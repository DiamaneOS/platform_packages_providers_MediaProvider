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
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.provider.CloudMediaProviderContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.DisplayMetrics;
import com.android.providers.media.vmdecode.frames.PickerImageClient;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Typed picker results and source-open cancellation, without a host image encode or second decode. */
final class PickerImages {
    private static final ThreadPoolExecutor sImages = new ThreadPoolExecutor(4, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), work -> {
                final Thread thread = new Thread(work, "VmPickerImage");
                thread.setDaemon(true);
                return thread;
            });
    static { sImages.allowCoreThreadTimeOut(true); }

    @SuppressWarnings("deprecation")
    static Bundle start(Context context, String value, Bundle extras) {
        if (value == null || extras == null) throw new IllegalArgumentException("No picker item");
        final Uri uri = Uri.parse(value);
        final int callerPid = Binder.getCallingPid();
        final int callerUid = Binder.getCallingUid();
        final long end = android.os.SystemClock.uptimeMillis() + 25_000;
        final ParcelFileDescriptor received = extras.getParcelable(PickerImageClient.OUT);
        final ResultReceiver receiver = extras.getParcelable(PickerImageClient.RECEIVER);
        if (received == null || receiver == null) throw new IllegalArgumentException("No picker result receiver");
        final ParcelFileDescriptor out;
        try {
            out = received.dup();
        } catch (IOException e) {
            answer(receiver, PickerImageClient.REJECTED, "Invalid picker liveness descriptor");
            return new Bundle();
        } finally {
            close(received);
        }
        final Bundle options = new Bundle();
        final Point size = extras.getParcelable(ContentResolver.EXTRA_SIZE);
        if (size != null) options.putParcelable(ContentResolver.EXTRA_SIZE, size);
        options.putBoolean(CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, true);
        options.putBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB,
                extras.getBoolean(CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB));
        final String mime = extras.getString(PickerImageClient.MIME, "image/*");
        final boolean frames = extras.getBoolean(PickerImageClient.FRAMES);
        final Runnable busy = () -> {
            answer(receiver, PickerImageClient.LATER, "Picker authorization temporarily unavailable");
            close(out);
        };
        final Runnable denied = () -> {
            answer(receiver, PickerImageClient.REJECTED, "Picker item access refused");
            close(out);
        };
        // DiamaneOS: local checks retain capacity while cloud permission calls are held.
        final boolean local = com.android.providers.media.photopicker.PickerSyncController
                .LOCAL_PICKER_PROVIDER_AUTHORITY.equals(uri.getHost());
        PickerAuthorization.submit(local, () -> providerUid(context, uri),
                () -> VmDecode.checkPickerUri(context, uri, callerPid, callerUid), () -> {
                    if (readerGone(out) || android.os.SystemClock.uptimeMillis() >= end) {
                        busy.run();
                        return;
                    }
                    try {
                        sImages.execute(() -> run(context, uri, mime, options, size, frames,
                                out, receiver, end));
                    } catch (RuntimeException failure) { busy.run(); }
                }, busy, denied);
        return new Bundle();
    }

    private static int providerUid(Context context, Uri uri) {
        final int user = VmMediaRows.uriUser(context, uri);
        if (!VmMediaUsers.serves(context, user)) throw new SecurityException("Unserved picker user");
        if (com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                .equals(uri.getHost())) return android.os.Process.myUid();
        final android.content.pm.ProviderInfo provider = VmMediaUsers.contextForUser(context, user)
                .getPackageManager().resolveContentProvider(uri.getHost(), 0);
        return provider == null || provider.applicationInfo == null ? -1 : provider.applicationInfo.uid;
    }

    private static void run(Context context, Uri uri, String mime, Bundle options, Point size,
            boolean frames, ParcelFileDescriptor out, ResultReceiver receiver, long end) {
        final CancellationSignal signal = new CancellationSignal();
        final Runnable checkpoint = () -> {
            if (readerGone(out)) {
                SourceReader.cancel(signal);
                throw new OperationCanceledException();
            }
        };
        try (VmDecode.Bound bound = VmDecode.boundUntil(end, signal, checkpoint)) {
            checkpoint.run();
            final VmMediaRows.Source source = SourceReader.pickerSource(context, uri);
            try (VmDecode.UserScope user = VmDecode.forUser(source.user);
                    AssetFileDescriptor asset = SourceReader.openPicker(context, uri, mime, options, signal)) {
            SourceReader.verifyPicker(context, uri, source);
            VmDecode.prepareForUser(source.user);
            checkpoint.run();
            final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            final int screen = Math.max(metrics.widthPixels, metrics.heightPixels);
            final int width = size != null && size.x > 0 ? size.x : screen;
            final int height = size != null && size.y > 0 ? size.y : screen;
            final String principal = source.principal;
            final Bundle result = new Bundle();
            if (frames) {
                final VmDecode.Animation animation = VmDecode.decodeAssetAnimation(asset, principal,
                        VmDecode.PRIORITY_USER_WAITING, width, height, signal);
                try (ParcelFileDescriptor file = VmDecode.memoryFile(animation.encoded,
                        animation.encoded.length)) {
                    checkpoint.run();
                    // The callback takes its own descriptor before this scope closes.
                    result.putParcelable(PickerImageClient.FRAME_FILE, file);
                    receiver.send(PickerImageClient.OK, result);
                }
            } else {
                final Bitmap bitmap = VmDecode.decodeAsset(asset, principal,
                        VmDecode.PRIORITY_USER_WAITING, width, height, signal);
                Bitmap shared = null;
                try {
                    checkpoint.run();
                    shared = bitmap.asShared();
                    result.putParcelable(PickerImageClient.BITMAP, shared);
                    receiver.send(PickerImageClient.OK, result);
                } finally {
                    if (shared != null && shared != bitmap) shared.recycle();
                    bitmap.recycle();
                }
            }
            }
        } catch (OperationCanceledException e) {
            // The tile has left and no result is delivered.
        } catch (VmDecodeRejectedException e) {
            answer(receiver, e.verdict ? PickerImageClient.REJECTED : PickerImageClient.LATER,
                    "Picker image refused");
        } catch (IOException e) {
            answer(receiver, PickerImageClient.LATER, "Picker image temporarily unavailable");
        } catch (RuntimeException | OutOfMemoryError e) {
            answer(receiver, PickerImageClient.REJECTED, "Picker image not available");
        } finally {
            SourceReader.cancel(signal);
            close(out);
        }
    }

    private static void answer(ResultReceiver receiver, int code, String message) {
        final Bundle result = new Bundle();
        result.putString(PickerImageClient.ERROR, message);
        receiver.send(code, result);
    }

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

    private static void close(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // The request already ended.
        }
    }
}
