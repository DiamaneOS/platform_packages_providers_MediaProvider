/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Column;
import android.provider.ExportedSince;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Test;

/** The retained pre-function helper reopens the exact file created by the current helper. */
public class ThumbnailDatabaseRollbackDeviceTest {
    @Test public void actualOldHelperDeletesAfterNewOpenAtSchema1700() throws Exception {
        exercise(false);
    }

    @Test public void lateCleanupCannotReopenARetiredHelper() throws Exception {
        exercise(true);
    }

    private void exercise(boolean lifetime) throws Exception {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File root = new File(base.getCacheDir(), "rollback-helper-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        final android.test.mock.MockContentResolver[] local = new android.test.mock.MockContentResolver[1];
        final Context scoped = new ContextWrapper(base) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public File getFilesDir() { return root; }
            @Override public android.content.ContentResolver getContentResolver() { return local[0]; }
        };
        local[0] = new android.test.mock.MockContentResolver(scoped);
        final ProjectionHelper projection = new ProjectionHelper(Column.class, ExportedSince.class);
        final DatabaseBackupAndRecovery backup = new DatabaseBackupAndRecovery(new TestConfigStore(),
                new VolumeCache(scoped, new com.android.providers.media.util.UserCache(scoped))) {
            @Override protected boolean isStableUrisEnabled(String volume) { return false; }
            @Override protected void updateNextRowIdXattr(DatabaseHelper helper, long id) { }
        };
        try {
            for (String name : new String[] {"internal.db", "external.db"}) {
                try (DatabaseHelper current = new DatabaseHelper(scoped, name, 1700, false, false,
                        projection, null, null, MediaProvider.MIGRATION_LISTENER, null, false, backup) {
                    @Override protected String getExternalStorageDbXattrPath() { return root.getPath(); }
                }) {
                    final SQLiteDatabase db = current.getWritableDatabaseForTest();
                    db.execSQL("INSERT INTO files(_id,_data,_user_id,generation_modified,volume_name,media_type)"
                            + " VALUES(771,?,0,11,?,1)", new Object[] {new File(root, name + ".jpg").getPath(),
                                    name.equals("internal.db") ? "internal" : "external_primary"});
                    // Model the already-deployed incompatible trigger, then reopen the current build.
                    db.execSQL("DROP TRIGGER files_diamaneos_thumbnail_delete");
                    db.execSQL("CREATE TRIGGER files_diamaneos_thumbnail_delete AFTER DELETE ON files"
                            + " BEGIN SELECT _DIAMANEOS_THUMBNAIL_DELETE(old._id); END");
                }
                final java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
                try (DatabaseHelper current = new DatabaseHelper(scoped, name, 1700, false, false,
                        projection, null, null, MediaProvider.MIGRATION_LISTENER, null, false, backup) {
                    @Override protected String getExternalStorageDbXattrPath() { return root.getPath(); }
                    @Override public void onOpen(SQLiteDatabase db) { super.onOpen(db); opens.incrementAndGet(); }
                }) {
                    final SQLiteDatabase db = current.getWritableDatabaseForTest();
                    assertEquals(1700, db.getVersion());
                    if (lifetime) {
                        final Class<?> worker = Class.forName("com.android.providers.media.vmdecode.VmThumbnailDeletes");
                        java.lang.reflect.Constructor<?> constructor;
                        Object binding;
                        try { constructor = worker.getDeclaredConstructor(Context.class, SQLiteDatabase.class); binding = db; }
                        catch (NoSuchMethodException previousBuild) {
                            constructor = worker.getDeclaredConstructor(Context.class, DatabaseHelper.class); binding = current;
                        }
                        constructor.setAccessible(true);
                        final Object retired = constructor.newInstance(scoped, binding);
                        final java.lang.reflect.Method generation = worker.getDeclaredMethod("generation", long.class, int.class);
                        generation.setAccessible(true);
                        assertEquals(11L, generation.invoke(retired, 771L, 0));
                        current.close();
                        try { generation.invoke(retired, 771L, 0); }
                        catch (java.lang.reflect.InvocationTargetException closed) {
                            assertTrue(closed.getCause() instanceof IllegalStateException
                                    || closed.getCause() instanceof android.database.sqlite.SQLiteException);
                        }
                        assertEquals("Late cleanup reopened a retired helper", 1, opens.get());
                        assertFalse(db.isOpen());
                    }
                }
                if (lifetime) {
                    assertTrue(SQLiteDatabase.deleteDatabase(new File(root, name)));
                    continue;
                }
                try (RollbackDatabaseHelper1700 old = new RollbackDatabaseHelper1700(scoped, name,
                        1700, false, false, projection, null, null, null, null, false, backup) {
                    @Override protected String getExternalStorageDbXattrPath() { return root.getPath(); }
                }) {
                    final SQLiteDatabase db = old.getWritableDatabaseForTest();
                    assertEquals(1700, db.getVersion());
                    assertEquals(1, db.delete("files", "_id=771", null));
                }
                assertTrue(SQLiteDatabase.deleteDatabase(new File(root, name)));
            }
        } finally {
            for (File file : root.listFiles()) file.delete();
            root.delete();
        }
    }
}
