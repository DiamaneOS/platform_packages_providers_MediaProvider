/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.providers.media.vmdecode.frames.PickerImageClient;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PickerImageClientTest {
    @Test public void callbackParcelNeedsOnlyTheFrameworkClassLoader() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean delivered =
                new java.util.concurrent.atomic.AtomicBoolean();
        final MockContentResolver resolver = new MockContentResolver(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        resolver.addProvider(MediaStore.AUTHORITY, new MockContentProvider() {
            @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
                final android.os.Parcel parcel = android.os.Parcel.obtain();
                try {
                    parcel.writeBundle(extras);
                    parcel.setDataPosition(0);
                    final Bundle incoming = parcel.readBundle(android.os.ResultReceiver.class.getClassLoader());
                    final android.os.ResultReceiver receiver = incoming.getParcelable(
                            PickerImageClient.RECEIVER, android.os.ResultReceiver.class);
                    assertEquals(android.os.ResultReceiver.class, receiver.getClass());
                    try (ParcelFileDescriptor output = incoming.getParcelable(
                            PickerImageClient.OUT, ParcelFileDescriptor.class)) {
                        receiver.send(PickerImageClient.REJECTED, new Bundle());
                        delivered.set(true);
                    } catch (IOException e) { throw new AssertionError(e); }
                } finally { parcel.recycle(); }
                return new Bundle();
            }
        });
        try (PickerImageClient client = new PickerImageClient()) {
            assertThrows(IOException.class, () -> client.load(resolver,
                    Uri.parse("content://com.android.providers.media.photopicker/media/7"), new Bundle()));
        }
        assertTrue("The callback survives without loading an application class", delivered.get());
    }

    @Test public void cancelIsPublishedBeforeProviderCallReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> output = new AtomicReference<>();
        MockContentResolver resolver = new MockContentResolver(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        resolver.addProvider(MediaStore.AUTHORITY, new MockContentProvider() {
            @Override public Bundle call(String authority, String method, String arg, Bundle extras) {
                try {
                    output.set(extras.getParcelable(PickerImageClient.OUT, ParcelFileDescriptor.class).dup());
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception e) { throw new IllegalStateException(e); }
                return new Bundle();
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PickerImageClient client = new PickerImageClient()) {
            Future<?> load = pool.submit(() -> {
                assertThrows(IOException.class, () -> client.load(resolver,
                        Uri.parse("content://com.android.providers.media.photopicker/media/7"), new Bundle()));
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            client.close();
            StructPollfd poll = new StructPollfd();
            poll.fd = output.get().getFileDescriptor();
            poll.events = 0;
            assertTrue(Os.poll(new StructPollfd[]{poll}, 1000) > 0);
            assertTrue((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0);
            release.countDown();
            load.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            if (output.get() != null) output.get().close();
            pool.shutdownNow();
        }
    }
}
