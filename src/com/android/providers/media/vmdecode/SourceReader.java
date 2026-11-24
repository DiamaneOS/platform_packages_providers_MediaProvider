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

import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded source reads with admission reserved before submission. A stalled worker keeps its own descriptor,
 * count and byte reservation until it exits, including after its caller times out or is interrupted.
 */
final class SourceReader {
    static final int MAX_ABANDONED_PER_OWNER = 2;
    static final int MAX_ABANDONED = 8;
    private static final SourceBudget sBudget = new SourceBudget();
    private static final ThreadPoolExecutor sIo = new ThreadPoolExecutor(0, MAX_ABANDONED, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                final Thread t = new Thread(r, "VmDecodeIo");
                t.setDaemon(true);
                return t;
            });

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
        return SourceWork.call(sIo, sBudget, owner, 2L * limit, deadlineMillis, () -> {
            try (ParcelFileDescriptor input = pfd.dup()) {
                final byte[] bytes = readAll(input.getFileDescriptor(), offset, length, limit, prefix);
                input.checkError();
                return bytes;
            }
        }, bytes -> { });
    }

    /** Source preparation, including open and stat, shares admission and the entry deadline. */
    static Prepared prepare(java.io.File file, String principal, boolean probe) throws IOException {
        final int limit = probe ? de.diamaneos.sanitizer.decode.IDecodeService.MAX_PROBE_BYTES
                : de.diamaneos.sanitizer.decode.IDecodeService.MAX_BUFFERED_BYTES;
        return SourceWork.call(sIo, sBudget, principal, 2L * limit, 10_000, () -> {
            try (ParcelFileDescriptor opened = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                final StructStat stat;
                try {
                    stat = Os.fstat(opened.getFileDescriptor());
                } catch (ErrnoException e) {
                    throw new IOException("source stat failed", e);
                }
                final String identity = stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":"
                        + stat.st_mtim.tv_sec + "." + stat.st_mtim.tv_nsec + ":"
                        + stat.st_ctim.tv_sec + "." + stat.st_ctim.tv_nsec;
                final ParcelFileDescriptor prepared;
                if (onFuse(opened)) {
                    final byte[] bytes = readAll(opened.getFileDescriptor(), 0, -1, limit, probe);
                    prepared = VmDecode.memoryFile(bytes, bytes.length);
                } else {
                    prepared = opened.dup();
                }
                return new Prepared(prepared, identity);
            }
        }, Prepared::close);
    }

    static ParcelFileDescriptor openDisplay(android.content.ContentResolver resolver, android.net.Uri uri,
            String principal) throws IOException {
        final android.os.CancellationSignal signal = VmDecode.boundSignal() == null
                ? new android.os.CancellationSignal() : VmDecode.boundSignal();
        try {
            return SourceWork.call(sIo, sBudget, principal, 0, 10_000, () -> {
                signal.throwIfCanceled();
                final ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r", signal);
                if (file == null) throw new IOException("No display source");
                return file;
            }, ParcelFileDescriptor::close);
        } catch (IOException | RuntimeException e) {
            cancel(signal);
            throw e;
        }
    }

    static VmMediaRows.Source displaySource(android.content.ContentResolver resolver, android.net.Uri uri)
            throws IOException {
        final android.os.CancellationSignal signal = VmDecode.boundSignal();
        return SourceWork.call(sIo, sBudget, uri.getAuthority(), 0, 10_000,
                () -> VmMediaRows.forDisplay(resolver, uri, signal), source -> { });
    }

    static android.content.res.AssetFileDescriptor openTypedDisplay(android.content.ContentResolver resolver,
            android.net.Uri uri, android.os.Bundle options) throws IOException {
        final android.os.CancellationSignal signal = VmDecode.boundSignal() == null
                ? new android.os.CancellationSignal() : VmDecode.boundSignal();
        try {
            return SourceWork.call(sIo, sBudget, uri.getAuthority(), 0, 10_000, () -> {
                signal.throwIfCanceled();
                final android.content.res.AssetFileDescriptor asset = resolver.openTypedAssetFileDescriptor(
                        uri, "image/*", options, signal);
                if (asset == null) throw new IOException("No display thumbnail source");
                return asset;
            }, android.content.res.AssetFileDescriptor::close);
        } catch (IOException | RuntimeException e) {
            cancel(signal);
            throw e;
        }
    }

    static android.content.res.AssetFileDescriptor openPicker(android.content.Context context,
            android.net.Uri uri, String mime, android.os.Bundle options,
            android.os.CancellationSignal signal) throws IOException {
        return SourceWork.call(sIo, sBudget, uri.getAuthority(), 0, 10_000, () -> {
            signal.throwIfCanceled();
            final android.content.res.AssetFileDescriptor asset = context.getContentResolver()
                    .openTypedAssetFileDescriptor(uri, mime, options, signal);
            if (asset == null) throw new IOException("No picker asset");
            return asset;
        }, android.content.res.AssetFileDescriptor::close);
    }

    static VmMediaRows.Source pickerSource(android.content.Context context, android.net.Uri uri)
            throws IOException {
        return SourceWork.call(sIo, sBudget, uri.getAuthority(), 0, 10_000,
                () -> VmMediaRows.picker(context, uri), value -> { });
    }

    static void verifyPicker(android.content.Context context, android.net.Uri uri,
            VmMediaRows.Source source) throws IOException {
        SourceWork.call(sIo, sBudget, uri.getAuthority(), 0, 10_000, () -> {
            VmMediaRows.verify(context, uri, source);
            return Boolean.TRUE;
        }, value -> { });
    }

    static ParcelFileDescriptor prepareAsset(android.content.res.AssetFileDescriptor asset,
            String principal) throws IOException {
        final long offset = asset.getStartOffset();
        final long length = asset.getDeclaredLength();
        return SourceWork.call(sIo, sBudget, principal,
                2L * de.diamaneos.sanitizer.decode.IDecodeService.MAX_BUFFERED_BYTES, 10_000, () -> {
            try (ParcelFileDescriptor input = asset.getParcelFileDescriptor().dup()) {
                final StructStat stat;
                try {
                    stat = Os.fstat(input.getFileDescriptor());
                } catch (ErrnoException e) {
                    throw new IOException("Asset stat failed", e);
                }
                final boolean regular = OsConstants.S_ISREG(stat.st_mode);
                if (offset < 0 || length < -1 || (!regular && offset != 0)
                        || (regular && (offset > stat.st_size
                                || (length >= 0 && length > stat.st_size - offset)))) {
                    throw new VmDecodeRejectedException("Asset extent is invalid", true);
                }
                if (!onFuse(input) && ((regular && offset == 0
                        && (length < 0 || length == stat.st_size)) || (!regular && length < 0))) {
                    return input.dup();
                }
                final byte[] bytes = readAll(input.getFileDescriptor(), offset, length,
                        de.diamaneos.sanitizer.decode.IDecodeService.MAX_BUFFERED_BYTES, false);
                input.checkError();
                return VmDecode.memoryFile(bytes, bytes.length);
            }
        }, ParcelFileDescriptor::close);
    }

    private static final ThreadPoolExecutor sCancel = new ThreadPoolExecutor(0, 2, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                final Thread thread = new Thread(r, "VmSourceCancel");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    static void cancel(android.os.CancellationSignal signal) {
        sCancel.execute(signal::cancel);
    }

    static <T> T metadata(String principal, long timeout, SourceWork.Work<T> work) throws IOException {
        return SourceWork.call(sIo, sBudget, principal, 32L * 1024 * 1024, timeout, work, value -> { });
    }

    static final class Prepared implements AutoCloseable {
        final ParcelFileDescriptor file;
        final String identity;

        Prepared(ParcelFileDescriptor file, String identity) {
            this.file = file;
            this.identity = identity;
        }

        @Override
        public void close() throws IOException {
            file.close();
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
