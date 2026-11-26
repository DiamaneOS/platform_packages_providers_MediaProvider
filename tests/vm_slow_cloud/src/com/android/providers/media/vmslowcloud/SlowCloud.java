/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmslowcloud;

import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;

public final class SlowCloud extends CloudMediaProvider {
    static volatile java.util.concurrent.CountDownLatch held;
    static final AtomicInteger checks = new AtomicInteger();
    static final AtomicInteger opens = new AtomicInteger();
    static final AtomicInteger active = new AtomicInteger();
    @Override public boolean onCreate() { return true; }
    @Override public int checkUriPermission(Uri uri, int uid, int modes) {
        checks.incrementAndGet();
        active.incrementAndGet();
        try {
            final java.util.concurrent.CountDownLatch wait = held;
            if (wait == null) SystemClock.sleep(1500);
            else try { wait.await(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return PackageManager.PERMISSION_DENIED;
        } finally { active.decrementAndGet(); }
    }
    @Override public Cursor onQueryMedia(Bundle extras) {
        return new MatrixCursor(new String[] {CloudMediaProviderContract.MediaColumns.ID,
                CloudMediaProviderContract.MediaColumns.MIME_TYPE,
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
                CloudMediaProviderContract.MediaColumns.SIZE_BYTES});
    }
    @Override public Cursor onQueryDeletedMedia(Bundle extras) {
        return new MatrixCursor(new String[] {CloudMediaProviderContract.MediaColumns.ID});
    }
    @Override public Bundle onGetMediaCollectionInfo(Bundle extras) {
        final Bundle result = new Bundle();
        result.putString(CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID,
                "slow-permission-fixture");
        result.putLong(CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, 0);
        return result;
    }
    @Override public AssetFileDescriptor onOpenPreview(String id, Point size, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        opens.incrementAndGet();
        throw new FileNotFoundException("Unauthorized fixture preview must never open");
    }
    @Override public ParcelFileDescriptor onOpenMedia(String id, Bundle extras,
            CancellationSignal signal) throws FileNotFoundException {
        opens.incrementAndGet();
        throw new FileNotFoundException("Unauthorized fixture media must never open");
    }
}
