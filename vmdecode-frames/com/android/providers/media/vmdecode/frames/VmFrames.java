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
import android.graphics.ColorSpace;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Option;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * An animation the decode service made in its VM, on its way to the pickers' image loader (Glide): MediaProvider
 * writes the checked frames in this simple form (a header, each frame's time, then raw premultiplied RGBA frames)
 * and the picker's Glide turns it into a drawable that plays them. Nothing here decodes an image file. The reader
 * checks every number before it allocates, so a broken stream ends in a failed load.
 *
 * <p>Shared by MediaProvider's picker and the photo picker app (filegroup mediaprovider-vmdecode-frames).
 */
public final class VmFrames {
    private VmFrames() {}

    /** Ask the picker's fetcher for an animation instead of a still image (GIF and animated WebP previews). */
    public static final Option<Boolean> ANIMATE =
            Option.memory("com.android.providers.media.vmdecode.frames.ANIMATE", false);

    private static final int MAGIC = 0x464d5644; // "DVMF"
    private static final int MAX_FRAMES = 1024;
    private static final int MAX_BYTES = 32 << 20;
    private static final int MAX_DIMENSION = 65535;

    /** The header's length for count frames: the frames' premultiplied RGBA pixels follow it, frame after frame. */
    public static int headerBytes(int count) {
        return 24 + 4 * count;
    }

    /**
     * Writes the header at the start of out, as MediaProvider makes the stream: all frames of one size and one colour
     * space. The caller puts the pixels after it, so the frames are held once.
     */
    public static void putHeader(@NonNull byte[] out, int w, int h, int count, int loops, boolean displayP3,
            @NonNull int[] durations) {
        final ByteBuffer header = ByteBuffer.wrap(out, 0, headerBytes(count)).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC).putInt(w).putInt(h).putInt(count).putInt(loops).putInt(displayP3 ? 1 : 0);
        for (int d : durations) {
            header.putInt(d);
        }
    }

    /** True when the stream starts with this form (the stream must support mark, as Glide's do). */
    public static boolean isFrames(@NonNull InputStream in) throws IOException {
        in.mark(4);
        try {
            final byte[] b = new byte[4];
            return new DataInputStream(in).read(b) == 4
                    && ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt() == MAGIC;
        } finally {
            in.reset();
        }
    }

    /**
     * The frames read and checked: their bitmaps, shared by every drawable made from them (newDrawable), and the
     * memory they hold.
     */
    public static final class Frames {
        private final Resources mResources;
        private final Bitmap[] mBitmaps;
        private final int[] mDurations;
        private final int mLoops;
        public final long bytes;

        Frames(@Nullable Resources res, Bitmap[] bitmaps, int[] durations, int loops) {
            mResources = res;
            mBitmaps = bitmaps;
            mDurations = durations;
            mLoops = loops;
            bytes = (long) bitmaps.length * bitmaps[0].getAllocationByteCount();
        }

        /** A drawable of its own that plays the frames while it is shown. */
        public @NonNull Drawable newDrawable() {
            return new VmFrameDrawable(mBitmaps, mDurations, mLoops);
        }
    }

    /** The frames of a stream, or an exception for anything outside the limits. */
    public static @NonNull Frames read(@Nullable Resources res, @NonNull InputStream in) throws IOException {
        final DataInputStream data = new DataInputStream(in);
        final byte[] head = new byte[24];
        data.readFully(head);
        final ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        final int magic = b.getInt(), w = b.getInt(), h = b.getInt(), count = b.getInt(), loops = b.getInt();
        final int space = b.getInt();
        if (magic != MAGIC || w < 1 || h < 1 || w > MAX_DIMENSION || h > MAX_DIMENSION || count < 1
                || count > MAX_FRAMES || (long) count * w * h * 4 > MAX_BYTES || loops < 0 || loops > 65535
                || (space != 0 && space != 1)) {
            throw new IOException("not a valid frame stream");
        }
        final byte[] times = new byte[4 * count];
        data.readFully(times);
        final ByteBuffer t = ByteBuffer.wrap(times).order(ByteOrder.LITTLE_ENDIAN);
        final ColorSpace cs = ColorSpace.get(space == 1 ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB);
        final Bitmap[] bitmaps = new Bitmap[count];
        final int[] durations = new int[count];
        final byte[] pixels = new byte[w * h * 4];
        for (int i = 0; i < count; i++) {
            final int duration = t.getInt();
            if (duration < 0 || duration > 65535) throw new IOException("Invalid frame duration");
            durations[i] = FrameClock.delay(duration);
            data.readFully(pixels);
            for (int pixel = 0; pixel < pixels.length; pixel += 4) {
                final int alpha = pixels[pixel + 3] & 255;
                if ((pixels[pixel] & 255) > alpha || (pixels[pixel + 1] & 255) > alpha
                        || (pixels[pixel + 2] & 255) > alpha) {
                    throw new IOException("Non-premultiplied picker frame");
                }
            }
            bitmaps[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, cs);
            bitmaps[i].copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        }
        return new Frames(res, bitmaps, durations, loops);
    }

    /** Glide's decoder for this form, registered before the bitmap decoders. */
    public static final class Decoder implements ResourceDecoder<InputStream, Drawable> {
        private final Resources mResources;

        public Decoder(@Nullable Resources resources) {
            mResources = resources;
        }

        @Override
        public boolean handles(@NonNull InputStream source, @NonNull Options options) throws IOException {
            return isFrames(source);
        }

        @Override
        public Resource<Drawable> decode(@NonNull InputStream source, int width, int height,
                @NonNull Options options) throws IOException {
            final Frames frames = read(mResources, source);
            // Each view gets a drawable of its own (one drawable plays in one view only), over the same bitmaps.
            // The memory cache counts every frame.
            return new Resource<Drawable>() {
                @NonNull
                @Override
                public Class<Drawable> getResourceClass() {
                    return Drawable.class;
                }

                @NonNull
                @Override
                public Drawable get() {
                    return frames.newDrawable();
                }

                @Override
                public int getSize() {
                    return (int) Math.min(Integer.MAX_VALUE, frames.bytes);
                }

                @Override
                public void recycle() {
                    // The frames go with the drawable.
                }
            };
        }
    }
}
