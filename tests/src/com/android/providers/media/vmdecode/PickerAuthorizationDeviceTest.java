/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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
            extras.putParcelable(PickerImageClient.RECEIVER, new ResultReceiver(null) {
                @Override protected void onReceiveResult(int value, Bundle result) {
                    code.set(value);
                    callback.countDown();
                }
            });
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
}
