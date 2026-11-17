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
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Image decoding for MediaProvider, done in a protected VM by the decode service (de.diamaneos.sanitizer). MediaProvider
 * hands over the file as opaque bytes and gets back checked pixels or checked numbers. It never runs an image decoder
 * on a file itself: when the service is unavailable every method throws {@link VmDecodeUnavailableException} and the
 * caller shows a placeholder or stores nothing and asks again later. There is no setting or flag that turns this off.
 */
public final class VmDecode {
    private static final String TAG = "VmDecode";

    public static final int PRIORITY_USER_WAITING = IDecodeService.PRIORITY_USER_WAITING;
    public static final int PRIORITY_BACKGROUND = IDecodeService.PRIORITY_BACKGROUND;

    private static final Object sLock = new Object();
    private static DecodeClient sClient;

    // Probe results by file identity (device, inode, size, change times). The service keeps its own cache by content
    // hash, since it reads every byte anyway. Here a stat is enough and costs no reading.
    private static final LruCache<String, Info> sProbeCache = new LruCache<>(512);

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

    /** Format, size and animation of an image, from its header. Cached by file identity. */
    public static @NonNull Info probe(@NonNull File file, @Nullable String owner, int priority)
            throws IOException {
        final String key = identity(file);
        final Info cached = sProbeCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            final DecodeRequest request = request(pfd, owner, priority, mimeOf(file));
            final Info info = client().call(priority, service -> {
                final ImageInfo reply = service.probe(request);
                checkStatus(reply == null ? -1 : reply.status);
                try {
                    StripeValidator.checkInfo(reply.format, reply.width, reply.height);
                } catch (StripeValidator.BadReplyException e) {
                    throw new VmDecodeRejectedException("bad probe reply: " + e.getMessage());
                }
                return new Info(reply.format, reply.width, reply.height, reply.animated);
            });
            sProbeCache.put(key, info);
            return info;
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
     * A thumbnail for an audio file: the embedded picture, or an album art file next to it. MediaMetadataRetriever only
     * extracts the embedded picture's bytes, in the media sandbox. The bytes are decoded in the VM.
     */
    public static @NonNull Bitmap audioThumbnail(@NonNull File file, @NonNull Size size, @Nullable String owner,
            @Nullable CancellationSignal signal) throws IOException {
        byte[] picture = null;
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            picture = retriever.getEmbeddedPicture();
        } catch (RuntimeException e) {
            Log.w(TAG, "no embedded picture: " + e.getMessage());
        }
        if (picture != null) {
            return decodeBytes(picture, owner, size, signal);
        }
        // Album art files in the same folder, the largest first, like ThumbnailUtils.
        final File parent = file.getParentFile();
        final File[] found = parent == null ? null : parent.listFiles((dir, name) -> {
            final String lower = name.toLowerCase(Locale.ROOT);
            return (lower.startsWith("albumart") || lower.startsWith("cover") || lower.startsWith("folder"))
                    && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"));
        });
        if (found != null) {
            Arrays.sort(found, (a, b) -> Long.compare(b.length(), a.length()));
            for (File art : found) {
                try {
                    return imageThumbnail(art, size, owner, signal);
                } catch (VmDecodeRejectedException e) {
                    Log.w(TAG, "album art not usable: " + art.getName());
                }
            }
        }
        throw new FileNotFoundException("No album art found");
    }

    /**
     * An image from a content URI for display, fitting in {@code maxWidth} x {@code maxHeight}. For screens that show a
     * full image, such as the permission dialog.
     */
    public static @NonNull Bitmap decodeForDisplay(@NonNull ContentResolver resolver, @NonNull Uri uri, int maxWidth,
            int maxHeight) throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new FileNotFoundException("no file for " + uri);
            }
            return decode(pfd, null, PRIORITY_USER_WAITING, resolver.getType(uri), maxWidth, maxHeight, null);
        }
    }

    /**
     * An image from a stream, such as a cloud media provider's reply, fitting in {@code maxWidth} x {@code maxHeight}.
     * At most IDecodeService.MAX_INPUT_BYTES are read, a longer stream is rejected.
     */
    public static @NonNull Bitmap decodeStream(@NonNull InputStream in, @Nullable String owner, int priority,
            int maxWidth, int maxHeight, @Nullable CancellationSignal signal) throws IOException {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final boolean[] tooLong = new boolean[1];
        final Thread writer = new Thread(() -> {
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                final byte[] buffer = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > IDecodeService.MAX_INPUT_BYTES) {
                        tooLong[0] = true;
                        return;
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                // The service stopped reading, or the source failed: the reply says what happened.
            }
        }, "VmDecodeStream");
        writer.start();
        final Bitmap bitmap;
        try (ParcelFileDescriptor read = pipe[0]) {
            bitmap = decode(read, owner, priority, null, maxWidth, maxHeight, signal);
        }
        try {
            writer.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (tooLong[0]) {
            bitmap.recycle();
            throw new VmDecodeRejectedException("stream longer than " + IDecodeService.MAX_INPUT_BYTES);
        }
        return bitmap;
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
                    return Metadata.check(reply);
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

        private Metadata(MetadataRecord r) throws StripeValidator.BadReplyException {
            StripeValidator.checkInfo(r.format, r.width, r.height);
            format = r.format;
            width = r.width;
            height = r.height;
            orientation = StripeValidator.checkOrientation(r.orientation);
            if (r.dateTakenMillis < -1) {
                throw new StripeValidator.BadReplyException("bad date");
            }
            dateTakenMillis = r.dateTakenMillis;
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

        static Metadata check(MetadataRecord r) throws StripeValidator.BadReplyException {
            return new Metadata(r);
        }
    }

    private static Bitmap decodeBytes(byte[] bytes, @Nullable String owner, Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (bytes.length > IDecodeService.MAX_INPUT_BYTES) {
            throw new VmDecodeRejectedException("picture too large: " + bytes.length);
        }
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        final Thread writer = new Thread(() -> {
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                out.write(bytes);
            } catch (IOException e) {
                // The service stopped reading: its reply says what happened.
            }
        }, "VmDecodeBytes");
        writer.start();
        try (ParcelFileDescriptor read = pipe[0]) {
            return decode(read, owner, PRIORITY_USER_WAITING, null, size.getWidth(), size.getHeight(), signal);
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

    /** File identity for the probe cache: any write changes the change time, so a changed file is probed again. */
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
