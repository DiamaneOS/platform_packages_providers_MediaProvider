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

import android.graphics.Bitmap;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.ErrnoException;
import com.android.providers.media.util.FileUtils;

/** A cache file is publishable only after the encoder reports success. */
public final class ThumbnailEncoding {
    private ThumbnailEncoding() { }
    public static void jpeg(Bitmap bitmap, OutputStream output) throws IOException {
        encode(output, sink -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, sink));
    }
    interface Encoder { boolean write(OutputStream output) throws IOException; }
    interface EncoderSource { Encoder read() throws IOException; }
    static void encode(OutputStream output, Encoder encoder) throws IOException {
        if (!encoder.write(output)) {
            throw new IOException("Thumbnail encoder did not finish");
        }
    }
    public interface BitmapSource { Bitmap read() throws IOException; }
    public interface CacheFile { File get() throws IOException; }
    public interface Cleanup { void delete(File file); }

    public static void publish(ParcelFileDescriptor descriptor, BitmapSource source, CacheFile cache,
            Cleanup cleanup) throws IOException {
        publishEncoded(descriptor, () -> {
            final Bitmap bitmap = source.read();
            if (bitmap == null) throw new IOException("Thumbnail bitmap not made");
            return sink -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, sink);
        }, cache, cleanup);
    }

    interface Rename { void run(File temporary, File target) throws IOException; }

    static void publishEncoded(ParcelFileDescriptor descriptor, EncoderSource source, CacheFile cache,
            Cleanup cleanup) throws IOException {
        publishEncoded(descriptor, source, cache, cleanup, (temporary, target) -> {
            try { Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath()); }
            catch (ErrnoException failure) { throw failure.rethrowAsIOException(); }
        });
    }

    static void publishEncoded(ParcelFileDescriptor descriptor, EncoderSource source, CacheFile cache,
            Cleanup cleanup, Rename rename) throws IOException {
        File temporary = null;
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            try {
                final Encoder encoder = source.read();
                final File target = cache.get();
                final File directory = target.getParentFile();
                directory.mkdirs();
                temporary = File.createTempFile(ThumbnailCacheFiles.TEMPORARY, null, directory);
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    encode(file, encoder);
                }
                try (FileInputStream input = new FileInputStream(temporary)) {
                    rename.run(temporary, target);
                    temporary = null;
                    FileUtils.copy(input, output);
                }
            } catch (IOException | RuntimeException | Error failure) {
                VmThumbnailErrors.failPipe(descriptor, failure);
                throw failure;
            }
        } finally {
            if (temporary != null) cleanup.delete(temporary);
        }
    }
}
