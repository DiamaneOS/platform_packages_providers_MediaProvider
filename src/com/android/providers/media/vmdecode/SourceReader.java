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

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads from a source that may stall for ever (a file on FUSE, which an app can serve through a StorageManager proxy
 * file descriptor) on a thread of its own, with a real deadline. A read that misses its deadline is abandoned: its
 * thread stays blocked until the source answers or goes away, and counts against the owner of the bytes. An owner
 * with {@link #MAX_ABANDONED_PER_OWNER} abandoned reads, or {@link #MAX_ABANDONED} in all, gets no new reads until
 * those end: its requests fail at once, so stalled sources can never take all threads.
 */
final class SourceReader {
    static final int MAX_ABANDONED_PER_OWNER = 2;
    static final int MAX_ABANDONED = 8;
    private static final int MAX_ACTIVE = 4;
    private static final int RUNNING = 0;
    private static final int FINISHED = 1;
    private static final int ABANDONED = 2;

    private static final ThreadPoolExecutor sIo = new ThreadPoolExecutor(0, MAX_ACTIVE + MAX_ABANDONED, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                final Thread t = new Thread(r, "VmDecodeIo");
                t.setDaemon(true);
                return t;
            });

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static final Map<String, Integer> sAbandoned = new HashMap<>();
    @GuardedBy("sLock")
    private static int sAbandonedAll;

    private SourceReader() {
    }

    /**
     * Whether the descriptor's file lies on FUSE. Found from /proc without touching the file. When that cannot be told,
     * it counts as FUSE, so it is read the careful way.
     */
    static boolean onFuse(ParcelFileDescriptor pfd) {
        try {
            final String path = Os.readlink("/proc/self/fd/" + pfd.getFd());
            final String mountinfo = new String(Files.readAllBytes(Paths.get("/proc/self/mountinfo")),
                    StandardCharsets.UTF_8);
            return Mounts.isFuse(path, mountinfo);
        } catch (ErrnoException | IOException | RuntimeException e) {
            return true;
        }
    }

    /**
     * The bytes of {@code pfd} (all of it, or {@code length} bytes from {@code offset} when length is not negative),
     * at most {@code limit}. A longer source is cut at {@code limit} when {@code prefix}, else refused. Done on a
     * thread of its own and given up after {@code deadlineMillis}.
     */
    static byte[] read(ParcelFileDescriptor pfd, long offset, long length, int limit, boolean prefix,
            @Nullable String owner, long deadlineMillis) throws IOException {
        final String key = owner == null ? "" : owner;
        synchronized (sLock) {
            if (sAbandoned.getOrDefault(key, 0) >= MAX_ABANDONED_PER_OWNER || sAbandonedAll >= MAX_ABANDONED) {
                throw new VmDecodeRejectedException("sources of " + key + " stall");
            }
        }
        // RUNNING until the read ends (FINISHED) or the caller gives up on it (ABANDONED), whichever is first.
        final AtomicInteger state = new AtomicInteger(RUNNING);
        final Future<byte[]> future;
        try {
            future = sIo.submit(() -> {
                try {
                    return readAll(pfd.getFileDescriptor(), offset, length, limit, prefix);
                } finally {
                    if (!state.compareAndSet(RUNNING, FINISHED)) {
                        synchronized (sLock) {
                            sAbandonedAll--;
                            final int left = sAbandoned.getOrDefault(key, 1) - 1;
                            if (left <= 0) {
                                sAbandoned.remove(key);
                            } else {
                                sAbandoned.put(key, left);
                            }
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            throw new VmDecodeUnavailableException("no reader thread free", e);
        }
        try {
            return future.get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (sLock) {
                if (state.compareAndSet(RUNNING, ABANDONED)) {
                    sAbandonedAll++;
                    sAbandoned.merge(key, 1, Integer::sum);
                }
            }
            throw new VmDecodeRejectedException("source too slow");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VmDecodeUnavailableException("interrupted", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("source not readable", cause);
        }
    }

    private static byte[] readAll(FileDescriptor fd, long offset, long length, int limit, boolean prefix)
            throws IOException {
        try {
            final StructStat st = Os.fstat(fd);
            final boolean regular = OsConstants.S_ISREG(st.st_mode);
            long want = length >= 0 ? length : (regular ? st.st_size - offset : -1);
            if (want > limit || want < -1) {
                if (!prefix) {
                    throw new VmDecodeRejectedException("source longer than " + limit);
                }
                want = limit;
            }
            final int cap = want >= 0 ? (int) want : limit;
            final byte[] buffer = new byte[cap];
            int done = 0;
            while (done < cap) {
                final int n = regular
                        ? Os.pread(fd, buffer, done, Math.min(65536, cap - done), offset + done)
                        : Os.read(fd, buffer, done, Math.min(65536, cap - done));
                if (n <= 0) {
                    break;
                }
                done += n;
            }
            if (want < 0 && done == cap && !prefix) {
                // A stream that filled the whole limit may be longer: refuse it like a long file.
                final byte[] one = new byte[1];
                if (Os.read(fd, one, 0, 1) > 0) {
                    throw new VmDecodeRejectedException("source longer than " + limit);
                }
            }
            if (want >= 0 && done < want) {
                throw new VmDecodeRejectedException("source shorter than declared");
            }
            return done == cap ? buffer : Arrays.copyOf(buffer, done);
        } catch (ErrnoException e) {
            throw new IOException("source not readable", e);
        }
    }
}
