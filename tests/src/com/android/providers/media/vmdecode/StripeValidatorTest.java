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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.android.providers.media.vmdecode.StripeValidator.BadReplyException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The checks on what the decode service sends back. Plain Java, no device state needed. */
@RunWith(JUnit4.class)
public class StripeValidatorTest {

    @Test
    public void headerWithinBox() throws Exception {
        assertEquals(3, StripeValidator.checkHeader(100, 50, 20, 0, 100, 100));
        assertEquals(1, StripeValidator.checkHeader(1, 1, 1, 0, 1, 1));
        // Turned images are checked against the box the other way round.
        assertEquals(1, StripeValidator.checkHeader(50, 100, 100, 90, 100, 50));
    }

    @Test
    public void headerRejectsBadValues() {
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(0, 10, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, -1, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(101, 10, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(50, 100, 10, 90, 99, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 1, 45, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 0, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 11, 0, 100, 100));
        // Too many pixels even within the box.
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(4000, 4000, 1, 0, 4000, 4000));
        // A stripe over the byte limit.
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(1000, 1000, 100, 0, 1000, 1000));
        // Sizes past the dimension limit, where width * height would overflow an int.
        assertThrows(BadReplyException.class,
                () -> StripeValidator.checkHeader(70000, 70000, 1, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 1, 0, 0, 10));
    }

    @Test
    public void stripeLengths() throws Exception {
        assertEquals(20 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 0));
        assertEquals(20 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 1));
        assertEquals(10 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 2));
        assertThrows(BadReplyException.class, () -> StripeValidator.stripeLength(100, 50, 20, 3));
        assertThrows(BadReplyException.class, () -> StripeValidator.stripeLength(100, 50, 20, -1));
    }

    @Test
    public void putStripeChecksLengthAndClampsToAlpha() throws Exception {
        final byte[] frame = new byte[2 * 2 * 4];
        final byte[] first = {(byte) 200, 10, 20, (byte) 100, 1, 2, 3, (byte) 255};
        StripeValidator.putStripe(frame, 2, 2, 1, 0, first);
        assertArrayEquals(new byte[] {(byte) 100, 10, 20, (byte) 100, 1, 2, 3, (byte) 255},
                java.util.Arrays.copyOfRange(frame, 0, 8));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, new byte[7]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, new byte[9]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, null));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 2, new byte[8]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(new byte[3], 2, 2, 1, 0, first));
    }

    @Test
    public void infoChecks() throws Exception {
        StripeValidator.checkInfo(1, 640, 480);
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(11, 640, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(-1, 640, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 0, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 640, 65536));
    }

    @Test
    public void animationOnlyWhereFormatsAnimate() throws Exception {
        StripeValidator.checkInfo(3, 10, 10, true);
        StripeValidator.checkInfo(4, 10, 10, true);
        StripeValidator.checkInfo(2, 10, 10, true);
        StripeValidator.checkInfo(1, 10, 10, false);
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 10, 10, true));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(5, 10, 10, true));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(0, 10, 10, true));
    }

    @Test
    public void dateTaken() throws Exception {
        final long now = 1794873000000L;
        assertEquals(-1, StripeValidator.checkDateTaken(-1, false, now));
        assertEquals(now, StripeValidator.checkDateTaken(now, true, now));
        assertEquals(now + 86400000L, StripeValidator.checkDateTaken(now + 86400000L, false, now));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkDateTaken(now + 86400001L, false, now));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkDateTaken(-1, true, now));
        // Dates before 1970 are real dates: only -1 means none.
        assertEquals(-2, StripeValidator.checkDateTaken(-2, false, now));
        assertThrows(BadReplyException.class,
                () -> StripeValidator.checkDateTaken(-2208988800000L - 86400001L, false, now));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkDateTaken(Long.MAX_VALUE, false, now));
    }

    @Test
    public void textRules() throws Exception {
        assertEquals("", StripeValidator.checkText(null, 10));
        assertEquals("Holiday \u00e9t\u00e9 \ud83d\ude00", StripeValidator.checkText("Holiday \u00e9t\u00e9 \ud83d\ude00", 20));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\nb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u0085b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u009bb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u202eb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u2066b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u2028b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u2029b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u061cb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u200bb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u200db", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u2060b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\u180eb", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\ud800b", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("a\udc00", 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkText("abcdefghijk", 10));
    }

    @Test
    public void numberAndIdRules() throws Exception {
        assertEquals("1/250", StripeValidator.checkNumber("1/250"));
        assertEquals("2.8", StripeValidator.checkNumber("2.8"));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkNumber("1e9"));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkNumber("123456789012345678901234567890123"));
        assertEquals("xmp.did:0A1b-2_3", StripeValidator.checkId("xmp.did:0A1b-2_3"));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkId("../x"));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkId("a b"));
    }

    @Test
    public void orientationAndLocation() throws Exception {
        assertEquals(-1, StripeValidator.checkOrientation(-1));
        assertEquals(270, StripeValidator.checkOrientation(270));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkOrientation(45));
        StripeValidator.checkLocation(Double.NaN, Double.NaN);
        StripeValidator.checkLocation(52.5, 13.4);
        assertThrows(BadReplyException.class, () -> StripeValidator.checkLocation(91, 0));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkLocation(Double.NaN, 10));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkLocation(0, Double.POSITIVE_INFINITY));
    }
}
