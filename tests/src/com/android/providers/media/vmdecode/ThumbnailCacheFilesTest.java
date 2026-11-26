/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.providers.media.DatabaseHelper;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class ThumbnailCacheFilesTest {
    @Test public void discoveryNeverMutatesAnUnownedVolume() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),
                "foreign-thumbnails").toFile();
        final File jpeg = new File(volume, "Pictures/.thumbnails/7.jpg");
        jpeg.getParentFile().mkdirs();
        Files.write(jpeg.toPath(), new byte[] {1});
        try {
            assertTrue(ThumbnailCacheFiles.directories(volume, 0).isEmpty());
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(jpeg.toPath()));
            assertEquals(1, jpeg.getParentFile().list().length);
        } finally {
            jpeg.delete(); jpeg.getParentFile().delete(); jpeg.getParentFile().getParentFile().delete(); volume.delete();
        }
    }

    @Test public void reopenedIdentityRejectsOldPixelCaptureBeforeTargetSelection() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = new File(System.getProperty("java.io.tmpdir"));
        final String uuid = java.util.UUID.randomUUID().toString();
        final ThumbnailCacheFiles.Session before = ThumbnailCacheFiles.opened(true, uuid);
        final File old = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11, before);
        ThumbnailCacheFiles.opened(true, uuid);
        try {
            ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11, before);
            fail("Old pixels selected the reopened namespace");
        } catch (VmDecodeUnavailableException expected) { }
        final File current = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        assertEquals(old, current);
        ThumbnailCacheFiles.opened(false, uuid);
        assertNotEquals(ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11,
                ThumbnailCacheFiles.session(false)), current);
    }

    @Test public void sameDatabaseUuidReusesBytesAndChangedUuidRetiresThem() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thumbnail-database-uuid").toFile();
        final File database = new File(volume, "media.db");
        try {
            final File thumbnail;
            try (SQLiteDatabase first = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                ThumbnailCacheFiles.opened(true, DatabaseHelper.getOrCreateUuid(first));
                thumbnail = ThumbnailCacheFiles.file(volume, "Pictures", 10, 7, 11);
                Files.createDirectories(thumbnail.getParentFile().toPath());
                Files.write(thumbnail.toPath(), new byte[] {42});
            }
            try (SQLiteDatabase reopened = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                ThumbnailCacheFiles.opened(true, DatabaseHelper.getOrCreateUuid(reopened));
                final File current = ThumbnailCacheFiles.file(volume, "Pictures", 10, 7, 11);
                assertEquals(thumbnail, current);
                assertArrayEquals(new byte[] {42}, Files.readAllBytes(current.toPath()));
                assertFalse(ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11).exists());
            }
            Os.removexattr(database.getPath(), "user.uuid");
            try (SQLiteDatabase reopened = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                ThumbnailCacheFiles.opened(true, DatabaseHelper.getOrCreateUuid(reopened));
                final File changed = ThumbnailCacheFiles.file(volume, "Pictures", 10, 7, 11);
                assertNotEquals(thumbnail, changed);
                assertFalse(changed.exists());
            }
        } finally {
            SQLiteDatabase.deleteDatabase(database);
            try (var paths = Files.walk(volume.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
