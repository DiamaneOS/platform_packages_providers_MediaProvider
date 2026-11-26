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

/** A finite or infinite sequence uses one frame list regardless of the requested loop count. */
final class FrameClock {
    private final int mCount;
    private final int mLoops;
    private int mFrame;
    private int mCompleted;
    private boolean mFinished;

    FrameClock(int count, int loops) {
        if (count < 1 || loops < 0) throw new IllegalArgumentException("Invalid frame sequence");
        mCount = count;
        mLoops = loops;
    }

    int frame() { return mFrame; }

    void reset() {
        mFrame = 0;
        mCompleted = 0;
        mFinished = false;
    }

    boolean next() {
        if (mFinished) return false;
        if (++mFrame < mCount) return true;
        ++mCompleted;
        if (mLoops != 0 && mCompleted >= mLoops) {
            mFrame = mCount - 1;
            mFinished = true;
            return false;
        }
        mFrame = 0;
        return true;
    }

    static int delay(int duration) {
        return duration <= 10 ? 100 : duration;
    }
}
