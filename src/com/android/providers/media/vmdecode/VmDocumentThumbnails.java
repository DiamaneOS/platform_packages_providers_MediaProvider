/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import java.io.FileNotFoundException;

/** Called only after document authorization and the normal open's exceptional result. */
public final class VmDocumentThumbnails {
    private VmDocumentThumbnails() { }

    public static AssetFileDescriptor failed(Context context, String document,
            CancellationSignal signal, FileNotFoundException original) throws FileNotFoundException {
        if (document == null || !document.matches("(?:image|video):[1-9][0-9]*")) throw original;
        final String id = document.substring(document.indexOf(':') + 1);
        try { Long.parseLong(id); } catch (NumberFormatException invalid) { throw original; }
        final Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id=?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {id});
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        args.putBoolean(MediaStore.QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES, true);
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[] {"_id"}, args, signal)) {
            if (cursor != null && cursor.getCount() == 0) return VmThumbnailErrors.gone();
        } catch (android.os.OperationCanceledException | SecurityException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return VmThumbnailErrors.later();
        }
        return VmThumbnailErrors.later();
    }
}
