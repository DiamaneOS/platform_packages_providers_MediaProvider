/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class ThumbnailMaintenanceTest {
    @Test public void lateGenerationAndOrphanAreRemovedWithoutTouchingActiveTemporary() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File current = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        final File old = new File(current.getParentFile(), "7.10");
        final File orphan = new File(current.getParentFile(), "8.3");
        final File active = new File(current.getParentFile(), "thumb" + ThumbnailCacheFiles.PROCESS + "-1.tmp");
        for (File file : new File[] {current, old, orphan, active}) write(file);
        try {
            assertEquals(current, ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11));
            assertTrue("Warm lookup performed synchronous eviction", old.exists());
            final Set<File> deleted = new HashSet<>();
            try (ThumbnailSweeper sweep = sweeper(volume, (id,user) -> id == 7 ? 11L : null, null,
                    deleted::add)) {
                int turns = 0;
                while (sweep.step(1)) assertTrue("Unbounded cache traversal", ++turns < 100);
                assertFalse(sweep.failed);
                assertEquals(2, sweep.removed);
                assertEquals(sweep.removed, deleted.size());
            }
            assertTrue(current.exists()); assertTrue(active.exists());
            assertFalse(old.exists()); assertFalse(orphan.exists());
        } finally { remove(volume.toPath()); }
    }

    @Test public void legacyReconciliationIsChildFirstAndRepeatsAfterRollback() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        final File foreign = new File(volume, "Pictures/.thumbnails/7.jpg");
        final File legacy = new File(volume, "Pictures/.thumbnails/vm-pixels-v2-u0/7.jpg");
        final File row = new File(volume, "Pictures/.thumbnails/vm-pixels-v3-u0/9/9.3");
        final File uuid = new File(row.getParentFile(), ".database_uuid");
        final File abandoned = new File(row.getParentFile(), "thumb-abandoned.tmp");
        write(foreign); write(row); write(uuid); write(abandoned);
        try {
            for (int transition = 0; transition < 2; transition++) {
                write(legacy);
                try (ThumbnailSweeper sweep = new ThumbnailSweeper(volume, "Pictures", 0,
                        "vm-pixels-v2-u", (id,user) -> null, null, file -> { })) {
                    while (sweep.step(2)) { }
                }
                assertFalse(legacy.exists()); assertTrue(foreign.exists());
            }
            try (ThumbnailSweeper sweep = new ThumbnailSweeper(volume, "Pictures", 0,
                    "vm-pixels-v3-u", (id,user) -> null, null, file -> { })) {
                while (sweep.step(1)) { }
                assertFalse(sweep.failed);
                assertEquals(5, sweep.removed);
            }
            assertFalse(row.getParentFile().exists());
            assertTrue(foreign.exists());
        } finally { remove(volume.toPath()); }
    }

    @Test public void symlinkedCacheComponentCannotDeleteForeignPixels() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        final File foreign = temporary();
        final File jpeg = new File(foreign, "7.jpg");
        write(jpeg);
        new File(volume, "Pictures/.thumbnails").mkdirs();
        Files.createSymbolicLink(new File(volume, "Pictures/.thumbnails/vm-pixels-v2-u0").toPath(), foreign.toPath());
        try {
            try (ThumbnailSweeper sweep = new ThumbnailSweeper(volume, "Pictures", 0,
                    "vm-pixels-v2-u", (id,user) -> null, null, file -> { })) {
                fail("A symlinked root was opened");
            } catch (java.io.IOException expected) { }
            assertTrue(jpeg.exists());
        } finally { remove(volume.toPath()); remove(foreign.toPath()); }
    }

    @Test public void sameUuidReopenKeepsWarmFilesThroughTheSweep() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        final String uuid = java.util.UUID.randomUUID().toString();
        ThumbnailCacheFiles.opened(true, uuid);
        final File warm = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        write(warm);
        try {
            ThumbnailCacheFiles.opened(true, uuid);
            assertEquals(warm, ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11));
            assertEquals(0, sweep(volume, (id,user) -> id == 7 ? 11L : null, null));
            assertTrue(warm.exists());
        } finally { remove(volume.toPath()); }
    }

    @Test public void changedUuidRetiresTheOldDirectoryThroughTheSweep() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File old = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        final File orphan = new File(old.getParentFile(), "thumb123.tmp");
        write(old); write(orphan);
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File current = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        write(current);
        try {
            assertNotEquals(old.getParentFile(), current.getParentFile());
            assertEquals(3, sweep(volume, (id,user) -> id == 7 ? 11L : null, null));
            assertFalse(old.getParentFile().exists());
            assertTrue(current.exists());
        } finally { remove(volume.toPath()); }
    }

    @Test public void unboundUuidFallsBackWithoutTouchingDatabaseDirectories() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File bound = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        write(bound);
        try {
            for (String unread : new String[] {null, "F82C0429-C1DA-411C-81BF-53C76FB8DD5F", "../x"}) {
                final ThumbnailCacheFiles.Session fallback = ThumbnailCacheFiles.opened(true, unread);
                assertFalse(fallback.databaseBound);
                assertTrue(fallback.name.startsWith(ThumbnailCacheFiles.PROCESS + "-e"));
                final File current = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
                assertEquals(fallback.name, current.getParentFile().getName());
                assertEquals(0, sweep(volume, (id,user) -> id == 7 ? 11L : null, null));
                assertTrue(bound.exists());
            }
        } finally { remove(volume.toPath()); }
    }

    @Test public void orphanedTemporariesGoWhileTheLiveProcessKeepsItsOwn() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        final String tag = "thumb" + ThumbnailCacheFiles.PROCESS + "-";
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File retired = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11).getParentFile();
        final File late = new File(retired, tag + "2.tmp");
        write(late);
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final File directory = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11).getParentFile();
        final File active = new File(directory, tag + "1.tmp");
        final File untagged = new File(directory, "thumb4711.tmp");
        final File foreign = new File(directory, "thumb" + java.util.UUID.randomUUID() + "-3.tmp");
        for (File file : new File[] {active, untagged, foreign}) write(file);
        try {
            final int removed = sweep(volume, (id,user) -> null, null);
            assertTrue("Live temporary removed", active.exists() && late.exists());
            assertFalse("Orphaned temporary kept", untagged.exists() || foreign.exists());
            assertEquals(2, removed);
        } finally { remove(volume.toPath()); }
    }

    @Test public void currentInternalDirectoryIsPrunedByInternalRows() throws Exception {
        ThumbnailCacheFiles.writerReady(true);
        final File volume = temporary();
        ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
        final ThumbnailCacheFiles.Session internal =
                ThumbnailCacheFiles.opened(false, java.util.UUID.randomUUID().toString());
        final File live = ThumbnailCacheFiles.file(volume, "Pictures", 0, 5, 3, internal);
        final File stale = new File(live.getParentFile(), "5.2");
        final File gone = new File(live.getParentFile(), "6.1");
        for (File file : new File[] {live, stale, gone}) write(file);
        try {
            assertEquals(0, sweep(volume, (id,user) -> null, null));
            assertTrue("Unavailable internal rows removed files", stale.exists() && gone.exists());
            assertEquals(2, sweep(volume, (id,user) -> null, (id,user) -> id == 5 ? 3L : null));
            assertTrue(live.exists());
            assertFalse("Stale internal thumbnail kept", stale.exists() || gone.exists());
        } finally { remove(volume.toPath()); }
    }

    private static ThumbnailSweeper sweeper(File volume, ThumbnailSweeper.Rows rows,
            ThumbnailSweeper.Rows internal, java.util.function.Consumer<File> deleted) throws Exception {
        return new ThumbnailSweeper(volume, "Pictures", 0, "vm-pixels-v4-u", rows, internal, deleted);
    }

    private static int sweep(File volume, ThumbnailSweeper.Rows rows, ThumbnailSweeper.Rows internal)
            throws Exception {
        try (ThumbnailSweeper sweep = sweeper(volume, rows, internal, file -> { })) {
            int turns = 0;
            while (sweep.step(1)) assertTrue("Unbounded cache traversal", ++turns < 100);
            assertFalse(sweep.failed);
            return sweep.removed;
        }
    }

    private static File temporary() throws Exception {
        return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "thumbnail-maintenance").toFile();
    }
    private static void write(File file) throws Exception {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), new byte[] {1});
    }
    private static void remove(Path path) throws Exception {
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
                for (Path child : files) remove(child);
            }
        }
        Files.deleteIfExists(path);
    }
}
