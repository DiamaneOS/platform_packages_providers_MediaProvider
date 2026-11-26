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

package com.android.providers.media.scan;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import android.os.Handler;
import org.junit.Test;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class VmFillInsTest {
    @Test
    public void incomingFilesCannotExpandTheRunningRetrySet() throws Exception {
        final VmFillIns[] queue = new VmFillIns[1];
        final AtomicBoolean first = new AtomicBoolean(true);
        queue[0] = new VmFillIns(file -> {
            if (first.getAndSet(false)) {
                final Thread additions = new Thread(() -> {
                    for (int i = 0; i < 300; ++i) queue[0].add(new File("new-" + i));
                });
                additions.start();
                try {
                    additions.join();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return false;
        }, mock(Handler.class));
        for (int i = 0; i < 256; ++i) queue[0].add(new File("old-" + i));
        queue[0].run();
        final java.lang.reflect.Field files = VmFillIns.class.getDeclaredField("mFiles");
        files.setAccessible(true);
        assertTrue(((Map<?, ?>) files.get(queue[0])).size() <= 256);
    }
}
