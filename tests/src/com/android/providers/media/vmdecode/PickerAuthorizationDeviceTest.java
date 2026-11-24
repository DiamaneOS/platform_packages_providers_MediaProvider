/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.providers.media.vmdecode.frames.PickerImageClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Requires the distinct helper app to be installed and selected as the current cloud provider. */
public class PickerAuthorizationDeviceTest {
    @Test public void slowForceUriPermissionReturnsWithoutOpeningAnAsset() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ProviderInfo provider = context.getPackageManager().resolveContentProvider(
                "com.android.providers.media.vmslowcloud.media", 0);
        assertNotNull(provider);
        assertTrue(provider.forceUriPermissions);
        assertNotEquals(android.os.Process.myUid(), provider.applicationInfo.uid);
        assertNotEquals(1000, android.os.UserHandle.getAppId(android.os.Process.myUid()));
        final Uri control = Uri.parse("content://com.android.providers.media.vmslowcloud.control");
        context.getContentResolver().call(control, "reset", null, null);
        final CountDownLatch callback = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger(-1);
        final ParcelFileDescriptor[] liveness = ParcelFileDescriptor.createReliablePipe();
        try {
            final Bundle extras = new Bundle();
            extras.putParcelable(PickerImageClient.OUT, liveness[1]);
            final ResultReceiver local = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int value, Bundle result) {
                    code.set(value);
                    callback.countDown();
                }
            };
            // Marshal the callback as its framework base class for the remote provider.
            final Parcel parcel = Parcel.obtain();
            try {
                local.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                extras.putParcelable(PickerImageClient.RECEIVER,
                        ResultReceiver.CREATOR.createFromParcel(parcel));
            } finally { parcel.recycle(); }
            final long entry = SystemClock.uptimeMillis();
            context.getContentResolver().call("media", PickerImageClient.METHOD,
                    "content://com.android.providers.media.vmslowcloud.media/media/7", extras);
            assertTrue("Incoming call waited for external authorization",
                    SystemClock.uptimeMillis() - entry < 800);
            assertTrue(callback.await(5, TimeUnit.SECONDS));
            assertEquals(PickerImageClient.REJECTED, code.get());
            final Bundle state = context.getContentResolver().call(control, "state", null, null);
            assertEquals("The real full URI check did not reach the provider", 1, state.getInt("checks"));
            assertEquals("An asset opened before authorization", 0, state.getInt("opens"));
        } finally { liveness[0].close(); liveness[1].close(); }
    }
    @Test public void localImageCompletesWhileFourChecksForTwoCloudUidsRemainHeld() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final android.content.ContentResolver resolver = context.getContentResolver();
        final String[] authorities = {"com.android.providers.media.vmslowcloud.media",
                "com.android.providers.media.photopicker:443"};
        final String[] controls = {"com.android.providers.media.vmslowcloud.control",
                "com.android.providers.media.vmslowcloud2.control"};
        assertEquals(com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                Uri.parse("content://" + authorities[1]).getHost());
        assertNotEquals(com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY,
                authorities[1]);
        final ProviderInfo first = context.getPackageManager().resolveContentProvider(authorities[0], 0);
        final ProviderInfo second = context.getPackageManager().resolveContentProvider(authorities[1], 0);
        assertNotNull(first); assertNotNull(second);
        assertNotEquals(first.applicationInfo.uid, second.applicationInfo.uid);
        final String selected = selected();
        final java.util.List<ParcelFileDescriptor[]> liveness = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> pixels = new java.util.concurrent.atomic.AtomicReference<>();
        Uri row = null;
        try {
            final android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "sol-2c-rr-local-" + System.nanoTime() + ".jpg");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/sol-2c-rr/");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            row = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            assertNotNull(row);
            final android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888);
            image.eraseColor(android.graphics.Color.GREEN);
            try (java.io.OutputStream output = resolver.openOutputStream(row)) {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output));
            } finally { image.recycle(); }
            final android.content.ContentValues ready = new android.content.ContentValues();
            ready.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            assertEquals(1, resolver.update(row, ready, null, null));
            for (String control : controls) {
                resolver.call(Uri.parse("content://" + control), "reset", null, null);
                resolver.call(Uri.parse("content://" + control), "hold", null, null);
            }
            for (int provider = 0; provider < 2; provider++) {
                select(authorities[provider]);
                for (int call = 0; call < 2; call++) {
                    final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                    liveness.add(pipe);
                    final Bundle request = request(pipe[1], new ResultReceiver(null));
                    resolver.call("media", PickerImageClient.METHOD,
                            "content://" + authorities[provider] + "/media/7", request);
                }
                final long end = SystemClock.uptimeMillis() + 5_000;
                while (resolver.call(Uri.parse("content://" + controls[provider]), "state", null, null)
                        .getInt("checks") != 2 && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
                assertEquals("The full checks did not occupy both UID admissions", 2,
                        resolver.call(Uri.parse("content://" + controls[provider]), "state", null, null).getInt("checks"));
            }
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            liveness.add(pipe);
            final CountDownLatch local = new CountDownLatch(1);
            final AtomicInteger result = new AtomicInteger(-1);
            final java.util.concurrent.atomic.AtomicReference<String> error =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Bundle request = request(pipe[1], new ResultReceiver(null) {
                @Override protected void onReceiveResult(int value, Bundle answer) {
                    result.set(value);
                    if (answer != null) {
                        error.set(answer.getString(PickerImageClient.ERROR));
                        pixels.set(answer.getParcelable(PickerImageClient.BITMAP,
                                android.graphics.Bitmap.class));
                    }
                    local.countDown();
                }
            });
            resolver.call("media", PickerImageClient.METHOD, "content://"
                    + com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
                    + "/media/" + row.getLastPathSegment(), request);
            assertTrue("Local authorization was starved by four held cloud checks", local.await(10, TimeUnit.SECONDS));
            assertEquals(error.get(), PickerImageClient.OK, result.get());
            assertNotNull(pixels.get());
            for (String control : controls) {
                final Bundle state = resolver.call(Uri.parse("content://" + control), "state", null, null);
                assertEquals(2, state.getInt("checks"));
                assertEquals("A cloud check returned before the local result", 2, state.getInt("active"));
                assertEquals(0, state.getInt("opens"));
            }
        } finally {
            for (String control : controls) resolver.call(Uri.parse("content://" + control), "release", null, null);
            select(selected);
            if (pixels.get() != null) pixels.get().recycle();
            for (ParcelFileDescriptor[] pipe : liveness) { pipe[0].close(); pipe[1].close(); }
            if (row != null) resolver.delete(row, null, null);
        }
    }

    private static Bundle request(ParcelFileDescriptor out, ResultReceiver receiver) {
        final Bundle request = new Bundle();
        request.putParcelable(PickerImageClient.OUT, out);
        request.putParcelable(android.content.ContentResolver.EXTRA_SIZE, new android.graphics.Point(64, 64));
        final Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            request.putParcelable(PickerImageClient.RECEIVER, ResultReceiver.CREATOR.createFromParcel(parcel));
        } finally { parcel.recycle(); }
        return request;
    }
    private static String selected() throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("content call --uri content://media --method get_cloud_provider");
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            final String result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            final java.util.regex.Matcher value = java.util.regex.Pattern
                    .compile("get_cloud_provider_result=([^}\\]]+)").matcher(result);
            assertTrue(result, value.find());
            final String authority = value.group(1).trim();
            return "null".equals(authority) ? null : authority;
        }
    }

    private static void select(String authority) throws Exception {
        final String encoded = authority == null ? "-" : android.util.Base64.encodeToString(
                authority.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        final String apk = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getPackageCodePath();
        assertTrue(apk.matches("[A-Za-z0-9_./+=~-]+"));
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("env CLASSPATH=" + apk + " app_process /system/bin "
                        + PickerCloudSelection.class.getName() + " " + encoded);
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            final String result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(result, result.contains("true"));
        }
    }

}
