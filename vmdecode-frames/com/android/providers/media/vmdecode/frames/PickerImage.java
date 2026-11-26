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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import java.io.IOException;

/** Checked pixels handed directly to Glide, retaining their colour space and optional gain map. */
public final class PickerImage implements AutoCloseable {
    private Bitmap mBitmap;
    private ParcelFileDescriptor mFrames;

    public PickerImage(Bitmap bitmap, ParcelFileDescriptor frames) throws IOException {
        if ((bitmap == null) == (frames == null)) throw new IOException("Invalid picker result");
        if (bitmap != null && ((long) bitmap.getWidth() * bitmap.getHeight() > 8 * 1024 * 1024
                || bitmap.getWidth() < 1 || bitmap.getHeight() < 1)) {
            bitmap.recycle();
            throw new IOException("Picker bitmap exceeds the checked limit");
        }
        mBitmap = bitmap;
        mFrames = frames;
    }

    public synchronized boolean isStill() {
        return mBitmap != null;
    }

    private synchronized Bitmap takeBitmap() throws IOException {
        if (mBitmap == null) throw new IOException("Picker pixels already consumed");
        final Bitmap bitmap = mBitmap;
        mBitmap = null;
        return bitmap;
    }

    private synchronized VmFrames.Frames takeFrames(Resources resources) throws IOException {
        if (mFrames == null) throw new IOException("Picker frames already consumed");
        final ParcelFileDescriptor frames = mFrames;
        mFrames = null;
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                new ParcelFileDescriptor.AutoCloseInputStream(frames)) {
            return VmFrames.read(resources, input);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
        if (mFrames != null) {
            mFrames.close();
            mFrames = null;
        }
    }

    private static int bitmapBytes(Bitmap bitmap) {
        long bytes = bitmap.getAllocationByteCount();
        if (android.os.Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bytes += bitmap.getGainmap().getGainmapContents().getAllocationByteCount();
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    public static final class BitmapDecoder implements ResourceDecoder<PickerImage, Bitmap> {
        @Override
        public boolean handles(PickerImage source, Options options) {
            return source.isStill();
        }

        @Override
        public Resource<Bitmap> decode(PickerImage source, int width, int height, Options options)
                throws IOException {
            final Bitmap bitmap = source.takeBitmap();
            return new Resource<Bitmap>() {
                public Class<Bitmap> getResourceClass() { return Bitmap.class; }
                public Bitmap get() { return bitmap; }
                public int getSize() { return bitmapBytes(bitmap); }
                public void recycle() { bitmap.recycle(); }
            };
        }
    }

    public static final class DrawableDecoder implements ResourceDecoder<PickerImage, Drawable> {
        private final Resources mResources;
        public DrawableDecoder(Resources resources) { mResources = resources; }
        public boolean handles(PickerImage source, Options options) { return true; }

        public Resource<Drawable> decode(PickerImage source, int width, int height, Options options)
                throws IOException {
            if (source.isStill()) {
                final Bitmap bitmap = source.takeBitmap();
                return new Resource<Drawable>() {
                    public Class<Drawable> getResourceClass() { return Drawable.class; }
                    public Drawable get() { return new BitmapDrawable(mResources, bitmap); }
                    public int getSize() { return bitmapBytes(bitmap); }
                    public void recycle() { bitmap.recycle(); }
                };
            }
            final VmFrames.Frames frames = source.takeFrames(mResources);
            return new Resource<Drawable>() {
                public Class<Drawable> getResourceClass() { return Drawable.class; }
                public Drawable get() { return frames.newDrawable(); }
                public int getSize() { return (int) frames.bytes; }
                public void recycle() { }
            };
        }
    }
}
