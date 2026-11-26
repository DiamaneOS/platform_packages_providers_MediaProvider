/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.UserHandle;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Checks item visibility and the real provider projection using rows created by the test. */
public class VmMediaRowQueryDeviceTest {
    @Test public void imageItemReadsItsAuthenticatedUserThroughTheFilesCollection() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "diamaneos-row-" + System.nanoTime() + ".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri item = resolver.insert(MediaStore.Images.Media.getContentUri("external_primary"), values);
        assertNotNull(item);
        try {
            try (java.io.InputStream input = context.getResources().openRawResource(
                    com.android.providers.media.R.raw.test_image);
                    java.io.OutputStream output = resolver.openOutputStream(item)) {
                assertNotNull(output);
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            final VmMediaRows.Source source = VmMediaRows.forDisplay(resolver, item, null);
            assertEquals(UserHandle.myUserId(), source.user);
            assertEquals(context.getPackageName(), source.principal);
            assertEquals(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME), source.displayName);
            assertNotNull(source.path);
            final ContentValues trashed = new ContentValues();
            trashed.put(MediaStore.MediaColumns.IS_PENDING, 0);
            trashed.put(MediaStore.MediaColumns.IS_TRASHED, 1);
            assertEquals(1, resolver.update(item, trashed, null, null));
            final VmMediaRows.Source hidden = VmMediaRows.forDisplay(resolver, item, null);
            assertEquals(source.user, hidden.user);
            assertEquals(source.principal, hidden.principal);
        } finally {
            final Bundle cleanup = new Bundle();
            cleanup.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            cleanup.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            assertEquals(1, resolver.delete(item, cleanup));
        }
    }
}
