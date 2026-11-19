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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.AnimationDrawable;

import com.android.providers.media.vmdecode.frames.VmFrames;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** The frame stream MediaProvider hands the pickers: read back as written, refused outside its limits. */
@RunWith(JUnit4.class)
public class VmFramesTest {
    private static byte[] stream(int w, int h, int count, int loops, int[] durations) {
        final byte[] out = new byte[VmFrames.headerBytes(count) + count * w * h * 4];
        VmFrames.putHeader(out, w, h, count, loops, false, durations);
        for (int i = VmFrames.headerBytes(count); i < out.length; i++) {
            out[i] = (byte) 0x40; // grey at a quarter alpha, premultiplied
        }
        return out;
    }

    @Test
    public void framesPlayAsWritten() throws IOException {
        final byte[] s = stream(2, 1, 3, 2, new int[] {100, 50, 20});
        assertTrue(VmFrames.isFrames(new ByteArrayInputStream(s)));
        final VmFrames.Frames f = VmFrames.read(null, new ByteArrayInputStream(s));
        assertEquals(3L * 2 * 1 * 4, f.bytes);
        final AnimationDrawable a = (AnimationDrawable) f.newDrawable();
        // Two plays written out, then it stops on the last frame.
        assertEquals(6, a.getNumberOfFrames());
        assertTrue(a.isOneShot());
        assertEquals(50, a.getDuration(1));
        // Every view gets its own drawable over the same frames.
        assertNotSame(a, f.newDrawable());
    }

    @Test
    public void foreverLoops() throws IOException {
        final AnimationDrawable a = (AnimationDrawable) VmFrames.read(null,
                new ByteArrayInputStream(stream(1, 1, 2, 0, new int[] {100, 100}))).newDrawable();
        assertEquals(2, a.getNumberOfFrames());
        assertTrue(!a.isOneShot());
    }

    @Test
    public void brokenStreamsAreRefused() {
        final byte[] tooMany = stream(1, 1, 1, 0, new int[] {100});
        tooMany[12] = 0x01;
        tooMany[13] = 0x04; // 1025 frames
        assertThrows(IOException.class, () -> VmFrames.read(null, new ByteArrayInputStream(tooMany)));
        final byte[] tooBig = stream(1, 1, 1, 0, new int[] {100});
        tooBig[4] = 0x00;
        tooBig[5] = 0x10; // 4096 x 4096 is above 32 MiB
        tooBig[8] = 0x00;
        tooBig[9] = 0x10;
        assertThrows(IOException.class, () -> VmFrames.read(null, new ByteArrayInputStream(tooBig)));
        final byte[] badSpace = stream(1, 1, 1, 0, new int[] {100});
        badSpace[20] = 2;
        assertThrows(IOException.class, () -> VmFrames.read(null, new ByteArrayInputStream(badSpace)));
        final byte[] cut = java.util.Arrays.copyOf(stream(2, 2, 2, 0, new int[] {100, 100}), 40);
        assertThrows(IOException.class, () -> VmFrames.read(null, new ByteArrayInputStream(cut)));
        assertTrue(!isFrames(new byte[] {1, 2, 3, 4, 5}));
    }

    private static boolean isFrames(byte[] b) {
        try {
            return VmFrames.isFrames(new ByteArrayInputStream(b));
        } catch (IOException e) {
            return false;
        }
    }
}
