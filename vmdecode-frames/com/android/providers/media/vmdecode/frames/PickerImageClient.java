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

package com.android.providers.media.vmdecode.frames;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Cancellable, typed picker requests whose transient failures remain retryable until the tile leaves. */
public final class PickerImageClient implements AutoCloseable {
    public static final String METHOD = "de.diamaneos.vmdecode.picker_image";
    public static final String OUT = "de.diamaneos.vmdecode.picker_image_out";
    public static final String MIME = "de.diamaneos.vmdecode.picker_image_mime";
    public static final String FRAMES = "de.diamaneos.vmdecode.picker_image_frames";
    public static final String RECEIVER = "de.diamaneos.vmdecode.picker_receiver";
    public static final String BITMAP = "de.diamaneos.vmdecode.picker_bitmap";
    public static final String FRAME_FILE = "de.diamaneos.vmdecode.picker_frame_file";
    public static final String ERROR = "de.diamaneos.vmdecode.picker_error";
    public static final int OK = 0;
    public static final int LATER = 1;
    public static final int REJECTED = 2;
    public static final int GONE = 3;

    public static final class Missing extends IOException {
        Missing(String message) { super(message); }
    }
    private static final long TOTAL_MS = 60_000;
    private static final long ATTEMPT_MS = 30_000;
    private boolean mCancelled;
    private ParcelFileDescriptor mRead;

    private static final class Later extends IOException {
        Later(String message) { super(message); }
    }

    public PickerImage load(ContentResolver resolver, Uri uri, Bundle options) throws IOException {
        final long end = SystemClock.uptimeMillis() + TOTAL_MS;
        long wait = 1000;
        while (true) {
            try {
                return attempt(resolver, uri, options, Math.min(end,
                        SystemClock.uptimeMillis() + ATTEMPT_MS));
            } catch (Later e) {
                synchronized (this) {
                    final long left = end - SystemClock.uptimeMillis();
                    if (mCancelled || left <= 0) throw e;
                    try {
                        wait(Math.min(wait, left));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Picker retry interrupted", interrupted);
                    }
                    if (mCancelled) throw new IOException("Picker request cancelled");
                }
                wait = Math.min(wait * 2, 5000);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private PickerImage attempt(ContentResolver resolver, Uri uri, Bundle options, long end)
            throws IOException {
        final CompletableFuture<PickerImage> result = new CompletableFuture<>();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        synchronized (this) {
            if (mCancelled) {
                pipe[0].close();
                pipe[1].close();
                throw new IOException("Picker request cancelled");
            }
            // Publication precedes the provider call, so cancellation cannot be lost there.
            mRead = pipe[0];
        }
        final Bundle extras = new Bundle(options);
        extras.putParcelable(OUT, pipe[1]);
        final ResultReceiver receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                try {
                    if (code == OK && data != null) {
                        final Bitmap received = data.getParcelable(BITMAP);
                        final ParcelFileDescriptor frameFile = data.getParcelable(FRAME_FILE);
                        if (received != null && frameFile != null) {
                            received.recycle();
                            frameFile.close();
                            throw new IOException("Picker returned two result types");
                        }
                        final Bitmap bitmap;
                        if (received != null) {
                            final Parcel parcel = Parcel.obtain();
                            try {
                                received.writeToParcel(parcel, 0);
                                parcel.setDataPosition(0);
                                bitmap = Bitmap.CREATOR.createFromParcel(parcel);
                            } finally {
                                parcel.recycle();
                                received.recycle();
                            }
                        } else {
                            bitmap = null;
                        }
                        final ParcelFileDescriptor frames = frameFile == null ? null : frameFile.dup();
                        if (frameFile != null) frameFile.close();
                        final PickerImage image = new PickerImage(bitmap, frames);
                        if (!result.complete(image)) image.close();
                    } else {
                        final String message = data == null ? "No picker result" : data.getString(ERROR);
                        result.completeExceptionally(code == LATER ? new Later(message)
                                : code == GONE ? new Missing(message) : new IOException(message));
                    }
                } catch (IOException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            }
        };
        extras.putParcelable(RECEIVER, frameworkReceiver(receiver));
        boolean delivered = false;
        try {
            final String authority = uri.getUserInfo() == null ? MediaStore.AUTHORITY
                    : uri.getUserInfo() + "@" + MediaStore.AUTHORITY;
            resolver.call(authority, METHOD, uri.toString(), extras);
            pipe[1].close();
            while (true) {
                synchronized (this) {
                    if (mCancelled) throw new IOException("Picker request cancelled");
                }
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0) throw new Later("Picker result deadline expired");
                try {
                    final PickerImage image = result.get(Math.min(left, 50), TimeUnit.MILLISECONDS);
                    delivered = true;
                    return image;
                } catch (TimeoutException e) {
                    // Poll the cancellation flag without a thread dedicated to each request.
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw new IOException("Picker result failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Picker result interrupted", e);
        } catch (RuntimeException e) {
            throw new IOException("Picker provider refused the request", e);
        } finally {
            if (!delivered && !result.cancel(false)) {
                result.thenAccept(image -> {
                    try { image.close(); } catch (IOException ignored) { }
                });
            }
            synchronized (this) {
                if (mRead == pipe[0]) mRead = null;
            }
            close(pipe[0]);
            close(pipe[1]);
        }
    }

    private static ResultReceiver frameworkReceiver(ResultReceiver receiver) {
        final Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() {
        mCancelled = true;
        notifyAll();
        if (mRead != null) {
            try {
                mRead.close();
            } catch (IOException ignored) {
                // The request has already closed its liveness descriptor.
            }
            mRead = null;
        }
    }
}
