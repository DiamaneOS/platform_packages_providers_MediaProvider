/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Tests for inserts that name, through {@link MediaColumns#DATA}, a file that the caller has
 * already created via direct path. The caller is an app that may not set
 * {@link MediaColumns#DATA}: it holds no storage permission and targets the current SDK.
 */
@RunWith(AndroidJUnit4.class)
public class MediaProviderInsertOwnedFileTest {

    // Another installed test app, used as the caller that does not own the file
    private static final String OTHER_APP = "com.android.providers.media.testapp.withmediaperms";

    private static final String REFUSED = "Mutation of _data is not allowed.";

    private static final Uri IMAGES = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL);
    private static final Uri VIDEO = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL);
    private static final Uri FILES = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);

    private static final int NO_PERMISSIONS = 0;
    private static final int READ_AND_WRITE_MEDIA = LocalCallingIdentity.PERMISSION_READ_IMAGES
            | LocalCallingIdentity.PERMISSION_READ_VIDEO
            | LocalCallingIdentity.PERMISSION_WRITE_IMAGES
            | LocalCallingIdentity.PERMISSION_WRITE_VIDEO;

    private static IsolatedContext sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    private static MediaProvider sMediaProvider;

    private static String sOwnerPackage;
    private static int sOwnerUid;
    private static int sOtherUid;

    private File mMediaDir;
    private File mDownloadDir;
    private File mPrivateFile;

    @BeforeClass
    public static void setUpClass() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.UPDATE_APP_OPS_STATS,
                Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
        sMediaProvider = (MediaProvider) sIsolatedResolver
                .acquireContentProviderClient(MediaStore.AUTHORITY).getLocalContentProvider();

        sOwnerPackage = MediaProviderTest.PERMISSIONLESS_APP;
        sOwnerUid = context.getPackageManager().getPackageUid(sOwnerPackage,
                PackageManager.MATCH_ALL);
        sOtherUid = context.getPackageManager().getPackageUid(OTHER_APP,
                PackageManager.MATCH_ALL);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Before
    public void setUp() throws Exception {
        final String name = "InsertOwnedFileTest" + System.nanoTime();
        mMediaDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), name);
        mDownloadDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), name);
        mPrivateFile = new File(InstrumentationRegistry.getTargetContext().getFilesDir(),
                name + ".jpg");
        assertThat(mMediaDir.mkdirs()).isTrue();
        assertThat(mDownloadDir.mkdirs()).isTrue();
    }

    @After
    public void tearDown() throws Exception {
        sIsolatedContext.setAsFuseThread(false);
        for (File dir : new File[] { mMediaDir, mDownloadDir }) {
            FileUtils.deleteContents(dir);
            dir.delete();
        }
        mPrivateFile.delete();
    }

    /**
     * A photo as a camera app saves it: the final file is written via its path, then that path is
     * inserted with the item not pending.
     */
    @Test
    public void testInsertOwnedImage_publishesExistingRow() throws Exception {
        final File file = createViaFuse(new File(mMediaDir, "IMG_1.jpg"), R.raw.test_image);
        final Row before = queryRow(file);
        assertThat(before.isPending).isEqualTo(1);
        assertThat(before.owner).isEqualTo(sOwnerPackage);
        assertThat(isVisibleToOtherApp(IMAGES, before.id)).isFalse();

        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaColumns.TITLE, "IMG_1");
        values.put(MediaColumns.MIME_TYPE, "image/jpeg");
        // Not where the file is. The row keeps the place of the file.
        values.put(MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM);
        values.put(MediaColumns.IS_PENDING, 0);
        values.put(MediaColumns.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
        values.put(MediaColumns.SIZE, file.length());
        values.put(MediaColumns.WIDTH, 1);
        values.put(MediaColumns.HEIGHT, 1);
        values.put(MediaColumns.ORIENTATION, 90);

        final Uri uri = insertAsApp(sOwnerUid, IMAGES, values);

        assertThat(uri).isEqualTo(ContentUris.withAppendedId(IMAGES, before.id));
        final Row after = queryRow(file);
        assertThat(after.id).isEqualTo(before.id);
        assertThat(after.isPending).isEqualTo(0);
        assertThat(after.mediaType).isEqualTo(FileColumns.MEDIA_TYPE_IMAGE);
        assertThat(after.owner).isEqualTo(sOwnerPackage);
        assertThat(after.dateAdded).isEqualTo(before.dateAdded);
        assertThat(after.relativePath).isEqualTo(
                Environment.DIRECTORY_DCIM + "/" + mMediaDir.getName() + "/");
        assertThat(isVisibleToOtherApp(IMAGES, before.id)).isTrue();

        // Inserting the same path again returns the same row
        assertThat(insertAsApp(sOwnerUid, IMAGES, values)).isEqualTo(uri);
    }

    /**
     * A video as a camera app saves it: recorded into a temporary file with a video name, renamed
     * to the final name, then the final path is inserted.
     */
    @Test
    public void testInsertOwnedVideo_afterRename_publishesExistingRow() throws Exception {
        final File temp = createViaFuse(new File(mMediaDir, "VID_1.tmp.mp4"), R.raw.test_video);
        final File file = new File(mMediaDir, "VID_1.mp4");
        renameViaFuse(temp, file);
        final Row before = queryRow(file);
        assertThat(before.isPending).isEqualTo(1);
        assertThat(isVisibleToOtherApp(VIDEO, before.id)).isFalse();

        final Uri uri = insertAsApp(sOwnerUid, VIDEO, videoValues(file));

        assertThat(uri).isEqualTo(ContentUris.withAppendedId(VIDEO, before.id));
        final Row after = queryRow(file);
        assertThat(after.isPending).isEqualTo(0);
        assertThat(after.mediaType).isEqualTo(FileColumns.MEDIA_TYPE_VIDEO);
        assertThat(after.owner).isEqualTo(sOwnerPackage);
        assertThat(after.dateAdded).isEqualTo(before.dateAdded);
        assertThat(isVisibleToOtherApp(VIDEO, before.id)).isTrue();
    }

    /**
     * A video that was recorded into a temporary file whose name has no media type, as a
     * time-lapse recorder does, and was then renamed into a media directory.
     */
    @Test
    public void testInsertOwnedVideo_fromUntypedTempFile_publishesExistingRow() throws Exception {
        // Only directories that take any file type accept this name from an app
        final File temp = createViaFuse(new File(mDownloadDir, "VID_2.mp4.tmp"), R.raw.test_video);
        assertThat(queryRow(temp).mediaType).isEqualTo(FileColumns.MEDIA_TYPE_NONE);
        final File file = new File(mMediaDir, "VID_2.mp4");
        renameViaFuse(temp, file);
        final Row before = queryRow(file);
        assertThat(before.isPending).isEqualTo(1);

        final Uri uri = insertAsApp(sOwnerUid, VIDEO, videoValues(file));

        assertThat(uri).isEqualTo(ContentUris.withAppendedId(VIDEO, before.id));
        final Row after = queryRow(file);
        assertThat(after.isPending).isEqualTo(0);
        assertThat(after.mediaType).isEqualTo(FileColumns.MEDIA_TYPE_VIDEO);
        assertThat(after.owner).isEqualTo(sOwnerPackage);
        assertThat(isVisibleToOtherApp(VIDEO, before.id)).isTrue();
    }

    @Test
    public void testInsertFileOfAnotherPackage_throws() throws Exception {
        final File file = createViaFuse(new File(mMediaDir, "IMG_2.jpg"), R.raw.test_image);
        final long id = queryRow(file).id;

        // Not even for a caller that may read and write all images and videos
        assertRefused(sOtherUid, READ_AND_WRITE_MEDIA, IMAGES, dataValues(file));
        assertThat(queryRow(file).isPending).isEqualTo(1);
        assertThat(queryRow(file).owner).isEqualTo(sOwnerPackage);

        // Nor once the file is published and that caller can see it
        assertThat(insertAsApp(sOwnerUid, IMAGES, dataValues(file))).isNotNull();
        assertThat(isVisibleToOtherApp(IMAGES, id)).isTrue();
        assertRefused(sOtherUid, READ_AND_WRITE_MEDIA, IMAGES, dataValues(file));
        assertThat(queryRow(file).owner).isEqualTo(sOwnerPackage);
    }

    @Test
    public void testInsertFileWithoutOwner_throws() throws Exception {
        final File file = stage(R.raw.test_image, new File(mMediaDir, "IMG_4.jpg"));
        assertThat(MediaStore.scanFile(sIsolatedResolver, file)).isNotNull();
        assertThat(queryRow(file).owner).isNull();

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, dataValues(file));

        assertThat(queryRow(file).owner).isNull();
    }

    @Test
    public void testInsertMissingFile_throws() throws Exception {
        final File file = new File(mMediaDir, "IMG_5.jpg");

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, dataValues(file));

        assertThat(queryRow(file)).isNull();
        assertThat(file.exists()).isFalse();
    }

    @Test
    public void testInsertPathOutsideSharedStorage_throws() throws Exception {
        stage(R.raw.test_image, mPrivateFile);

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, dataValues(mPrivateFile));
        assertRefused(sOwnerUid, NO_PERMISSIONS, MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_INTERNAL), dataValues(mPrivateFile));

        assertThat(queryRow(mPrivateFile)).isNull();
    }

    @Test
    public void testInsertIntoOtherCollection_throws() throws Exception {
        final File file = createViaFuse(new File(mMediaDir, "IMG_7.jpg"), R.raw.test_image);

        assertRefused(sOwnerUid, NO_PERMISSIONS, VIDEO, dataValues(file));
        assertRefused(sOwnerUid, NO_PERMISSIONS, FILES, dataValues(file));

        assertThat(queryRow(file).isPending).isEqualTo(1);
    }

    /**
     * A placeholder for an item that is still being processed: the caller asks for a pending
     * item. Such an insert is not a request to publish a file.
     */
    @Test
    public void testInsertPendingItem_throws() throws Exception {
        final File file = createViaFuse(new File(mMediaDir, "IMG_8.jpg"), R.raw.test_image);
        final ContentValues values = dataValues(file);
        values.put(MediaColumns.IS_PENDING, 1);

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, values);
        assertThat(queryRow(file).isPending).isEqualTo(1);

        // The same for a file that is not written yet
        final File missing = new File(mMediaDir, "IMG_9.jpg");
        final ContentValues missingValues = dataValues(missing);
        missingValues.put(MediaColumns.IS_PENDING, 1);

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, missingValues);
        assertThat(queryRow(missing)).isNull();
    }

    @Test
    public void testInsertOwnedFileInHiddenDirectory_throws() throws Exception {
        final File hiddenDir = new File(mMediaDir, ".Temp");
        assertThat(hiddenDir.mkdirs()).isTrue();
        final File file = createViaFuse(new File(hiddenDir, "IMG_10.jpg"), R.raw.test_image);

        assertRefused(sOwnerUid, NO_PERMISSIONS, IMAGES, dataValues(file));

        final Row after = queryRow(file);
        assertThat(after.mediaType).isEqualTo(FileColumns.MEDIA_TYPE_NONE);
        assertThat(after.owner).isEqualTo(sOwnerPackage);
    }

    private static ContentValues dataValues(File file) {
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.DATA, file.getAbsolutePath());
        return values;
    }

    private static ContentValues videoValues(File file) {
        final ContentValues values = dataValues(file);
        values.put(MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaColumns.TITLE, "VID");
        values.put(MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaColumns.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
        values.put(MediaColumns.SIZE, file.length());
        values.put(MediaColumns.WIDTH, 1);
        values.put(MediaColumns.HEIGHT, 1);
        values.put(MediaColumns.RESOLUTION, "1x1");
        values.put(MediaColumns.ORIENTATION, 0);
        values.put(MediaColumns.DURATION, 1);
        return values;
    }

    /**
     * Creates a file the way an app does via direct path: the FUSE daemon asks the provider for a
     * database entry, which is pending and owned by the app, and then the content is written.
     */
    private static File createViaFuse(File file, int resId) throws Exception {
        sIsolatedContext.setAsFuseThread(true);
        try {
            assertThat(sMediaProvider.insertFileIfNecessaryForFuse(file.getPath(), sOwnerUid))
                    .isEqualTo(0);
        } finally {
            sIsolatedContext.setAsFuseThread(false);
        }
        return stage(resId, file);
    }

    private static void renameViaFuse(File from, File to) {
        sIsolatedContext.setAsFuseThread(true);
        try {
            assertThat(sMediaProvider.renameForFuse(from.getPath(), to.getPath(), sOwnerUid))
                    .isEqualTo(0);
        } finally {
            sIsolatedContext.setAsFuseThread(false);
        }
    }

    private static Uri insertAsApp(int uid, int permission, Uri collection,
            ContentValues values) {
        final LocalCallingIdentity token = sMediaProvider.clearLocalCallingIdentity(
                LocalCallingIdentity.forTest(sIsolatedContext, uid, permission));
        try {
            return sMediaProvider.insert(collection, new ContentValues(values), null);
        } finally {
            sMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    private static Uri insertAsApp(int uid, Uri collection, ContentValues values) {
        return insertAsApp(uid, NO_PERMISSIONS, collection, values);
    }

    private static void assertRefused(int uid, int permission, Uri collection,
            ContentValues values) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> insertAsApp(uid, permission, collection, values));
        assertThat(e).hasMessageThat().isEqualTo(REFUSED);
    }

    /**
     * Whether another app that may read all images and videos finds the item in the collection.
     */
    private static boolean isVisibleToOtherApp(Uri collection, long id) {
        final Bundle extras = new Bundle();
        extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaColumns._ID + "=?");
        extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] { Long.toString(id) });
        final LocalCallingIdentity token = sMediaProvider.clearLocalCallingIdentity(
                LocalCallingIdentity.forTest(sIsolatedContext, sOtherUid,
                        LocalCallingIdentity.PERMISSION_READ_IMAGES
                                | LocalCallingIdentity.PERMISSION_READ_VIDEO));
        try (Cursor c = sMediaProvider.query(collection, new String[] { MediaColumns._ID },
                extras, null)) {
            return c.getCount() == 1;
        } finally {
            sMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    private static final class Row {
        long id;
        int isPending;
        int mediaType;
        String owner;
        long dateAdded;
        String relativePath;
    }

    /**
     * Reads the database entry of a path as the provider itself, pending or not.
     */
    private static Row queryRow(File file) {
        final Bundle extras = new Bundle();
        extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaColumns.DATA + "=?");
        extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] { file.getAbsolutePath() });
        extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        final String[] projection = new String[] {
                MediaColumns._ID,
                MediaColumns.IS_PENDING,
                FileColumns.MEDIA_TYPE,
                MediaColumns.OWNER_PACKAGE_NAME,
                MediaColumns.DATE_ADDED,
                MediaColumns.RELATIVE_PATH,
        };
        try (Cursor c = sIsolatedResolver.query(FILES, projection, extras, null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            assertThat(c.getCount()).isEqualTo(1);
            final Row row = new Row();
            row.id = c.getLong(0);
            row.isPending = c.getInt(1);
            row.mediaType = c.getInt(2);
            row.owner = c.getString(3);
            row.dateAdded = c.getLong(4);
            row.relativePath = c.getString(5);
            return row;
        }
    }
}
