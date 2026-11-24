/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Persistent SQL remains executable by either schema-1700 provider. */
public final class ThumbnailDeleteQueue {
    static final String TABLE = "diamaneos_thumbnail_deletes";
    static final String STATE = "diamaneos_thumbnail_state";
    private static final String TRIGGER = "files_diamaneos_thumbnail_delete";
    private ThumbnailDeleteQueue() { }

    static void acknowledge(SQLiteDatabase db, int user, long id, String revision) {
        db.delete(TABLE, "user_id=? AND media_id=? AND hex(revision)=?",
                new String[] {Integer.toString(user), Long.toString(id), revision});
    }

    static void acknowledgeCoverage(SQLiteDatabase db, String revision) {
        db.execSQL("UPDATE " + STATE + " SET dirty=0 WHERE _id=1 AND hex(revision)=?",
                new Object[] {revision});
    }

    public static void prepare(SQLiteDatabase db, boolean external) {
        if (db.isReadOnly()) return;
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='files'", null)) {
            if (!cursor.moveToFirst()) return;
        }
        final boolean transaction = !db.inTransaction();
        if (transaction) db.beginTransaction();
        try {
            db.execSQL("DROP TRIGGER IF EXISTS " + TRIGGER);
            if (external && hasIdentityColumns(db)) {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + "(user_id INTEGER NOT NULL,"
                        + "media_id INTEGER NOT NULL,generation INTEGER NOT NULL,revision BLOB NOT NULL,"
                        + "PRIMARY KEY(user_id,media_id)) WITHOUT ROWID");
                db.execSQL("CREATE TABLE IF NOT EXISTS " + STATE + "(_id INTEGER PRIMARY KEY CHECK(_id=1),"
                        + "dirty INTEGER NOT NULL,revision BLOB NOT NULL)");
                db.execSQL("INSERT OR IGNORE INTO " + STATE + " VALUES(1,1,randomblob(16))");
                db.execSQL("CREATE TRIGGER " + TRIGGER + " AFTER DELETE ON files BEGIN "
                        + "UPDATE " + STATE + " SET revision=randomblob(16),dirty=1 WHERE _id=1 AND "
                        + "(SELECT count(*) FROM " + TABLE + ")>=256 AND NOT EXISTS(SELECT 1 FROM "
                        + TABLE + " WHERE user_id=old._user_id AND media_id=old._id);"
                        + " INSERT OR REPLACE INTO " + TABLE
                        + " SELECT old._user_id,old._id,ifnull(old.generation_modified,0),randomblob(16)"
                        + " WHERE old._user_id>=0 AND old._id>=0 AND ((SELECT count(*) FROM " + TABLE
                        + ")<256 OR EXISTS(SELECT 1 FROM " + TABLE
                        + " WHERE user_id=old._user_id AND media_id=old._id)); END");
            } else {
                db.execSQL("CREATE TRIGGER " + TRIGGER + " AFTER DELETE ON files BEGIN SELECT 1; END");
            }
            if (transaction) db.setTransactionSuccessful();
        } finally { if (transaction) db.endTransaction(); }
    }

    private static boolean hasIdentityColumns(SQLiteDatabase db) {
        final java.util.Set<String> required = new java.util.HashSet<>(java.util.Set.of(
                "_id", "_user_id", "generation_modified"));
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(files)", null)) {
            while (cursor.moveToNext()) required.remove(cursor.getString(1));
        }
        return required.isEmpty();
    }
}
