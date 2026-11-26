/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;

import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.Test;

public class ThumbnailRollbackTest {
    @Test public void sameSchemaOldDeleteAfterNewOpenForBothDatabases() throws Exception {
        for (boolean external : new boolean[] {true, false}) {
            final File file = File.createTempFile("thumbnail-rollback", ".db",
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
            try {
                try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                    schema(db);
                    db.setCustomScalarFunction("_DIAMANEOS_THUMBNAIL_DELETE", value -> null);
                    db.execSQL("CREATE TRIGGER files_diamaneos_thumbnail_delete AFTER DELETE ON files"
                            + " BEGIN SELECT _DIAMANEOS_THUMBNAIL_DELETE(old._id); END");
                    ThumbnailDeleteQueue.prepare(db, external);
                    db.execSQL("INSERT INTO files VALUES(7,0,11,'/storage/emulated/0/7.jpg')");
                }
                try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                    assertEquals(1700, old.getVersion());
                    // The stock pre-function hook retains its existing registration and arguments.
                    old.setCustomScalarFunction("_DELETE", value -> null);
                    old.execSQL("DELETE FROM files WHERE _id=7");
                    assertEquals(0, DatabaseUtils.longForQuery(old, "SELECT count(*) FROM files", null));
                }
            } finally { SQLiteDatabase.deleteDatabase(file); }
        }
    }

    @Test public void rollbackAndAbsentDrainerKeepBoundedDurableWork() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            schema(db);
            ThumbnailDeleteQueue.prepare(db, true);
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO files VALUES(7,0,11,'/storage/emulated/0/7.jpg')");
                db.execSQL("DELETE FROM files WHERE _id=7");
            } finally { db.endTransaction(); }
            assertEquals(0, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes", null));
            for (int id = 0; id < 600; id++) {
                db.execSQL("INSERT INTO files VALUES(?,0,11,'/storage/emulated/0/image.jpg')",
                        new Object[] {id});
                db.execSQL("DELETE FROM files WHERE _id=?", new Object[] {id});
            }
            assertEquals(256, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes", null));
            assertEquals(1, DatabaseUtils.longForQuery(db,
                    "SELECT dirty FROM diamaneos_thumbnail_state WHERE _id=1", null));
        }
    }

    @Test public void acknowledgmentPreservesSameKeyAndDoesNotDependOnOtherDeletes() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            schema(db);
            ThumbnailDeleteQueue.prepare(db, true);
            deleted(db, 7);
            final String exact = revision(db, "diamaneos_thumbnail_deletes WHERE media_id=7");
            final String broad = revision(db, "diamaneos_thumbnail_state WHERE _id=1");
            deleted(db, 8);
            ThumbnailDeleteQueue.acknowledge(db, 0, 7, exact);
            assertEquals(0, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes WHERE media_id=7", null));
            ThumbnailDeleteQueue.acknowledgeCoverage(db, broad);
            assertEquals(0, DatabaseUtils.longForQuery(db,
                    "SELECT dirty FROM diamaneos_thumbnail_state WHERE _id=1", null));
            final String repeated = revision(db, "diamaneos_thumbnail_deletes WHERE media_id=8");
            deleted(db, 8);
            ThumbnailDeleteQueue.acknowledge(db, 0, 8, repeated);
            assertEquals(1, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes WHERE media_id=8", null));
            ThumbnailDeleteQueue.acknowledge(db, 0, 8,
                    revision(db, "diamaneos_thumbnail_deletes WHERE media_id=8"));
            assertEquals(0, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes", null));
        }
    }

    @Test public void onlyNewOverflowInvalidatesCoverage() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            schema(db);
            ThumbnailDeleteQueue.prepare(db, true);
            final String state = "diamaneos_thumbnail_state WHERE _id=1";
            final String initial = revision(db, state);
            ThumbnailDeleteQueue.acknowledgeCoverage(db, initial);
            for (int id = 0; id < 256; id++) deleted(db, id);
            deleted(db, 0);
            assertEquals(initial, revision(db, state));
            deleted(db, 256);
            final String overflow = revision(db, state);
            assertNotEquals(initial, overflow);
            deleted(db, 257);
            final String newerOverflow = revision(db, state);
            assertNotEquals(overflow, newerOverflow);
            ThumbnailDeleteQueue.acknowledgeCoverage(db, overflow);
            assertEquals(1, DatabaseUtils.longForQuery(db, "SELECT dirty FROM " + state, null));
            ThumbnailDeleteQueue.acknowledgeCoverage(db, newerOverflow);
            assertEquals(0, DatabaseUtils.longForQuery(db, "SELECT dirty FROM " + state, null));
        }
    }

    @Test public void preIdentitySchemaCanMigrateBeforeQueueInstallation() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.setVersion(1000);
            db.execSQL("CREATE TABLE files(_id INTEGER PRIMARY KEY,_data TEXT)");
            ThumbnailDeleteQueue.prepare(db, true);
            db.execSQL("INSERT INTO files VALUES(7,'/synthetic/image.jpg')");
            assertEquals(1, db.delete("files", "_id=7", null));
            db.execSQL("ALTER TABLE files ADD COLUMN _user_id INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE files ADD COLUMN generation_modified INTEGER DEFAULT 1");
            ThumbnailDeleteQueue.prepare(db, true);
            db.execSQL("INSERT INTO files VALUES(8,'/synthetic/image.jpg',0,1)");
            assertEquals(1, db.delete("files", "_id=8", null));
            assertEquals(1, DatabaseUtils.longForQuery(db,
                    "SELECT count(*) FROM diamaneos_thumbnail_deletes", null));
        }
    }

    private static String revision(SQLiteDatabase db, String from) {
        return DatabaseUtils.stringForQuery(db, "SELECT hex(revision) FROM " + from, null);
    }

    private static void deleted(SQLiteDatabase db, long id) {
        db.execSQL("INSERT INTO files VALUES(?,0,11,'/storage/emulated/0/image.jpg')", new Object[] {id});
        db.execSQL("DELETE FROM files WHERE _id=?", new Object[] {id});
    }

    private static void schema(SQLiteDatabase db) {
        db.setVersion(1700);
        db.execSQL("CREATE TABLE files(_id INTEGER PRIMARY KEY,_user_id INTEGER,"
                + "generation_modified INTEGER,_data TEXT)");
    }
}
