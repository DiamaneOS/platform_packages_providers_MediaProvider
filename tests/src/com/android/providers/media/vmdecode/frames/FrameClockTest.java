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

import static org.junit.Assert.*;
import org.junit.Test;

public class FrameClockTest {
    @Test
    public void largeFiniteLoopCountsStopOnTheLastFrame() {
        final FrameClock clock = new FrameClock(2, 5000);
        for (int i = 0; i < 9999; ++i) assertTrue(clock.next());
        assertFalse(clock.next());
        assertEquals(1, clock.frame());
        assertFalse(clock.next());
    }

    @Test
    public void infiniteLoopsKeepOneListAndShortDelaysMatchTheGate() {
        final FrameClock clock = new FrameClock(2, 0);
        for (int i = 0; i < 10000; ++i) assertTrue(clock.next());
        assertEquals(100, FrameClock.delay(0));
        assertEquals(100, FrameClock.delay(10));
        assertEquals(11, FrameClock.delay(11));
    }
}
