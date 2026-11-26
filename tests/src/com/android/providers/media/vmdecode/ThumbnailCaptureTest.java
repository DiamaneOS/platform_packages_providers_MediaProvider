/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.res.AssetFileDescriptor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ThumbnailCaptureTest {
    private static final Uri ROW = Uri.parse("content://media/external/images/media/7");
    // Every reopen keeps the database UUID, so only the per-open object fence can refuse.
    private static final String DATABASE_UUID = "f82c0429-c1da-411c-81bf-53c76fb8dd5f";
    private static final VmMediaRows.Query QUERY = (uri, projection, args, signal) -> {
        final MatrixCursor cursor = new MatrixCursor(projection);
        cursor.addRow(new Object[] {"/storage/emulated/0/image.jpg", "test.principal", 0, 11,
                "image/jpeg", "image.jpg"});
        return cursor;
    };

    @Test public void pausedPixelsCannotSelectANewNamespaceAfterReopen() throws Exception {
        final CountDownLatch pixels = new CountDownLatch(1);
        final CountDownLatch target = new CountDownLatch(1);
        final AtomicReference<Throwable> outcome = new AtomicReference<>();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        ThumbnailCacheFiles.opened(true, DATABASE_UUID);
        try (AssetFileDescriptor ignored = VmMediaRows.thumbnailAsset(() -> {
            try { VmMediaRows.row(QUERY, ROW, null); }
            catch (java.io.IOException failure) { throw new java.io.FileNotFoundException(failure.getMessage()); }
            final Thread thread = new Thread(VmMediaRows.thumbnailWork(() -> {
                pixels.countDown();
                try {
                    assertTrue(target.await(5, TimeUnit.SECONDS));
                    VmMediaRows.cacheFile(QUERY, ROW, null, "Pictures");
                    outcome.set(new AssertionError("Old pixels selected a new namespace"));
                } catch (Throwable failure) { outcome.set(failure); }
            }));
            worker.set(thread);
            thread.start();
            return VmThumbnailErrors.later().getParcelFileDescriptor();
        })) {
            assertTrue(pixels.await(5, TimeUnit.SECONDS));
            ThumbnailCacheFiles.opened(true, DATABASE_UUID);
            target.countDown();
            worker.get().join(5_000);
            assertFalse(worker.get().isAlive());
            assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof VmDecodeUnavailableException);
        } finally {
            target.countDown();
            if (worker.get() != null) worker.get().join(5_000);
        }
    }

    @Test public void rowReadCrossingReopenIsDeferred() throws Exception {
        assertThrows(VmDecodeUnavailableException.class, () -> VmMediaRows.row((uri, p, a, s) -> {
            final android.database.Cursor cursor = QUERY.run(uri, p, a, s);
            ThumbnailCacheFiles.opened(true, DATABASE_UUID);
            return cursor;
        }, ROW, null));
    }

    @Test public void detachedRowOnlyServesTheCurrentWarmGeneration() throws Exception {
        final var users = org.mockito.Mockito.mock(com.android.providers.media.util.UserCache.class);
        final var storage = org.mockito.Mockito.mock(android.os.storage.StorageManager.class);
        final var target = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        final java.io.File volume = java.nio.file.Files.createTempDirectory(
                target.getCacheDir().toPath(), "detached-thumbnail").toFile();
        final var storageContext = new android.content.ContextWrapper(target) {
            @Override
            public Object getSystemService(String name) {
                return android.content.Context.STORAGE_SERVICE.equals(name)
                        ? storage : super.getSystemService(name);
            }
        };
        org.mockito.Mockito.when(users.getContextForUser(android.os.UserHandle.of(0)))
                .thenReturn(storageContext);
        final var constructor = android.os.storage.StorageVolume.class.getConstructor(String.class,
                java.io.File.class, java.io.File.class, String.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                long.class, android.os.UserHandle.class, java.util.UUID.class, String.class,
                String.class);
        final var primary = constructor.newInstance("fixture", volume, volume, "Fixture primary",
                true, false, true, false, false, 0L, android.os.UserHandle.of(0), null, null,
                android.os.Environment.MEDIA_MOUNTED);
        org.mockito.Mockito.when(storage.getPrimaryStorageVolume()).thenReturn(primary);
        final VmMediaRows.Query detached = (uri, projection, args, signal) ->
                args.getBoolean(android.provider.MediaStore.QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES)
                        ? QUERY.run(uri, projection, args, signal) : new MatrixCursor(projection);
        final Uri named = Uri.parse("content://media/abcd-1234/images/media/7");
        final VmMediaRows.Query detachedNamed = (uri, projection, args, signal) -> {
            if (uri.equals(Uri.parse("content://media/abcd-1234/file"))) {
                throw new IllegalArgumentException("Volume abcd-1234 not found");
            }
            assertEquals(Uri.parse("content://media/external/file"), uri);
            assertEquals("_id=? AND volume_name=?",
                    args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION));
            assertArrayEquals(new String[] {"7", "abcd-1234"},
                    args.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
            return detached.run(uri, projection, args, signal);
        };
        ThumbnailCacheFiles.writerReady(true);
        ThumbnailCacheFiles.opened(true, DATABASE_UUID);
        final java.io.File file = ThumbnailCacheFiles.file(volume, "Pictures", 0, 7, 11);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.write(file.toPath(), new byte[] { 42 });
        try {
            try (AssetFileDescriptor warm = retainedThumbnail(detached, ROW, users, false);
                    java.io.InputStream bytes = warm.createInputStream()) {
                assertEquals(42, bytes.read());
                assertEquals(0, warm.getExtras().getInt(VmMediaRows.MEDIA_USER, -1));
            }
            try (AssetFileDescriptor warm = retainedThumbnail(detachedNamed, named, users, false);
                    java.io.InputStream bytes = warm.createInputStream()) {
                assertEquals(42, bytes.read());
            }
            try (AssetFileDescriptor crossing = retainedThumbnail(detachedNamed, named, users, true)) {
                assertTrue(crossing.getExtras().getBoolean(VmThumbnailErrors.LATER));
            }
            try (AssetFileDescriptor reused = retainedThumbnail(detached, ROW, users, false);
                    java.io.InputStream bytes = reused.createInputStream()) {
                assertEquals(42, bytes.read());
            }
            ThumbnailCacheFiles.opened(true, java.util.UUID.randomUUID().toString());
            try (AssetFileDescriptor cold = retainedThumbnail(detached, ROW, users, false)) {
                assertTrue(cold.getExtras().getBoolean(VmThumbnailErrors.LATER));
                assertFalse(cold.getExtras().containsKey(VmThumbnailErrors.STATUS));
            }
        } finally {
            for (java.io.File dir = file; !dir.equals(volume); dir = dir.getParentFile()) {
                java.nio.file.Files.deleteIfExists(dir.toPath());
            }
            java.nio.file.Files.deleteIfExists(volume.toPath());
        }
    }

    private static AssetFileDescriptor retainedThumbnail(VmMediaRows.Query query, Uri uri,
            com.android.providers.media.util.UserCache users, boolean reopenBeforeDescriptor)
            throws Exception {
        return VmMediaRows.thumbnailAsset(() -> {
            try {
                final var file = VmMediaRows.cacheFile(query, uri, users, "Pictures");
                if (reopenBeforeDescriptor) ThumbnailCacheFiles.opened(true, DATABASE_UUID);
                return android.os.ParcelFileDescriptor.open(file,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (java.io.IOException failure) {
                throw new java.io.FileNotFoundException(failure.getMessage());
            }
        });
    }
}
