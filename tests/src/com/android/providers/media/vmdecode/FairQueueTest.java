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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Per-owner queues served in turn, with caps. Plain Java, no device state needed. */
@RunWith(JUnit4.class)
public class FairQueueTest {

    @Test
    public void ownersTakeTurns() {
        final FairQueue<String> q = new FairQueue<>(8, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(q.offer("flood", "f" + i));
        }
        assertTrue(q.offer("victim", "v0"));
        // The flood went first, then the victim gets the next turn, not the flood's remaining four.
        final FairQueue.Owned<String> a = q.poll();
        assertEquals("flood", a.owner);
        final FairQueue.Owned<String> b = q.poll();
        assertEquals("victim", b.owner);
        assertEquals("v0", b.item);
    }

    @Test
    public void runningShareHoldsWhileOthersWait() {
        final FairQueue<String> q = new FairQueue<>(8, 2);
        q.offer("flood", "f0");
        q.offer("flood", "f1");
        q.offer("flood", "f2");
        assertEquals("f0", q.poll().item);
        assertEquals("f1", q.poll().item);
        q.offer("victim", "v0");
        // The flood runs two already, its share: the victim is served first.
        assertEquals("v0", q.poll().item);
        // Nobody else waits: the flood may use a free thread again.
        assertEquals("f2", q.poll().item);
        assertNull(q.poll());
        assertEquals(3, q.running("flood"));
        q.done("flood");
        assertEquals(2, q.running("flood"));
    }

    @Test
    public void perOwnerQueueCap() {
        final FairQueue<String> q = new FairQueue<>(2, 1);
        assertTrue(q.offer("a", "1"));
        assertTrue(q.offer("a", "2"));
        assertFalse(q.offer("a", "3"));
        // Another owner is not affected by a's full queue.
        assertTrue(q.offer("b", "1"));
        assertEquals(2, q.queued("a"));
    }

    @Test
    public void removeOnlyBeforeStart() {
        final FairQueue<String> q = new FairQueue<>(4, 1);
        q.offer("a", "1");
        q.offer("a", "2");
        assertEquals("1", q.poll().item);
        assertFalse(q.remove("a", "1"));
        assertTrue(q.remove("a", "2"));
        assertEquals(0, q.queued("a"));
        assertNull(q.poll());
    }
}
