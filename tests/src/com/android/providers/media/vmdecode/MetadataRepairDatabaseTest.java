package com.android.providers.media.vmdecode;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Exercises the production snapshot and commit with Android SQLite and an isolated picker DB. */
public class MetadataRepairDatabaseTest {
    @Test
    public void nullPackageThenNamedPackageBothRepair() throws Exception {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File folder = new File(target.getCacheDir(), "repair-db-" + System.nanoTime());
        folder.mkdirs();
        final Context isolated = new android.content.ContextWrapper(target) {
            @Override public File getDatabasePath(String name) { return new File(folder, name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory,
                    android.database.DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getAbsolutePath(), factory, handler);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
            }
        };
        final PickerDbFacade picker = new PickerDbFacade(isolated, new PickerSyncLockManager(),
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);
        final Method snapshot = VmMetadataRepair.class.getDeclaredMethod("snapshotRows",
                SQLiteDatabase.class, long.class, int.class, CancellationSignal.class);
        final Class<?> implementation = "true".equals(InstrumentationRegistry.getArguments()
                .getString("repair_before")) ? MetadataRepairBefore.class : VmMetadataRepair.class;
        final Method commit = implementation.getDeclaredMethod("commitRow",
                SQLiteDatabase.class, PickerDbFacade.class, MetadataRepairPass.Row.class,
                MetadataRepairPass.Values.class, CancellationSignal.class);
        snapshot.setAccessible(true);
        commit.setAccessible(true);
        final CancellationSignal signal = new CancellationSignal();
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY, _data TEXT,"
                    + " owner_package_name TEXT, _user_id INTEGER, generation_modified INTEGER,"
                    + " media_type INTEGER, _special_format INTEGER, width INTEGER, height INTEGER,"
                    + " resolution TEXT)");
            db.execSQL("INSERT INTO files VALUES (1, '/one', NULL, 0, 7, 1, NULL, NULL, NULL, NULL)");
            db.execSQL("INSERT INTO files VALUES (2, '/two', 'test.app', 0, 8, 1, NULL, NULL, NULL, NULL)");
            final long[] cursor = {-1};
            MetadataRepairPass.run(new MetadataRepairPass.Store() {
                public long cursor() { return cursor[0]; }
                public void saveCursor(long value) { cursor[0] = value; }
                @SuppressWarnings("unchecked")
                public List<MetadataRepairPass.Row> snapshot(long after, int limit) {
                    return (List<MetadataRepairPass.Row>) invoke(snapshot, db, after, limit, signal);
                }
                public void commit(MetadataRepairPass.Row row, MetadataRepairPass.Values values) {
                    db.beginTransaction();
                    try {
                        invoke(commit, db, picker, row, values, signal);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
            }, (row, remaining) -> {
                MetadataRepairPass.Values values = new MetadataRepairPass.Values();
                values.width = 31;
                values.height = 17;
                values.format = 0;
                return values;
            }, () -> 0L, () -> false);
            try (Cursor rows = db.rawQuery("SELECT width, height FROM files ORDER BY _id", null)) {
                assertEquals(2, rows.getCount());
                while (rows.moveToNext()) {
                    assertEquals(31, rows.getInt(0));
                    assertEquals(17, rows.getInt(1));
                }
            }
            assertEquals(2, cursor[0]);
        } finally {
            final File[] files = folder.listFiles();
            if (files != null) for (File file : files) file.delete();
            folder.delete();
        }
    }

    private static Object invoke(Method method, Object... arguments) {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) {
                throw (RuntimeException) failure.getCause();
            }
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
