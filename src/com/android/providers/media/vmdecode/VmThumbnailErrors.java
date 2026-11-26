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

import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Typed first-hop failures and reliable-pipe status before the data descriptor reaches EOF. */
public final class VmThumbnailErrors {
    public static final String LATER = "de.diamaneos.vmdecode.thumbnail_later";
    public static final String STATUS = "de.diamaneos.vmdecode.thumbnail_status";
    public static final int GONE = 1;
    private static final String REJECTED = "decode image rejected: ";
    private VmThumbnailErrors() { }
    public interface Open { ParcelFileDescriptor run() throws FileNotFoundException; }

    public static AssetFileDescriptor gone() throws FileNotFoundException { return status(GONE); }
    public static AssetFileDescriptor later() throws FileNotFoundException { return status(0); }

    private static AssetFileDescriptor status(int code) throws FileNotFoundException {
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            pipe[1].close();
            final Bundle status = new Bundle();
            if (code != 0) status.putInt(STATUS, code);
            status.putBoolean(LATER, true);
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH, status);
        } catch (IOException failure) {
            throw new FileNotFoundException("Thumbnail status unavailable");
        }
    }

    public static AssetFileDescriptor open(Open open) throws FileNotFoundException {
        try {
            return new AssetFileDescriptor(open.run(), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (FileNotFoundException e) {
            if (!VmDecodeUnavailableException.isUnavailable(e)) throw e;
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                pipe[1].close();
                final Bundle status = new Bundle();
                status.putBoolean(LATER, true);
                return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH, status);
            } catch (IOException failure) {
                throw e;
            }
        }
    }

    public static IOException classify(IOException failure) {
        if (VmMediaGoneException.isGone(failure)) {
            return failure instanceof VmMediaGoneException ? failure
                    : new VmMediaGoneException("Thumbnail item no longer exists");
        }
        if (VmDecodeUnavailableException.isUnavailable(failure)) {
            return failure instanceof VmDecodeUnavailableException ? failure
                    : new VmDecodeUnavailableException("Thumbnail source temporarily unavailable", failure);
        }
        if (failure.getMessage() != null && failure.getMessage().contains(REJECTED)) {
            return new VmDecodeRejectedException("Thumbnail source rejected", true);
        }
        return failure;
    }

    public static VmDecodeUnavailableException busy(Throwable cause) {
        return new VmDecodeUnavailableException("Thumbnail generation queue full", cause);
    }

    public static void failPipe(ParcelFileDescriptor descriptor, Throwable failure) {
        final String error = failure instanceof VmDecodeRejectedException
                && ((VmDecodeRejectedException) failure).verdict ? REJECTED + "image not accepted"
                : VmDecodeUnavailableException.PREFIX + "thumbnail generation did not complete";
        try {
            descriptor.closeWithError(error);
        } catch (IOException ignored) {
            // The thumbnail reader already left.
        }
    }
}
