/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.vmdecode;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.util.MimeUtils;

import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.MetadataRecord;
import de.diamaneos.sanitizer.decode.Thumbnail;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Image decoding for MediaProvider, done in a protected VM by the decode service (de.diamaneos.sanitizer). MediaProvider
 * hands over the bytes unopened (a file descriptor, never parsed here) and gets back checked pixels or checked numbers.
 * It never runs an image decoder on those bytes itself: when the service is unavailable every method throws
 * {@link VmDecodeUnavailableException} and the caller shows a placeholder or stores nothing and asks again later. There
 * is no setting or flag that turns this off.
 */
public final class VmDecode {
    private static final String TAG = "VmDecode";

    public static final int PRIORITY_USER_WAITING = IDecodeService.PRIORITY_USER_WAITING;
    public static final int PRIORITY_BACKGROUND = IDecodeService.PRIORITY_BACKGROUND;

    // Copying a region of a regular file into memory for the service: a deadline for the whole copy.
    private static final long COPY_DEADLINE_MILLIS = 10_000;

    private static final Object sLock = new Object();
    private static DecodeClient sClient;

    // Probe answers by file identity (device, inode, size, both change times). Rejections are kept too, for this
    // process's life, so an undecodable file is not sent again and again until it changes. The service keeps its own
    // cache by content hash, since it reads the bytes anyway, and here a stat is enough.
    private static final LruCache<String, Info> sProbeCache = new LruCache<>(512);
    private static final LruCache<String, String> sRejectedCache = new LruCache<>(512);

    private VmDecode() {
    }

    /** Called once from MediaProvider and its activities. Binding happens on first use. */
    public static void init(@NonNull Context context) {
        synchronized (sLock) {
            if (sClient == null) {
                sClient = new DecodeClient(context);
            }
        }
    }

    private static DecodeClient client() throws VmDecodeUnavailableException {
        synchronized (sLock) {
            if (sClient == null) {
                throw new VmDecodeUnavailableException("VmDecode used before init");
            }
            return sClient;
        }
    }

    /** What the VM found in an image header. */
    public static final class Info {
        public final int format;
        public final int width;
        public final int height;
        public final boolean animated;

        Info(int format, int width, int height, boolean animated) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.animated = animated;
        }

        public boolean isGif() {
            return format == IDecodeService.FORMAT_GIF;
        }

        public boolean isWebp() {
            return format == IDecodeService.FORMAT_WEBP;
        }
    }

    /**
     * Format, size and animation of an image, from its header. The service reads at most
     * IDecodeService.MAX_PROBE_BYTES from the start of the file, so this works for files of any size. Cached by file
     * identity, rejections included.
     */
    public static @NonNull Info probe(@NonNull File file, @Nullable String owner, int priority)
            throws IOException {
        final String key = identity(file);
        final Info cached = sProbeCache.get(key);
        if (cached != null) {
            return cached;
        }
        final String rejected = sRejectedCache.get(key);
        if (rejected != null) {
            throw new VmDecodeRejectedException(rejected);
        }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            final DecodeRequest request = request(pfd, owner, priority, mimeOf(file));
            final Info info = client().call(priority, service -> {
                final ImageInfo reply = service.probe(request);
                checkStatus(reply == null ? -1 : reply.status);
                try {
                    StripeValidator.checkInfo(reply.format, reply.width, reply.height, reply.animated);
                } catch (StripeValidator.BadReplyException e) {
                    throw new VmDecodeRejectedException("bad probe reply: " + e.getMessage());
                }
                return new Info(reply.format, reply.width, reply.height, reply.animated);
            });
            sProbeCache.put(key, info);
            return info;
        } catch (VmDecodeRejectedException e) {
            sRejectedCache.put(key, e.getMessage() == null ? "rejected" : e.getMessage());
            throw e;
        }
    }

    /** A thumbnail of an image file that fits in {@code size}, turned upright. */
    public static @NonNull Bitmap imageThumbnail(@NonNull File file, @NonNull Size size, @Nullable String owner,
            @Nullable CancellationSignal signal) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            return decode(pfd, owner, PRIORITY_USER_WAITING, mimeOf(file), size.getWidth(), size.getHeight(),
                    signal);
        }
    }

    /**
     * A thumbnail for an audio file, like ThumbnailUtils.createAudioThumbnail: the embedded picture, otherwise the best
     * album art file next to it. MediaMetadataRetriever only extracts the picture's bytes, in the media sandbox. The
     * bytes and the art files are decoded in the VM.
     */
    public static @NonNull Bitmap audioThumbnail(@NonNull File file, @NonNull Size size, @Nullable String owner,
            @Nullable CancellationSignal signal) throws IOException {
        if (signal != null) {
            signal.throwIfCanceled();
        }
        final byte[] picture;
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            picture = retriever.getEmbeddedPicture();
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }
        if (picture != null) {
            return decodeBytes(picture, owner, size, signal);
        }

        // The same search as ThumbnailUtils: only on external storage, not in Download or a top-level folder.
        if (Environment.MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(file))) {
            throw new IOException("No embedded album art found");
        }
        final File parent = file.getParentFile();
        final File grandParent = parent != null ? parent.getParentFile() : null;
        if (parent != null && parent.getName().equals(Environment.DIRECTORY_DOWNLOADS)) {
            throw new IOException("No thumbnails in Downloads directories");
        }
        if (grandParent != null
                && Environment.MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(grandParent))) {
            throw new IOException("No thumbnails in top-level directories");
        }
        final File[] found = parent == null ? null : parent.listFiles((dir, name) -> {
            final String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".png");
        });
        if (found == null || found.length == 0) {
            throw new IOException("No album art found");
        }
        final ToIntFunction<File> score = (f) -> {
            final String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.equals("albumart.jpg")) return 4;
            if (lower.startsWith("albumart") && lower.endsWith(".jpg")) return 3;
            if (lower.contains("albumart") && lower.endsWith(".jpg")) return 2;
            if (lower.endsWith(".jpg")) return 1;
            return 0;
        };
        final File best = Arrays.stream(found).max(Comparator.comparingInt(score)).orElse(null);
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return imageThumbnail(Objects.requireNonNull(best), size, owner, signal);
    }

    /**
     * A thumbnail for a video file, like ThumbnailUtils.createVideoThumbnail: the embedded picture (decoded in the
     * VM), otherwise a frame from MediaMetadataRetriever, which the media server decodes in its own sandbox.
     */
    public static @NonNull Bitmap videoThumbnail(@NonNull File file, @NonNull Size size, @Nullable String owner,
            @Nullable CancellationSignal signal) throws IOException {
        if (signal != null) {
            signal.throwIfCanceled();
        }
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(file.getAbsolutePath());
            final byte[] picture = mmr.getEmbeddedPicture();
            if (picture != null) {
                return decodeBytes(picture, owner, size, signal);
            }
            final MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
            params.setPreferredConfig(Bitmap.Config.ARGB_8888);
            final int width = Integer.parseInt(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            final int height = Integer.parseInt(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            final long thumbnailTimeUs = -1;
            if (size.getWidth() > width && size.getHeight() > height) {
                return Objects.requireNonNull(mmr.getFrameAtTime(thumbnailTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, params));
            }
            return Objects.requireNonNull(mmr.getScaledFrameAtTime(thumbnailTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.getWidth(), size.getHeight(), params));
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }
    }

    /**
     * An image from a content URI for display, fitting in {@code maxWidth} x {@code maxHeight}. For screens that show a
     * full image, such as the permission dialog.
     */
    public static @NonNull Bitmap decodeForDisplay(@NonNull ContentResolver resolver, @NonNull Uri uri,
            @Nullable String owner, int maxWidth, int maxHeight) throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new FileNotFoundException("no file for " + uri);
            }
            return decode(pfd, owner, PRIORITY_USER_WAITING, resolver.getType(uri), maxWidth, maxHeight, null);
        }
    }

    /**
     * An image from an asset, such as a thumbnail or a cloud media provider's reply, fitting in {@code maxWidth} x
     * {@code maxHeight}. The descriptor goes to the service as it is, so a pipe that never ends or stalls is read by
     * the service with its own deadline and limit, never by a thread here. Only a part of a regular file (an asset
     * with an offset or a shorter length) is copied, into a memory file, under a deadline.
     */
    public static @NonNull Bitmap decodeAsset(@NonNull AssetFileDescriptor asset, @Nullable String owner,
            int priority, int maxWidth, int maxHeight, @Nullable CancellationSignal signal) throws IOException {
        final ParcelFileDescriptor pfd = asset.getParcelFileDescriptor();
        final long offset = asset.getStartOffset();
        final long length = asset.getDeclaredLength();
        final StructStat st;
        try {
            st = Os.fstat(pfd.getFileDescriptor());
        } catch (ErrnoException e) {
            throw new IOException("asset not readable", e);
        }
        final boolean regular = OsConstants.S_ISREG(st.st_mode);
        if (!regular || (offset == 0 && (length < 0 || length == st.st_size))) {
            if (!regular && offset != 0) {
                throw new VmDecodeRejectedException("offset into a stream");
            }
            return decode(pfd, owner, priority, null, maxWidth, maxHeight, signal);
        }
        if (length < 0 || length > IDecodeService.MAX_INPUT_BYTES || offset < 0) {
            throw new VmDecodeRejectedException("asset part out of range");
        }
        try (ParcelFileDescriptor part = copyToMemory(pfd.getFileDescriptor(), offset, (int) length)) {
            return decode(part, owner, priority, null, maxWidth, maxHeight, signal);
        }
    }

    /** The checked metadata record of an image. */
    public static @NonNull Metadata readMetadata(@NonNull File file, @Nullable String owner, int priority)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            final DecodeRequest request = request(pfd, owner, priority, mimeOf(file));
            return client().call(priority, service -> {
                final MetadataRecord reply = service.readMetadata(request);
                checkStatus(reply == null ? -1 : reply.status);
                try {
                    return Metadata.check(reply, System.currentTimeMillis());
                } catch (StripeValidator.BadReplyException e) {
                    throw new VmDecodeRejectedException("bad metadata reply: " + e.getMessage());
                }
            });
        }
    }

    /** {@link MetadataRecord} after every field passed its rule. */
    public static final class Metadata {
        public final int format;
        public final int width;
        public final int height;
        public final int orientation;
        public final long dateTakenMillis;
        public final boolean offsetKnown;
        public final double latitude;
        public final double longitude;
        public final String description;
        public final String exposureTime;
        public final String fNumber;
        public final String iso;
        public final String sceneCaptureType;
        public final String documentId;
        public final String instanceId;
        public final String originalDocumentId;
        public final int specialFormat;

        private Metadata(MetadataRecord r, long nowMillis) throws StripeValidator.BadReplyException {
            StripeValidator.checkInfo(r.format, r.width, r.height);
            format = r.format;
            width = r.width;
            height = r.height;
            orientation = StripeValidator.checkOrientation(r.orientation);
            dateTakenMillis = StripeValidator.checkDateTaken(r.dateTakenMillis, r.offsetKnown, nowMillis);
            offsetKnown = r.offsetKnown;
            StripeValidator.checkLocation(r.latitude, r.longitude);
            latitude = r.latitude;
            longitude = r.longitude;
            description = StripeValidator.checkText(r.description, StripeValidator.MAX_TEXT_CHARS);
            exposureTime = StripeValidator.checkNumber(r.exposureTime);
            fNumber = StripeValidator.checkNumber(r.fNumber);
            iso = StripeValidator.checkNumber(r.iso);
            sceneCaptureType = StripeValidator.checkNumber(r.sceneCaptureType);
            documentId = StripeValidator.checkId(r.documentId);
            instanceId = StripeValidator.checkId(r.instanceId);
            originalDocumentId = StripeValidator.checkId(r.originalDocumentId);
            if (r.specialFormat < 0 || r.specialFormat > 3) {
                throw new StripeValidator.BadReplyException("bad special format " + r.specialFormat);
            }
            specialFormat = r.specialFormat;
        }

        static Metadata check(MetadataRecord r, long nowMillis) throws StripeValidator.BadReplyException {
            return new Metadata(r, nowMillis);
        }
    }

    /** Bytes already in memory, such as an embedded picture, as a memory file: no thread, nothing to block on. */
    private static Bitmap decodeBytes(byte[] bytes, @Nullable String owner, Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (bytes.length > IDecodeService.MAX_INPUT_BYTES) {
            throw new VmDecodeRejectedException("picture too large: " + bytes.length);
        }
        try (ParcelFileDescriptor memory = memoryFile(bytes, bytes.length)) {
            return decode(memory, owner, PRIORITY_USER_WAITING, null, size.getWidth(), size.getHeight(), signal);
        }
    }

    /** A part of a regular file copied into a memory file, with pread and a deadline for the whole copy. */
    private static ParcelFileDescriptor copyToMemory(FileDescriptor source, long offset, int length)
            throws IOException {
        final byte[] buffer = new byte[length];
        final long deadline = System.currentTimeMillis() + COPY_DEADLINE_MILLIS;
        int done = 0;
        try {
            while (done < length) {
                if (System.currentTimeMillis() > deadline) {
                    throw new VmDecodeRejectedException("asset copy too slow");
                }
                final int n = Os.pread(source, buffer, done, Math.min(65536, length - done), offset + done);
                if (n <= 0) {
                    throw new VmDecodeRejectedException("asset shorter than declared");
                }
                done += n;
            }
        } catch (ErrnoException e) {
            throw new IOException("asset not readable", e);
        }
        return memoryFile(buffer, length);
    }

    private static ParcelFileDescriptor memoryFile(byte[] bytes, int length) throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.memfd_create("vmdecode", OsConstants.MFD_CLOEXEC);
            int done = 0;
            while (done < length) {
                done += Os.write(fd, bytes, done, length - done);
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET);
            return ParcelFileDescriptor.dup(fd);
        } catch (ErrnoException e) {
            throw new IOException("memory file", e);
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException ignored) {
                    // Nothing to do.
                }
            }
        }
    }

    private static Bitmap decode(ParcelFileDescriptor pfd, @Nullable String owner, int priority,
            @Nullable String mimeType, int maxWidth, int maxHeight, @Nullable CancellationSignal signal)
            throws IOException {
        final DecodeRequest request = request(pfd, owner, priority, mimeType);
        return client().call(priority, service -> {
            final Thumbnail reply = service.decodeThumbnail(request, maxWidth, maxHeight);
            checkStatus(reply == null ? -1 : reply.status);
            try {
                final int stripes = StripeValidator.checkHeader(reply.width, reply.height, reply.stripeRows,
                        reply.rotation, maxWidth, maxHeight);
                final byte[] frame = new byte[reply.width * reply.height * 4];
                for (int i = 0; i < stripes; i++) {
                    if (signal != null && signal.isCanceled()) {
                        throw new RemoteException("canceled");
                    }
                    StripeValidator.putStripe(frame, reply.width, reply.height, reply.stripeRows, i,
                            service.readStripe(reply.handle, i));
                }
                final Bitmap bitmap = Bitmap.createBitmap(reply.width, reply.height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame));
                return rotate(bitmap, reply.rotation);
            } catch (StripeValidator.BadReplyException e) {
                throw new VmDecodeRejectedException("bad thumbnail reply: " + e.getMessage());
            } finally {
                try {
                    service.release(reply.handle);
                } catch (RemoteException ignored) {
                    // The service frees it after its timeout.
                }
            }
        });
    }

    /**
     * Maps a reply status: OK returns, unavailable or no reply (-1) become a RemoteException, so the call counts as
     * unavailable, and the rest reject the file.
     */
    private static void checkStatus(int status) throws RemoteException, VmDecodeRejectedException {
        switch (status) {
            case -1:
                throw new RemoteException("no reply");
            case IDecodeService.STATUS_OK:
                return;
            case IDecodeService.STATUS_UNAVAILABLE:
                throw new RemoteException("decode service unavailable");
            case IDecodeService.STATUS_UNSUPPORTED:
                throw new VmDecodeRejectedException("unsupported format");
            case IDecodeService.STATUS_INVALID:
                throw new VmDecodeRejectedException("invalid file");
            default:
                throw new VmDecodeRejectedException("bad status " + status);
        }
    }

    private static Bitmap rotate(Bitmap bitmap, int rotation) {
        if (rotation == 0) {
            return bitmap;
        }
        final Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        final Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix,
                false);
        if (turned != bitmap) {
            bitmap.recycle();
        }
        return turned;
    }

    private static DecodeRequest request(ParcelFileDescriptor pfd, @Nullable String owner, int priority,
            @Nullable String mimeType) {
        final DecodeRequest request = new DecodeRequest();
        request.file = pfd;
        request.ownerPackage = owner == null ? "" : owner;
        request.priority = priority;
        request.mimeTypeHint = mimeType == null ? "" : mimeType;
        return request;
    }

    private static String mimeOf(File file) {
        return MimeUtils.resolveMimeType(file);
    }

    /** File identity for the probe caches: any write changes the change time, so a changed file is probed again. */
    private static String identity(File file) throws IOException {
        try {
            final StructStat st = Os.stat(file.getAbsolutePath());
            return st.st_dev + ":" + st.st_ino + ":" + st.st_size + ":" + st.st_mtim.tv_sec + "."
                    + st.st_mtim.tv_nsec + ":" + st.st_ctim.tv_sec + "." + st.st_ctim.tv_nsec;
        } catch (ErrnoException e) {
            throw new FileNotFoundException(file + ": " + e.getMessage());
        }
    }
}
