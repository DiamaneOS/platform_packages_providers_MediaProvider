/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.database.MatrixCursor;
import android.net.Uri;
import org.junit.Test;
public class VmMediaRowsTest {
    private static final String[] COLUMNS = {"_data", "owner_package_name", "_user_id",
            "generation_modified", "mime_type", "_display_name"};
    private MatrixCursor row(Integer user, long generation) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{"/storage/emulated/10/image.jpg", "work.app", user,
                generation, "image/jpeg", "Fixture"});
        return cursor;
    }
    private VmMediaRows.Source source(Integer user, long generation) throws Exception {
        return VmMediaRows.row((u, p, a, c) -> row(user, generation),
                Uri.parse("content://10@media/external/file/7"), null);
    }
    @Test public void rowUserAndPackageStayTogether() throws Exception {
        VmMediaRows.Source source = source(10, 3);
        assertEquals(10, source.user);
        assertEquals("work.app", source.principal);
        assertEquals(3, source.generation);
    }
    @Test public void unknownUserIsNotTheProcessUser() {
        assertThrows(java.io.FileNotFoundException.class, () -> source(null, 3));
    }
    @Test public void anotherGenerationIsAnotherSource() throws Exception {
        assertFalse(source(10, 3).same(source(10, 4)));
        assertFalse(source(10, 3).same(source(0, 3)));
    }
    @Test public void rowUserUsesTheFilesCollectionAndABoundItemId() throws Exception {
        final String[][] cases = {
                {"content://10@media/external/images/media/7", "content://10@media/external/file", "7"},
                {"content://14@media/external_primary/audio/media/8", "content://14@media/external_primary/file", "8"},
                {"content://media/external_primary/file/9", "content://media/external_primary/file", "9"}
        };
        for (String[] item : cases) {
            VmMediaRows.Source source = VmMediaRows.row((uri, projection, args, signal) -> {
                // MediaProvider only permits the hidden user column on the files collection.
                assertEquals(item[1], uri.toString());
                assertEquals("_id=?", args.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION));
                assertArrayEquals(new String[] {item[2]}, args.getStringArray(
                        android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
                assertEquals(android.provider.MediaStore.MATCH_INCLUDE, args.getInt(
                        android.provider.MediaStore.QUERY_ARG_MATCH_PENDING));
                assertEquals(android.provider.MediaStore.MATCH_INCLUDE, args.getInt(
                        android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED));
                return row(14, 3);
            }, Uri.parse(item[0]), null);
            assertEquals(14, source.user);
        }
    }
    @Test public void identityIsRestoredWhenTheRowQueryFails() {
        final java.util.concurrent.atomic.AtomicInteger identity =
                new java.util.concurrent.atomic.AtomicInteger(7);
        assertThrows(IllegalStateException.class, () -> VmMediaRows.asProvider(
                () -> identity.getAndSet(0), identity::set, () -> {
                    assertEquals(0, identity.get());
                    throw new IllegalStateException("query failed");
                }));
        assertEquals(7, identity.get());
    }
    @Test public void sharedProviderThumbnailCarriesItsRowUser() throws Exception {
        final android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
        try (android.content.res.AssetFileDescriptor asset = VmMediaRows.thumbnailAsset(() -> {
            try { source(10, 3); }
            catch (Exception e) { throw new java.io.FileNotFoundException(e.toString()); }
            return pipe[0];
        })) {
            assertEquals(10, asset.getExtras().getInt("de.diamaneos.vmdecode.media_user", -1));
        } finally { pipe[1].close(); }
    }
    @Test public void asyncThumbnailRejectsAChangedRow() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Runnable> work =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean refused = new java.util.concurrent.atomic.AtomicBoolean();
        final android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
        try (android.content.res.AssetFileDescriptor asset = VmMediaRows.thumbnailAsset(() -> {
            try { source(10, 3); }
            catch (Exception e) { throw new java.io.FileNotFoundException(e.toString()); }
            work.set(VmMediaRows.thumbnailWork(() -> {
                try { source(10, 4); }
                catch (VmDecodeUnavailableException expected) { refused.set(true); }
                catch (Exception e) { throw new AssertionError(e); }
            }));
            return pipe[0];
        })) {
            Thread thread = new Thread(work.get());
            thread.start();
            thread.join();
            assertTrue(refused.get());
            assertEquals(10, source(10, 5).user);
        } finally { pipe[1].close(); }
    }
}
