/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Procedural fixtures for the scoped real-provider thumbnail and idle-repair phone proof. */
public class MediaThumbnailLifecycleDeviceTest {
    private static final String PREFS = "sol_2c_r_lifecycle_20261122";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ContentResolver resolver = context.getContentResolver();

    private static byte[] jpeg(int color, int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output));
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private Uri create(byte[] bytes, boolean pending) throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "sol-2c-r-" + System.nanoTime() + ".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/sol-2c-r/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri row = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(row);
        write(row, bytes);
        if (!pending) publish(row);
        return row;
    }

    private void write(Uri row, byte[] bytes) throws Exception {
        try (OutputStream output = resolver.openOutputStream(row, "w")) { output.write(bytes); }
    }

    private void publish(Uri row) {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        assertEquals(1, resolver.update(row, values, null, null));
    }

    private void record(String key, Uri row) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, row.toString()).commit();
        final Bundle result = new Bundle();
        result.putString("fixture_" + key, row.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
    }

    @Test public void editWhileThumbnailIsRequestedThenReadCurrentPixels() throws Exception {
        final byte[] red = jpeg(Color.RED, 3072, 2048);
        final byte[] blue = jpeg(Color.BLUE, 3072, 2048);
        final Uri row = create(red, false);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicLong completed = new AtomicLong();
        try {
            final Future<?> old = worker.submit(() -> {
                entered.countDown();
                try {
                    final Bitmap bitmap = resolver.loadThumbnail(row, new Size(256, 256), null);
                    bitmap.recycle();
                } catch (java.io.IOException expectedRace) {
                    // A changed row may refuse an obsolete in-flight request.
                } finally { completed.set(SystemClock.uptimeMillis()); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            final long edit = SystemClock.uptimeMillis();
            write(row, blue);
            publish(row);
            old.get(70, TimeUnit.SECONDS);
            assertTrue("The thumbnail request finished before the edit began", completed.get() >= edit);
            final Bitmap latest = resolver.loadThumbnail(row, new Size(256, 256), null);
            try {
                final int color = latest.getPixel(latest.getWidth() / 2, latest.getHeight() / 2);
                assertTrue("Stale red pixels survived the image edit", Color.blue(color) > 180
                        && Color.red(color) < 70 && Color.green(color) < 70);
            } finally { latest.recycle(); }
        } finally {
            worker.shutdownNow();
            resolver.delete(row, null, null);
        }
    }

    @Test public void keepDeletionFixture() throws Exception {
        final Uri row = create(jpeg(Color.CYAN, 128, 96), false);
        record("delete", row);
        final Bitmap thumbnail = resolver.loadThumbnail(row, new Size(64, 64), null);
        thumbnail.recycle();
    }

    @Test public void deleteKeptFixture() {
        final android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String value = prefs.getString("delete", null);
        assertNotNull(value);
        assertEquals(1, resolver.delete(Uri.parse(value), null, null));
        prefs.edit().remove("delete").commit();
    }

    @Test public void keepOwnedPendingFixture() throws Exception {
        record("owned_pending", create(jpeg(Color.YELLOW, 128, 96), true));
    }

    @Test public void cleanupFixtures() {
        final android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String key : new String[] {"delete", "owned_pending"}) {
            final String value = prefs.getString(key, null);
            if (value != null) resolver.delete(Uri.parse(value), null, null);
        }
        prefs.edit().clear().commit();
    }
}
