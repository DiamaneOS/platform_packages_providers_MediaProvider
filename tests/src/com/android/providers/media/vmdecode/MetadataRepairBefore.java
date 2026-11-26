/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
/** Exact prior commit body retained for the real SQLite before control. */
final class MetadataRepairBefore {
 private static Integer integer(Cursor cursor, int column) {
  return cursor.isNull(column) ? null : cursor.getInt(column);
 }
    private static void commitRow(SQLiteDatabase db, PickerDbFacade picker,
            MetadataRepairPass.Row row, MetadataRepairPass.Values values, CancellationSignal signal) {
        final ContentValues external = new ContentValues();
        final ContentValues picked = new ContentValues();
        final String where = "_id=? AND generation_modified=? AND _data=? AND _user_id=?"
                + " AND owner_package_name IS ?";
        final String[] args = {Long.toString(row.id), Long.toString(row.generation), row.path,
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
