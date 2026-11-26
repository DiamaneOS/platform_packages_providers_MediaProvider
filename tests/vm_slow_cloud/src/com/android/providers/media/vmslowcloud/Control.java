/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmslowcloud;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class Control extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("make_pending".equals(method)) return pending();
        if ("reset".equals(method) || "release".equals(method)) {
            final java.util.concurrent.CountDownLatch held = SlowCloud.held;
            SlowCloud.held = null;
            if (held != null) held.countDown();
        }
        if ("reset".equals(method)) { SlowCloud.checks.set(0); SlowCloud.opens.set(0); }
        if ("hold".equals(method)) SlowCloud.held = new java.util.concurrent.CountDownLatch(1);
        final Bundle result = new Bundle();
        result.putInt("checks", SlowCloud.checks.get());
        result.putInt("opens", SlowCloud.opens.get());
        result.putInt("active", SlowCloud.active.get());
        return result;
    }
    private Bundle pending() {
        final ContentValues values = new ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "sol-2c-r-pending-" + System.nanoTime() + ".jpg");
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/sol-2c-r/");
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri row = getContext().getContentResolver().insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (row == null) throw new IllegalStateException("Pending fixture not inserted");
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(96, 64,
                android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.GREEN);
        try (java.io.OutputStream output = getContext().getContentResolver().openOutputStream(row, "w")) {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)) {
                throw new java.io.IOException("Pending fixture encode failed");
            }
        } catch (java.io.IOException failure) {
            getContext().getContentResolver().delete(row, null, null);
            throw new IllegalStateException(failure);
        } finally { bitmap.recycle(); }
        final Bundle result = new Bundle();
        result.putString("uri", row.toString());
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
