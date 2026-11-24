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
        if ("reset".equals(method)) { SlowCloud.checks.set(0); SlowCloud.opens.set(0); }
        final Bundle result = new Bundle();
        result.putInt("checks", SlowCloud.checks.get());
        result.putInt("opens", SlowCloud.opens.get());
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
