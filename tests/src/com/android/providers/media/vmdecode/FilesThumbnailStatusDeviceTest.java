/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class FilesThumbnailStatusDeviceTest {
    private static final class MatchingResolver extends android.test.mock.MockContentResolver {
        MatchingResolver(Context context) {
            super(context);
        }

        public ContentInterface getThumbnailSource() {
            return null;
        }

        public int getUserId() {
            return 12345;
        }
    }

    @Test public void matchingResolverLoadsAndCannotOverrideThumbnailIdentity() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ContentResolver resolver = new MatchingResolver(context);
        final Method source = ContentResolver.class.getMethod("getThumbnailSource", ContentResolver.class);
        final Method user = ContentResolver.class.getMethod("getThumbnailSourceUser", ContentResolver.class);
        assertSame(resolver, source.invoke(null, resolver));
        assertEquals(context.getUser().getIdentifier(), user.invoke(null, resolver));
    }

    @Test public void statusOnlyDescriptorRemainsRetryableForAnOlderConsumer() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final android.app.UiAutomation shell = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        shell.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
        try {
        final Uri source = Uri.parse("content://" + context.getUserId()
                + "@com.android.providers.media.documents/document/image%3A7");
        final ContentInterface producer = (ContentInterface) java.lang.reflect.Proxy.newProxyInstance(
                ContentInterface.class.getClassLoader(), new Class<?>[] {ContentInterface.class},
                (proxy,method,args) -> {
                    if (method.getName().equals("openTypedAssetFile")) return VmThumbnailErrors.gone();
                    throw new UnsupportedOperationException();
                });
        final Method open = Class.forName("com.android.internal.diamaneos.cleancopies.ThumbnailWork")
                .getDeclaredMethod("open", ContentInterface.class, Uri.class,
                        Bundle.class, android.os.CancellationSignal.class, long.class);
        open.setAccessible(true);
        assertEquals("LaterException", openFailure(open, producer, source, new Bundle())
                .getClass().getSimpleName());
        } finally { shell.dropShellPermissionIdentity(); }
    }

    @Test public void normalDocumentOpenConfirmsGoneAndConsumerRejectsCopiedStatus() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ContentResolver resolver = context.getContentResolver();
        final android.app.UiAutomation shell = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        shell.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
        Uri row = null;
        try {
            final ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "sol-2c-rr-status-" + System.nanoTime() + ".jpg");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/sol-2c-rr/");
            row = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            assertNotNull(row);
            final Bitmap image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
            try (java.io.OutputStream output = resolver.openOutputStream(row)) {
                assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 90, output));
            } finally { image.recycle(); }
            final String id = row.getLastPathSegment();
            final Uri document = DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "image:" + id);
            final Uri source = document.buildUpon().encodedAuthority(context.getUserId()
                    + "@com.android.providers.media.documents").build();
            final Bundle options = new Bundle();
            options.putParcelable(ContentResolver.EXTRA_SIZE, new android.graphics.Point(64, 64));
            assertLiveJpeg(resolver, source, options);
            assertEquals(1, resolver.delete(row, null, null));
            row = null;
            try (AssetFileDescriptor gone = resolver.openTypedAssetFileDescriptor(source, "image/*", options)) {
                assertEquals(VmThumbnailErrors.GONE, gone.getExtras().getInt(VmThumbnailErrors.STATUS));
            }
            final Class<?> work = Class.forName("com.android.internal.diamaneos.cleancopies.ThumbnailWork");
            final Method open = work.getDeclaredMethod("open", ContentInterface.class, Uri.class,
                    Bundle.class, android.os.CancellationSignal.class, long.class);
            open.setAccessible(true);
            assertEquals("MissingException", openFailure(open, resolver, source, options).getClass().getSimpleName());
            try (android.content.ContentProviderClient client = resolver.acquireContentProviderClient(source)) {
                assertNotNull(client);
                assertEquals("MissingException", openFailure(open, client, source, options).getClass().getSimpleName());
                final Class<?> contract = Class.forName("android.content.IContentProvider");
                final java.lang.reflect.Field transport = android.content.ContentProviderClient.class
                        .getDeclaredField("mContentProvider");
                transport.setAccessible(true);
                final Object binder = contract.getMethod("asBinder").invoke(transport.get(client));
                final Object forged = java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(),
                        new Class<?>[] {contract}, (proxy,method,args) -> {
                            if (method.getName().equals("asBinder")) return binder;
                            if (method.getName().equals("openTypedAssetFile")) return VmThumbnailErrors.gone();
                            if (method.getName().equals("createCancellationSignal")) return null;
                            throw new UnsupportedOperationException();
                        });
                final java.lang.reflect.Constructor<?> constructor = android.content.ContentProviderClient.class
                        .getConstructor(ContentResolver.class, contract, String.class, boolean.class);
                try (android.content.ContentProviderClient impostor = (android.content.ContentProviderClient)
                        constructor.newInstance(new android.test.mock.MockContentResolver(context),
                                forged, source.getEncodedAuthority(), false)) {
                    assertEquals("A claimed Binder identity authenticated a different interface",
                            "LaterException", openFailure(open, impostor, source, options).getClass().getSimpleName());
                }
            }
            final ContentInterface copied = (ContentInterface) java.lang.reflect.Proxy.newProxyInstance(
                    ContentInterface.class.getClassLoader(), new Class<?>[] {ContentInterface.class},
                    (proxy,method,args) -> {
                        if (method.getName().equals("openTypedAssetFile")) return VmThumbnailErrors.gone();
                        throw new UnsupportedOperationException();
                    });
            assertEquals("LaterException", openFailure(open, copied, source, options).getClass().getSimpleName());
            shell.dropShellPermissionIdentity();
            if (context.checkSelfPermission("android.permission.MANAGE_DOCUMENTS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try (AssetFileDescriptor denied = resolver.openTypedAssetFileDescriptor(source, "image/*", options)) {
                    fail("An ungranted document reached the missing confirmation");
                } catch (SecurityException expected) { }
            }
        } finally {
            if (row != null) resolver.delete(row, null, null);
            shell.dropShellPermissionIdentity();
        }
    }

    private static void assertLiveJpeg(ContentResolver resolver, Uri source, Bundle options) throws Exception {
        final long end = SystemClock.uptimeMillis() + 30_000;
        String deferred = "";
        while (SystemClock.uptimeMillis() < end) {
            try (AssetFileDescriptor live = resolver.openTypedAssetFileDescriptor(source, "image/*", options)) {
                assertNotNull(live);
                final Bundle extras = live.getExtras();
                assertTrue(extras == null || extras.getInt(VmThumbnailErrors.STATUS, 0) == 0);
                if (extras != null && extras.getBoolean(VmThumbnailErrors.LATER)) {
                    deferred = extras.toString();
                    SystemClock.sleep(100);
                    continue;
                }
                final byte[] header = new byte[2];
                final ParcelFileDescriptor descriptor = live.getParcelFileDescriptor();
                final android.system.StructStat stat = android.system.Os.fstat(descriptor.getFileDescriptor());
                final String extent = "offset=" + live.getStartOffset() + " length=" + live.getDeclaredLength()
                        + " size=" + stat.st_size + " extras=" + extras;
                if (android.system.OsConstants.S_ISREG(stat.st_mode)) {
                    assertEquals(extent, 2, android.system.Os.pread(descriptor.getFileDescriptor(), header,
                            0, header.length, live.getStartOffset()));
                } else {
                    try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup())) {
                        final int first = input.read();
                        final int second = input.read();
                        if (first < 0 || second < 0) descriptor.checkError();
                        assertTrue(extent, first >= 0 && second >= 0);
                        header[0] = (byte) first;
                        header[1] = (byte) second;
                    }
                }
                assertEquals(extent, 0xff, header[0] & 0xff);
                assertEquals(extent, 0xd8, header[1] & 0xff);
                return;
            }
        }
        fail("The retained document stayed deferred: " + deferred);
    }

    private static Throwable openFailure(Method open, ContentInterface content, Uri uri, Bundle options) throws Exception {
        try {
            final Object source = open.invoke(null, content, uri, options, null, SystemClock.uptimeMillis() + 5_000);
            if (source instanceof AutoCloseable) ((AutoCloseable) source).close();
            fail("A status-only descriptor reached byte admission");
            return null;
        } catch (InvocationTargetException failure) { return failure.getCause(); }
    }
}
