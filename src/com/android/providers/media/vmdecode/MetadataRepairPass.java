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

import android.database.sqlite.SQLiteException;
import android.os.OperationCanceledException;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Separates database snapshots and short conditional writes from all file and VM work. */
public final class MetadataRepairPass {
    public static final String PENDING = "media_type=1 AND (_special_format IS NULL"
            + " OR width IS NULL OR height IS NULL)";
    static final int PAGE = 32;
    static final long PASS_MS = 5000;
    static final long ROW_MS = 500;

    public static final class Row {
        public final long id;
        public final String path;
        public final String principal;
        public final int user;
        public final long generation;
        public final Integer format;
        public final Integer width;
        public final Integer height;

        public Row(long id, String path, String principal, int user, long generation,
                Integer format, Integer width, Integer height) {
            this.id = id;
            this.path = path;
            this.principal = principal;
            this.user = user;
            this.generation = generation;
            this.format = format;
            this.width = width;
            this.height = height;
        }
    }

    public static final class Values {
        public Integer format;
        public Integer width;
        public Integer height;
        public boolean empty() { return format == null && width == null && height == null; }
    }

    public interface Store {
        long cursor();
        List<Row> snapshot(long after, int limit);
        void commit(Row row, Values values);
        void saveCursor(long after);
        default void databaseFailure(SQLiteException failure) { }
    }
    public interface Reader {
        Values read(Row row, long remaining) throws IOException;
    }

    public static void run(Store store, Reader reader, LongSupplier now, BooleanSupplier cancelled) {
        final long end = now.getAsLong() + PASS_MS;
        long after = store.cursor();
        try {
            List<Row> rows = store.snapshot(after, PAGE);
            if (rows.isEmpty() && after >= 0) {
                after = -1;
                rows = store.snapshot(after, PAGE);
            }
            for (Row row : rows) {
                final long left = end - now.getAsLong();
                if (left <= 0 || cancelled.getAsBoolean()) break;
                try {
                    final Values values = reader.read(row, Math.min(ROW_MS, left));
                    if (cancelled.getAsBoolean()) break;
                    if (values != null && !values.empty()) {
                        if (now.getAsLong() >= end) break;
                        store.commit(row, values);
                    }
                    after = row.id;
                } catch (android.database.sqlite.SQLiteConstraintException
                        | android.database.sqlite.SQLiteBlobTooBigException
                        | android.database.sqlite.SQLiteDatatypeMismatchException rowLocal) {
                    after = row.id;
                } catch (SQLiteException failure) {
                    store.databaseFailure(failure);
                    break;
                } catch (OperationCanceledException failure) {
                    break;
                } catch (IOException | RuntimeException ignored) {
                    if (cancelled.getAsBoolean()) break;
                    // A failed row stays pending while the durable cursor advances to later rows.
                    after = row.id;
                }
            }
        } catch (SQLiteException failure) {
            store.databaseFailure(failure);
        } catch (OperationCanceledException failure) {
            // Cancellation preserves the last safe cursor for the next pass.
        }
        store.saveCursor(after);
    }
}
