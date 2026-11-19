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
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.util.MimeUtils;

import de.diamaneos.sanitizer.decode.DecodeChecks;
import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.Frames;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.ImageInfo;
import de.diamaneos.sanitizer.decode.MetadataRecord;
import de.diamaneos.sanitizer.decode.Thumbnail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    // Probe answers by file identity (device, inode, size, both change times). The service's verdicts on a file
    // (invalid, unsupported, a reply that broke the contract) are kept for REJECTED_MILLIS, so an undecodable file
    // is not sent again and again until it changes. Read problems (a slow or stalling source) are not kept. The
    // service keeps its own cache by content hash, since it reads the bytes anyway, and here a stat is enough.
    private static final long REJECTED_MILLIS = 60 * 60 * 1000;
    private static final LruCache<String, Info> sProbeCache = new LruCache<>(512);
    private static final LruCache<String, Rejected> sRejectedCache = new LruCache<>(512);

    private static final class Rejected {
        final String message;
        final long until;

        Rejected(String message, long until) {
            this.message = message;
            this.until = until;
        }
    }

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
        final Rejected rejected = sRejectedCache.get(key);
        if (rejected != null) {
            if (SystemClock.elapsedRealtime() < rejected.until) {
                throw new VmDecodeRejectedException(rejected.message, true);
            }
            sRejectedCache.remove(key);
        }
        try (ParcelFileDescriptor opened = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
                ParcelFileDescriptor pfd = safeSource(opened, owner, true)) {
            final DecodeRequest request = request(pfd, owner, priority, mimeOf(file));
            final Info info = client().call(priority, owner, service -> {
                final ImageInfo reply = service.probe(request);
                checkReply(reply == null, DecodeChecks.check(reply));
                checkStatus(reply.status);
                return new Info(reply.format, reply.width, reply.height, reply.animated);
            });
            sProbeCache.put(key, info);
            return info;
        } catch (VmDecodeRejectedException e) {
            if (e.verdict) {
                sRejectedCache.put(key, new Rejected(e.getMessage() == null ? "rejected" : e.getMessage(),
                        SystemClock.elapsedRealtime() + REJECTED_MILLIS));
            }
            throw e;
        }
    }

    /** A thumbnail of an image file that fits in {@code size}, turned upright. */
    public static @NonNull Bitmap imageThumbnail(@NonNull File file, @NonNull Size size, @Nullable String owner,
            @Nullable CancellationSignal signal) throws IOException {
        try (ParcelFileDescriptor opened = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
                ParcelFileDescriptor pfd = safeSource(opened, owner, false)) {
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
        try (ParcelFileDescriptor opened = resolver.openFileDescriptor(uri, "r")) {
            if (opened == null) {
                throw new FileNotFoundException("no file for " + uri);
            }
            try (ParcelFileDescriptor pfd = safeSource(opened, owner, false)) {
                return decode(pfd, owner, PRIORITY_USER_WAITING, resolver.getType(uri), maxWidth, maxHeight,
                        null);
            }
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
        return withAsset(asset, owner, pfd -> decode(pfd, owner, priority, null, maxWidth, maxHeight, signal));
    }

    /** As {@link #decodeAsset}, an animation (GIF or WebP) frame by frame. */
    public static @NonNull Animation decodeAssetAnimation(@NonNull AssetFileDescriptor asset, @Nullable String owner,
            int priority, int maxWidth, int maxHeight, @Nullable CancellationSignal signal) throws IOException {
        return withAsset(asset, owner, pfd -> decodeFrames(pfd, owner, priority, maxWidth, maxHeight, signal));
    }

    private interface WithFile<T> {
        T run(ParcelFileDescriptor pfd) throws IOException;
    }

    // The asset as the service takes it: a FUSE file or a part of a file through a memory file, else as it is.
    private static <T> T withAsset(AssetFileDescriptor asset, @Nullable String owner, WithFile<T> work)
            throws IOException {
        final ParcelFileDescriptor pfd = asset.getParcelFileDescriptor();
        final long offset = asset.getStartOffset();
        final long length = asset.getDeclaredLength();
        if (SourceReader.onFuse(pfd)) {
            // Even a stat can stall on FUSE: everything happens on a reader thread with a deadline, and the
            // service gets a memory file.
            final byte[] bytes = SourceReader.read(pfd, offset, length, IDecodeService.MAX_BUFFERED_BYTES, false,
                    owner, COPY_DEADLINE_MILLIS);
            try (ParcelFileDescriptor memory = memoryFile(bytes, bytes.length)) {
                return work.run(memory);
            }
        }
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
            return work.run(pfd);
        }
        if (length < 0 || length > IDecodeService.MAX_BUFFERED_BYTES || offset < 0) {
            throw new VmDecodeRejectedException("asset part out of range");
        }
        try (ParcelFileDescriptor part = copyToMemory(pfd.getFileDescriptor(), offset, (int) length)) {
            return work.run(part);
        }
    }

    /**
     * The package that supplied a picker item's bytes, for the decode service's fair queue: the media item's owner for
     * the local provider, the cloud media provider's package otherwise. Null when unknown.
     */
    public static @Nullable String pickerOwner(@NonNull Context context, @NonNull Uri uri) {
        try {
            // The picker's URIs carry the user before the host (content://0@authority/...): the host alone names
            // the provider.
            final String authority = uri.getHost();
            if (PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority)) {
                final String id = uri.getLastPathSegment();
                final Uri item = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, Long.parseLong(id));
                try (Cursor c = context.getContentResolver().query(item,
                        new String[] {MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null)) {
                    return c != null && c.moveToFirst() ? c.getString(0) : null;
                }
            }
            final ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
            return provider != null ? provider.packageName : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The photo picker app (com.android.photopicker) shows its images through MediaProvider: it calls
    // PICKER_IMAGE_CALL with the item's URI, the extras of its own openTypedAssetFile and the write end of a pipe.
    // MediaProvider opens the item, has it decoded in the VM and writes a PNG of the checked pixels into the pipe, so
    // the picker's image loader only ever decodes a PNG made here. The names are copied in the picker
    // (photopicker/src/com/android/photopicker/core/glide/PhotopickerMediaFetcher.kt).
    public static final String PICKER_IMAGE_CALL = "de.diamaneos.vmdecode.picker_image";
    public static final String EXTRA_PICKER_IMAGE_OUT = "de.diamaneos.vmdecode.picker_image_out";
    public static final String EXTRA_PICKER_IMAGE_MIME = "de.diamaneos.vmdecode.picker_image_mime";
    // A GIF or animated WebP preview: all frames in the VmFrames form instead of a PNG.
    public static final String EXTRA_PICKER_IMAGE_FRAMES = "de.diamaneos.vmdecode.picker_image_frames";
    // A few at a time, the rest wait briefly: the picker shows a grid, and a load past these is refused (the picker
    // retries when the cell comes back into view).
    private static final ThreadPoolExecutor sPickerImages = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64));

    static {
        sPickerImages.allowCoreThreadTimeOut(true);
    }

    /**
     * Starts one image for the photo picker app (see {@link #PICKER_IMAGE_CALL}) and returns at once. The caller is
     * checked by MediaProvider. The result goes into the pipe, an error closes it with that error. Never throws for a
     * bad item: the pipe carries the failure.
     */
    @SuppressWarnings("deprecation") // Bundle.getParcelable(String): the module also runs before API 33
    public static @NonNull Bundle pickerImage(@NonNull Context context, @Nullable String uriString,
            @Nullable Bundle extras) {
        if (uriString == null || extras == null) {
            throw new IllegalArgumentException("no item");
        }
        checkPickerUri(context, Uri.parse(uriString), Binder.getCallingPid(), Binder.getCallingUid());
        final ParcelFileDescriptor out = extras.getParcelable(EXTRA_PICKER_IMAGE_OUT);
        if (out == null) {
            throw new IllegalArgumentException("no pipe");
        }
        final Uri uri = Uri.parse(uriString);
        final String mime = extras.getString(EXTRA_PICKER_IMAGE_MIME, "image/*");
        // Only the options a picker image takes, nothing else from the caller.
        final Bundle opts = new Bundle();
        final Point size = extras.getParcelable(ContentResolver.EXTRA_SIZE);
        if (size != null) {
            opts.putParcelable(ContentResolver.EXTRA_SIZE, size);
        }
        opts.putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true);
        if (extras.getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB)) {
            opts.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB, true);
        }
        final boolean frames = extras.getBoolean(EXTRA_PICKER_IMAGE_FRAMES);
        try {
            sPickerImages.execute(() -> writePickerImage(context, uri, mime, opts, size, frames, out));
        } catch (RejectedExecutionException e) {
            closeWithError(out, "busy");
        }
        return new Bundle();
    }

    /**
     * Only a picker item the calling picker may itself read: a content URI of the local picker provider or of the
     * current cloud media provider, in this MediaProvider's user, of the form {@code /media/<id>}, with the caller's
     * own read access checked as its own open would check it. MediaProvider opens the item with its own identity
     * afterwards, so nothing else may pass here.
     */
    static void checkPickerUri(Context context, Uri uri, int pid, int uid) {
        final String host = uri.getHost();
        final String user = uri.getUserInfo();
        final java.util.List<String> path = uri.getPathSegments();
        String cloud = null;
        try {
            cloud = PickerSyncController.getInstanceOrThrow().getCloudProvider();
        } catch (IllegalStateException e) {
            // No picker data layer: only the local provider.
        }
        final boolean provider = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(host)
                || (cloud != null && cloud.equals(host));
        final boolean sameUser = user == null
                || user.equals(String.valueOf(android.os.UserHandle.myUserId()));
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || !provider || !sameUser
                || path.size() != 2 || !"media".equals(path.get(0)) || uri.getQuery() != null
                || uri.getFragment() != null
                || context.checkUriPermission(uri, pid, uid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("not a picker item the caller may read");
        }
    }

    // A picker that stops reading cannot hold a thread: each write waits at most this long.
    private static final int PICKER_WRITE_MILLIS = 10_000;

    private static boolean readerGone(ParcelFileDescriptor out) {
        try {
            final android.system.StructPollfd p = new android.system.StructPollfd();
            p.fd = out.getFileDescriptor();
            p.events = 0;
            return Os.poll(new android.system.StructPollfd[] {p}, 0) > 0
                    && (p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0;
        } catch (ErrnoException e) {
            return true;
        }
    }

    private static void writeWithDeadline(ParcelFileDescriptor out, byte[] bytes, int length) throws IOException {
        final FileDescriptor fd = out.getFileDescriptor();
        try {
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
            int done = 0;
            final long deadline = SystemClock.elapsedRealtime() + PICKER_WRITE_MILLIS;
            while (done < length) {
                final long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    throw new IOException("picker stopped reading");
                }
                final android.system.StructPollfd p = new android.system.StructPollfd();
                p.fd = fd;
                p.events = (short) OsConstants.POLLOUT;
                if (Os.poll(new android.system.StructPollfd[] {p}, (int) left) <= 0) {
                    continue;
                }
                if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0) {
                    throw new IOException("picker closed its end");
                }
                try {
                    done += Os.write(fd, bytes, done, Math.min(65536, length - done));
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EAGAIN) {
                        throw e;
                    }
                }
            }
        } catch (ErrnoException e) {
            throw new IOException("picker pipe", e);
        }
    }

    private static void writePickerImage(Context context, Uri uri, String mime, Bundle opts, @Nullable Point size,
            boolean frames, ParcelFileDescriptor out) {
        // MediaProvider's own identity opens the item, checked against the picker's own access (checkPickerUri).
        final CancellationSignal signal = new CancellationSignal();
        boolean written = false;
        // A cell that scrolled away closed its end: no decode for it.
        if (readerGone(out)) {
            closeWithError(out, "gone");
            return;
        }
        try (AssetFileDescriptor asset = context.getContentResolver().openTypedAssetFile(uri, mime, opts, signal)) {
            if (asset == null) {
                closeWithError(out, "no data");
                return;
            }
            if (readerGone(out)) {
                closeWithError(out, "gone");
                return;
            }
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            final int screen = Math.max(metrics.widthPixels, metrics.heightPixels);
            final int maxWidth = size != null && size.x > 0 ? size.x : screen;
            final int maxHeight = size != null && size.y > 0 ? size.y : screen;
            final byte[] bytes;
            if (frames) {
                final Animation a = decodeAssetAnimation(asset, pickerOwner(context, uri), PRIORITY_USER_WAITING,
                        maxWidth, maxHeight, signal);
                bytes = a.encoded;
            } else {
                final Bitmap bitmap = decodeAsset(asset, pickerOwner(context, uri), PRIORITY_USER_WAITING, maxWidth,
                        maxHeight, signal);
                final ByteArrayOutputStream png = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
                bitmap.recycle();
                bytes = png.toByteArray();
            }
            writeWithDeadline(out, bytes, bytes.length);
            written = true;
        } catch (Throwable t) {
            // Out of memory included: the thread carries on, and the picker's read always ends.
            Log.w(TAG, "picker image: " + t);
        } finally {
            if (written) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // The picker gave up.
                }
            } else {
                closeWithError(out, "not decoded");
            }
        }
    }

    private static void closeWithError(ParcelFileDescriptor out, String why) {
        try {
            out.closeWithError(why);
        } catch (IOException | RuntimeException ignored) {
            // Closed already: the picker gave up.
        }
    }

    /** The checked metadata record of an image. */
    public static @NonNull Metadata readMetadata(@NonNull File file, @Nullable String owner, int priority)
            throws IOException {
        try (ParcelFileDescriptor opened = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY);
                ParcelFileDescriptor pfd = safeSource(opened, owner, false)) {
            final DecodeRequest request = request(pfd, owner, priority, mimeOf(file));
            return client().call(priority, owner, service -> {
                final MetadataRecord reply = service.readMetadata(request);
                if (reply != null) {
                    DecodeChecks.dropFutureDate(reply, System.currentTimeMillis());
                }
                checkReply(reply == null, DecodeChecks.check(reply, System.currentTimeMillis()));
                checkStatus(reply.status);
                return new Metadata(reply);
            });
        }
    }

    /** {@link MetadataRecord} after every field passed its rule (DecodeChecks). */
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

        /** Only after DecodeChecks.check passed. */
        private Metadata(MetadataRecord r) {
            format = r.format;
            width = r.width;
            height = r.height;
            orientation = r.orientation;
            dateTakenMillis = r.dateTakenMillis;
            offsetKnown = r.offsetKnown;
            latitude = r.latitude;
            longitude = r.longitude;
            description = r.description;
            exposureTime = r.exposureTime;
            fNumber = r.fNumber;
            iso = r.iso;
            sceneCaptureType = r.sceneCaptureType;
            documentId = r.documentId;
            instanceId = r.instanceId;
            originalDocumentId = r.originalDocumentId;
            specialFormat = r.specialFormat;
        }
    }

    /** Bytes already in memory, such as an embedded picture, as a memory file: no thread, nothing to block on. */
    private static Bitmap decodeBytes(byte[] bytes, @Nullable String owner, Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (bytes.length > IDecodeService.MAX_BUFFERED_BYTES) {
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

    /**
     * The descriptor to hand to the service. A file on FUSE is read here first, on a reader thread with a deadline
     * (at most MAX_PROBE_BYTES for a probe), and the service gets a memory file, so a stalling FUSE daemon can hold
     * up neither MediaProvider nor the service. Anything else goes as it is, as a new descriptor the caller closes.
     */
    private static ParcelFileDescriptor safeSource(ParcelFileDescriptor pfd, @Nullable String owner,
            boolean probe) throws IOException {
        if (!SourceReader.onFuse(pfd)) {
            return pfd.dup();
        }
        final byte[] bytes = SourceReader.read(pfd, 0, -1,
                probe ? IDecodeService.MAX_PROBE_BYTES : IDecodeService.MAX_BUFFERED_BYTES, probe, owner,
                COPY_DEADLINE_MILLIS);
        return memoryFile(bytes, bytes.length);
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
        return client().call(priority, owner, service -> {
            final Thumbnail reply = service.decodeThumbnail(request, maxWidth, maxHeight);
            checkReply(reply == null, DecodeChecks.check(reply, maxWidth, maxHeight));
            checkStatus(reply.status);
            try {
                // Every stripe is checked by DecodeChecks (exact length, no colour above alpha) before it is
                // copied, so the frame only ever holds valid premultiplied pixels of the checked size.
                final int rowBytes = reply.width * 4;
                final int stripes = (reply.height + reply.stripeRows - 1) / reply.stripeRows;
                final byte[] frame = new byte[rowBytes * reply.height];
                for (int i = 0; i < stripes; i++) {
                    if (signal != null && signal.isCanceled()) {
                        throw new RemoteException("canceled");
                    }
                    final byte[] stripe = service.readStripe(reply.handle, i);
                    final String bad = DecodeChecks.check(reply, i, stripe);
                    if (bad != null) {
                        throw new VmDecodeRejectedException("bad stripe " + i + ": " + bad, true);
                    }
                    System.arraycopy(stripe, 0, frame, i * reply.stripeRows * rowBytes, stripe.length);
                }
                // In the file's colour space, as Android's decode gives it (sRGB or Display P3).
                final Bitmap bitmap = Bitmap.createBitmap(reply.width, reply.height, Bitmap.Config.ARGB_8888,
                        true, DecodeChecks.colorSpaceOf(reply));
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame));
                return rotate(bitmap, reply.rotation);
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
     * A decoded animation, in VmFrames' form: the header (size, frame count, loop count, colour space, each frame's
     * time), then every frame's checked pixels, held once.
     */
    public static final class Animation {
        public final byte[] encoded;

        Animation(byte[] encoded) {
            this.encoded = encoded;
        }
    }

    private static Animation decodeFrames(ParcelFileDescriptor pfd, @Nullable String owner, int priority,
            int maxWidth, int maxHeight, @Nullable CancellationSignal signal) throws IOException {
        final DecodeRequest request = request(pfd, owner, priority, null);
        return client().call(priority, owner, service -> {
            final Frames reply = service.decodeFrames(request, maxWidth, maxHeight);
            checkReply(reply == null, DecodeChecks.check(reply, maxWidth, maxHeight));
            checkStatus(reply.status);
            try {
                // One tall image, frame after frame, every stripe checked before it is copied straight behind the
                // VmFrames header (DecodeChecks bounds it to MAX_FRAMES_BYTES).
                final int rowBytes = reply.width * 4;
                final int rows = reply.frameCount * reply.height;
                final int stripes = (rows + reply.stripeRows - 1) / reply.stripeRows;
                final int head = com.android.providers.media.vmdecode.frames.VmFrames.headerBytes(reply.frameCount);
                final byte[] all = new byte[head + rowBytes * rows];
                com.android.providers.media.vmdecode.frames.VmFrames.putHeader(all, reply.width, reply.height,
                        reply.frameCount, reply.loopCount,
                        reply.colorSpace == IDecodeService.COLOR_SPACE_DISPLAY_P3, reply.durations);
                for (int i = 0; i < stripes; i++) {
                    if (signal != null && signal.isCanceled()) {
                        throw new RemoteException("canceled");
                    }
                    final byte[] stripe = service.readStripe(reply.handle, i);
                    final String bad = DecodeChecks.check(reply, i, stripe);
                    if (bad != null) {
                        throw new VmDecodeRejectedException("bad stripe " + i + ": " + bad, true);
                    }
                    System.arraycopy(stripe, 0, all, head + i * reply.stripeRows * rowBytes, stripe.length);
                }
                return new Animation(all);
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
     * A reply that broke the contract (DecodeChecks) rejects the file, like STATUS_INVALID. No reply at all counts as
     * unavailable.
     */
    private static void checkReply(boolean missing, @Nullable String bad)
            throws RemoteException, VmDecodeRejectedException {
        if (missing) {
            throw new RemoteException("no reply");
        }
        if (bad != null) {
            throw new VmDecodeRejectedException("reply broke the contract: " + bad, true);
        }
    }

    /** Maps a checked reply's status: OK returns, unavailable counts as unavailable, the rest reject the file. */
    private static void checkStatus(int status) throws RemoteException, VmDecodeRejectedException {
        switch (status) {
            case IDecodeService.STATUS_OK:
                return;
            case IDecodeService.STATUS_UNAVAILABLE:
                throw new RemoteException("decode service unavailable");
            case IDecodeService.STATUS_UNSUPPORTED:
                throw new VmDecodeRejectedException("unsupported format", true);
            case IDecodeService.STATUS_INVALID:
                throw new VmDecodeRejectedException("invalid file", true);
            default:
                throw new VmDecodeRejectedException("bad status " + status, true);
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
