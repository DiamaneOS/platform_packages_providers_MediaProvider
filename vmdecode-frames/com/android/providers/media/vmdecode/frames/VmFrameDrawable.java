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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Draws checked frames with the same short-delay policy as the framework gate. */
final class VmFrameDrawable extends Drawable implements Animatable, Runnable {
    private final Bitmap[] mFrames;
    private final int[] mDurations;
    private final FrameClock mClock;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean mRunning;

    VmFrameDrawable(Bitmap[] frames, int[] durations, int loops) {
        mFrames = frames;
        mDurations = durations;
        mClock = new FrameClock(frames.length, loops);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawBitmap(mFrames[mClock.frame()], null, getBounds(), mPaint);
    }

    @Override
    public void start() {
        if (mRunning) return;
        mClock.reset();
        mRunning = true;
        invalidateSelf();
        scheduleSelf(this, SystemClock.uptimeMillis() + FrameClock.delay(mDurations[mClock.frame()]));
    }

    @Override
    public void stop() {
        mRunning = false;
        unscheduleSelf(this);
    }

    @Override
    public boolean isRunning() { return mRunning; }

    @Override
    public void run() {
        if (!mRunning) return;
        if (!mClock.next()) {
            mRunning = false;
            return;
        }
        invalidateSelf();
        scheduleSelf(this, SystemClock.uptimeMillis() + FrameClock.delay(mDurations[mClock.frame()]));
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        if (!visible) stop();
        else {
            if (restart) stop();
            if (!mRunning) start();
        }
        return changed;
    }

    @Override
    public int getIntrinsicWidth() { return mFrames[0].getWidth(); }
    @Override
    public int getIntrinsicHeight() { return mFrames[0].getHeight(); }
    @Override
    public void setAlpha(int alpha) { mPaint.setAlpha(alpha); invalidateSelf(); }
    @Override
    public void setColorFilter(ColorFilter filter) { mPaint.setColorFilter(filter); invalidateSelf(); }
    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
