/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import static com.android.providers.media.util.IsoInterface.*;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Iterative ISO traversal with one allocation and work budget for the file. */
final class IsoMetadataReader {
    private static final class Parent {
        final long end;
        final int type;
        Parent(long end, int type) {
            this.end = end;
            this.type = type;
        }
    }

    static List<IsoInterface.Box> read(FileDescriptor fd) throws IOException {
        try {
            final List<IsoInterface.Box> result = new ArrayList<>();
            final long end = Os.lseek(fd, 0, OsConstants.SEEK_END);
            if (end < 8) return result;
            seek(fd, 4);
            if (readInt(fd) != BOX_FTYP) return result;
            seek(fd, 0);
            final MetadataBudget budget = new MetadataBudget();
            final ArrayDeque<Parent> parents = new ArrayDeque<>();
            parents.push(new Parent(end, -1));
            while (!parents.isEmpty()) {
                final Parent parent = parents.peek();
                final long pos = Os.lseek(fd, 0, OsConstants.SEEK_CUR);
                if (parent.end - pos < 8) {
                    seek(fd, parent.end);
                    parents.pop();
                    continue;
                }
                budget.visit(parents.size());
                int header = 8;
                long length = Integer.toUnsignedLong(readInt(fd));
                final int type = readInt(fd);
                if (length == 0) {
                    length = parent.end - pos;
                } else if (length == 1) {
                    if (parent.end - pos < 16) throw new EOFException();
                    header = 16;
                    length = ((long) readInt(fd) << 32) | Integer.toUnsignedLong(readInt(fd));
                }
                if (length < header || length > parent.end - pos) {
                    throw new IOException("Invalid ISO box extent");
                }
                final IsoInterface.Box box = new IsoInterface.Box(type, new long[] {pos, length});
                if (type == BOX_UUID) {
                    if (length - header < 16) throw new EOFException();
                    box.uuid = new UUID(readLong(fd), readLong(fd));
                    header += 16;
                } else if (type == BOX_META && length != header) {
                    if (length - header < 8) throw new EOFException();
                    readInt(fd);
                    if (readInt(fd) != BOX_HDLR) header += 4;
                }
                box.headerSize = header;
                final long payload = length - header;
                if (type == BOX_XMP || (type == BOX_UUID && Objects.equals(box.uuid, XMP_UUID))) {
                    if (payload <= MAX_XMP_SIZE_BYTES) {
                        budget.retain(payload);
                        box.data = new byte[(int) payload];
                        seek(fd, pos + header);
                        readFully(fd, box.data);
                    }
                }
                if (type == BOX_XYZ && parent.type == BOX_ILST) {
                    box.range = new long[] {pos, header, pos + header, payload};
                }
                switch (type) {
                    case BOX_UUID:
                        if (!Objects.equals(box.uuid, XMP_UUID)) break;
                        // fall through
                    case BOX_META:
                    case BOX_HDLR:
                    case BOX_XYZ:
                    case BOX_XMP:
                    case BOX_LOCI:
                    case BOX_GPS:
                    case BOX_GPS0:
                        result.add(box);
                        break;
                }
                if (IsoInterface.isBoxParent(type)) {
                    seek(fd, pos + header);
                    parents.push(new Parent(pos + length, type));
                } else {
                    seek(fd, pos + length);
                }
            }
            return result;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    private static void seek(FileDescriptor fd, long pos) throws ErrnoException {
        Os.lseek(fd, pos, OsConstants.SEEK_SET);
    }

    private static int readInt(FileDescriptor fd) throws IOException, ErrnoException {
        final byte[] bytes = new byte[4];
        readFully(fd, bytes);
        return Memory.peekInt(bytes, 0, ByteOrder.BIG_ENDIAN);
    }

    private static long readLong(FileDescriptor fd) throws IOException, ErrnoException {
        return ((long) readInt(fd) << 32) | Integer.toUnsignedLong(readInt(fd));
    }

    private static void readFully(FileDescriptor fd, byte[] bytes)
            throws IOException, ErrnoException {
        int offset = 0;
        while (offset < bytes.length) {
            final int count = Os.read(fd, bytes, offset, bytes.length - offset);
            if (count <= 0) throw new EOFException();
            offset += count;
        }
    }
}
