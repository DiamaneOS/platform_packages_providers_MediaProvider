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

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.ExifInterface;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.MediaStore;
import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.util.SpecialFormatDetector;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Idle field repair with durable independent pending fields and no source work under database locks. */
public final class VmMetadataRepair {
    private static final String CURSOR = "after";
    private static final String[] PROJECTION = {"_id", "_data", "owner_package_name", "_user_id",
            "generation_modified", "_special_format", "width", "height"};

    private VmMetadataRepair() { }

    public static void run(Context context, DatabaseHelper database, PickerDbFacade picker,
            CancellationSignal signal) {
        final SharedPreferences prefs = context.getSharedPreferences("diamaneos_vm_metadata_repair",
                Context.MODE_PRIVATE);
        final MetadataRepairPass.Store store = new MetadataRepairPass.Store() {
            public long cursor() { return prefs.getLong(CURSOR, -1); }
            public void saveCursor(long after) { prefs.edit().putLong(CURSOR, after).apply(); }
            public void databaseFailure(android.database.sqlite.SQLiteException failure) {
                android.util.Log.w("VmMetadataRepair", "Database repair pass stopped: "
                        + failure.getClass().getSimpleName());
            }
            public List<MetadataRepairPass.Row> snapshot(long after, int limit) {
                return database.runWithoutTransaction(db -> snapshotRows(db, after, limit, signal));
            }
            public void commit(MetadataRepairPass.Row row, MetadataRepairPass.Values values) {
                PickerSyncController.sIdleMaintenanceSyncLock.lock();
                try {
                    database.runWithTransaction(db -> {
                        commitRow(db, picker, row, values, signal);
                        return null;
                    });
                } finally {
                    PickerSyncController.sIdleMaintenanceSyncLock.unlock();
                }
            }
        };
        MetadataRepairPass.run(store, (row, remaining) -> {
            try (VmDecode.UserScope user = VmDecode.forUser(row.user);
                    VmDecode.Bound bound = VmDecode.boundWaits(remaining, signal)) {
                VmDecode.Info header = null;
                if (row.path != null && row.user >= 0) {
                    try { header = VmDecode.probe(new File(row.path), row.principal,
                            VmDecode.PRIORITY_BACKGROUND); }
                    catch (IOException ignored) { }
                }
                final VmDecode.Info checked = header;
                return VmDecode.metadataWork(row.principal, remaining, signal, () -> read(row, checked));
            }
        }, SystemClock::uptimeMillis, signal::isCanceled);
    }

    private static List<MetadataRepairPass.Row> snapshotRows(SQLiteDatabase db, long after,
            int limit, CancellationSignal signal) {
        final List<MetadataRepairPass.Row> rows = new ArrayList<>();
        try (Cursor cursor = db.query(false, "files", PROJECTION,
                MetadataRepairPass.PENDING + " AND _id>?", new String[] {Long.toString(after)},
                null, null, "_id", Integer.toString(limit), signal)) {
            while (cursor.moveToNext()) {
                rows.add(new MetadataRepairPass.Row(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.isNull(3) ? -1 : cursor.getInt(3), cursor.getLong(4),
                        integer(cursor, 5), integer(cursor, 6), integer(cursor, 7)));
            }
        }
        return rows;
    }

    private static Integer integer(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getInt(column);
    }

    private static MetadataRepairPass.Values read(MetadataRepairPass.Row row, VmDecode.Info header) throws IOException {
        final MetadataRepairPass.Values values = new MetadataRepairPass.Values();
        if (row.path == null || row.user < 0) return values;
        final File file = new File(row.path);
        try (VmDecode.UserScope user = VmDecode.forUser(row.user);
                MetadataInput input = new MetadataInput(new FileInputStream(file))) {
            final ExifInterface exif = new ExifInterface(input);
            input.check();
            if (row.width == null || row.height == null) {
                final int width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
                final int height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
                if (width > 0 && height > 0) {
                    values.width = width;
                    values.height = height;
                } else if (header != null) {
                    values.width = header.width;
                    values.height = header.height;
                }
            }
            if (row.format == null) {
                try {
                    values.format = SpecialFormatDetector.detect(exif, header);
                } catch (Exception ignored) {
                    // Parsing or source failure is not an ordinary-image verdict.
                }
            }
        }
        return values;
    }

    private static void commitRow(SQLiteDatabase db, PickerDbFacade picker,
            MetadataRepairPass.Row row, MetadataRepairPass.Values values, CancellationSignal signal) {
        final ContentValues external = new ContentValues();
        final ContentValues picked = new ContentValues();
        if (row.path == null || row.user < 0) return;
        final String where = "_id=? AND generation_modified=? AND _data=? AND _user_id=?"
                + (row.principal == null ? " AND owner_package_name IS NULL"
                        : " AND owner_package_name=?");
        final String[] args = row.principal == null
                ? new String[] {Long.toString(row.id), Long.toString(row.generation), row.path,
                        Integer.toString(row.user)}
                : new String[] {Long.toString(row.id), Long.toString(row.generation), row.path,
                        Integer.toString(row.user), row.principal};
        try (Cursor current = db.query(false, "files", new String[] {"_special_format", "width", "height"},
                where, args, null, null, null, "1", signal)) {
            if (!current.moveToFirst()) return;
            if (current.isNull(0) && values.format != null) {
                external.put("_special_format", values.format);
                picked.put(PickerDbFacade.KEY_STANDARD_MIME_TYPE_EXTENSION, values.format);
            }
            Integer width = integer(current, 1);
            Integer height = integer(current, 2);
            if (width == null && values.width != null) {
                width = values.width;
                external.put(MediaStore.MediaColumns.WIDTH, width);
                picked.put(PickerDbFacade.KEY_WIDTH, width);
            }
            if (height == null && values.height != null) {
                height = values.height;
                external.put(MediaStore.MediaColumns.HEIGHT, height);
                picked.put(PickerDbFacade.KEY_HEIGHT, height);
            }
            if (width != null && height != null && (external.containsKey(MediaStore.MediaColumns.WIDTH)
                    || external.containsKey(MediaStore.MediaColumns.HEIGHT))) {
                external.put(MediaStore.MediaColumns.RESOLUTION, width + "×" + height);
            }
        }
        if (external.size() == 0 || signal.isCanceled()) return;
        try (PickerDbFacade.UpdateMediaOperation operation = picker.beginUpdateMediaOperation(
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY)) {
            if (db.update("files", external, where, args) != 1) return;
            if (picked.size() != 0) operation.execute(Long.toString(row.id), picked);
            operation.setSuccess();
        }
    }
}
