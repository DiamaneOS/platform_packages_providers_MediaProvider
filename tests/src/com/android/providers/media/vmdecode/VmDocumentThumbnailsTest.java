/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.test.mock.MockContentResolver;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileNotFoundException;
import org.junit.Test;

public class VmDocumentThumbnailsTest {
    @Test public void nullCursorRetainedUnmountedRowAndDenialNeverBecomeGone() throws Exception {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int[] mode = {0};
        final Bundle[] queried = new Bundle[1];
        final ContentProvider provider = new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
                queried[0] = args;
                if (mode[0] == 1) return null;
                if (mode[0] == 2) throw new SecurityException();
                if (mode[0] == 3) throw new android.os.OperationCanceledException();
                final MatrixCursor cursor = new MatrixCursor(new String[] {"_id"});
                if (mode[0] == 4) cursor.addRow(new Object[] {7L});
                return cursor;
            }
            @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String order) { throw new AssertionError(); }
            @Override public String getType(Uri uri) { return null; }
            @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
            @Override public int update(Uri uri, ContentValues values,String where,String[] args) { throw new UnsupportedOperationException(); }
            @Override public int delete(Uri uri,String where,String[] args) { throw new UnsupportedOperationException(); }
        };
        final ProviderInfo info = new ProviderInfo();
        info.authority = "media";
        info.applicationInfo = base.getApplicationInfo();
        provider.attachInfo(base, info);
        final MockContentResolver resolver = new MockContentResolver();
        resolver.addProvider("media", provider);
        final Context context = new ContextWrapper(base) {
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        final FileNotFoundException original = new FileNotFoundException("normal precheck result");
        try (android.content.res.AssetFileDescriptor gone = VmDocumentThumbnails.failed(context, "image:7", null, original)) {
            assertEquals(VmThumbnailErrors.GONE, gone.getExtras().getInt(VmThumbnailErrors.STATUS));
        }
        assertTrue(queried[0].getBoolean(MediaStore.QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES));
        assertEquals(MediaStore.MATCH_INCLUDE, queried[0].getInt(MediaStore.QUERY_ARG_MATCH_PENDING));
        assertEquals(MediaStore.MATCH_INCLUDE, queried[0].getInt(MediaStore.QUERY_ARG_MATCH_TRASHED));
        for (int outcome : new int[] {1, 4}) {
            mode[0] = outcome;
            try (android.content.res.AssetFileDescriptor deferred = VmDocumentThumbnails.failed(context, "image:7", null, original)) {
                assertTrue(deferred.getExtras().getBoolean(VmThumbnailErrors.LATER));
                assertEquals(0, deferred.getExtras().getInt(VmThumbnailErrors.STATUS, 0));
            }
        }
        mode[0] = 2;
        try { VmDocumentThumbnails.failed(context, "image:7", null, original); fail(); }
        catch (SecurityException denied) { }
        mode[0] = 3;
        try { VmDocumentThumbnails.failed(context, "image:7", null, original); fail(); }
        catch (android.os.OperationCanceledException cancelled) { }
    }
}
