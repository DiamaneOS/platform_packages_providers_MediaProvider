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

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.*;

public class SourceWorkTest {
    @Test
    public void sourcePreparationSharesTheCallerDeadlineAndDisposesLateResults() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch disposed = new CountDownLatch(1);
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 2, 1, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    final Thread thread = new Thread(task);
                    thread.setDaemon(true);
                    return thread;
                });
        final SourceBudget budget = new SourceBudget();
        try {
            try (VmDecode.Bound ignored = VmDecode.boundWaits(40)) {
                assertThrows(VmDecodeUnavailableException.class, () -> SourceWork.call(pool, budget,
                        "app", 1, 10000, () -> {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                throw new java.io.IOException(e);
                            }
                            return new Object();
                        }, value -> disposed.countDown()));
            }
            final SourceBudget.Ticket second = budget.reserve("app", 1);
            assertNotNull(second);
            assertNull(budget.reserve("app", 1));
            release.countDown();
            assertTrue(disposed.await(1, TimeUnit.SECONDS));
            second.close();
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }
    @Test
    public void cancelledEntryDoesNotStartSourceWork() throws Exception {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS,
                new SynchronousQueue<>());
        final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        signal.cancel();
        try (VmDecode.Bound ignored = VmDecode.boundWaits(1000, signal)) {
            assertThrows(android.os.OperationCanceledException.class,
                    () -> SourceWork.call(pool, new SourceBudget(), "app", 1, 1000,
                            () -> { throw new AssertionError("cancelled source opened"); }, value -> {}));
        } finally {
            pool.shutdown();
        }
    }
}
