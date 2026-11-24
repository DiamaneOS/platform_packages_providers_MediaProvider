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

import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Admission and resource transfer for source work that may outlive its waiting caller. */
final class SourceWork {
    interface Work<T> { T run() throws IOException; }
    interface Dispose<T> { void close(T value) throws IOException; }

    static <T> T call(ThreadPoolExecutor pool, SourceBudget budget, String principal, long bytes,
            long timeout, Work<T> work, Dispose<T> dispose) throws IOException {
        VmDecode.checkBoundCancellation();
        final long bound = VmDecode.boundEnd();
        final long end = Math.min(SystemClock.uptimeMillis() + timeout,
                bound == 0 ? Long.MAX_VALUE : bound);
        if (end <= SystemClock.uptimeMillis()) throw unavailable("source deadline expired", null);
        final SourceBudget.Ticket ticket = budget.reserve(principal, bytes);
        if (ticket == null) throw unavailable("source admission full", null);
        final CompletableFuture<T> result = new CompletableFuture<>();
        try {
            pool.execute(() -> {
                try {
                    if (result.isCancelled()) return;
                    if (SystemClock.uptimeMillis() >= end) {
                        throw new VmDecodeUnavailableException("source deadline expired before start");
                    }
                    final T value = work.run();
                    if (!result.complete(value)) close(dispose, value);
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    ticket.close();
                }
            });
        } catch (RuntimeException e) {
            ticket.close();
            throw unavailable("source workers full", e);
        }
        try {
            while (true) {
                VmDecode.checkBoundCancellation();
                final long left = end - SystemClock.uptimeMillis();
                if (left <= 0) throw new TimeoutException();
                try {
                    return result.get(Math.min(left, 50), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (end <= SystemClock.uptimeMillis()) throw e;
                }
            }
        } catch (TimeoutException e) {
            abandon(result, dispose);
            throw unavailable("source deadline expired", e);
        } catch (InterruptedException e) {
            abandon(result, dispose);
            Thread.currentThread().interrupt();
            throw unavailable("source wait interrupted", e);
        } catch (android.os.OperationCanceledException e) {
            abandon(result, dispose);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException
                    && VmDecodeUnavailableException.isUnavailable((IOException) e.getCause())) {
                VmDecode.countUnavailable();
            }
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw new IOException("source work failed", e.getCause());
        }
    }

    private static <T> void abandon(CompletableFuture<T> result, Dispose<T> dispose) {
        if (!result.cancel(false)) result.thenAccept(value -> close(dispose, value));
    }

    private static <T> void close(Dispose<T> dispose, T value) {
        try {
            dispose.close(value);
        } catch (IOException ignored) {
            // The caller has already left and no resource is published.
        }
    }

    private static VmDecodeUnavailableException unavailable(String message, Throwable cause) {
        VmDecode.countUnavailable();
        return new VmDecodeUnavailableException(message, cause);
    }
}
