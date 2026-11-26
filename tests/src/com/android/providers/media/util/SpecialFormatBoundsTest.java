/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.util;
import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import java.io.*;
import java.lang.reflect.*;
import org.junit.Test;

public class SpecialFormatBoundsTest {
    private ExifInterface exif(String xmp) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes));
        bitmap.recycle();
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes.toByteArray()));
        exif.setAttribute(ExifInterface.TAG_XMP, xmp);
        return exif;
    }
    private void refused(String contents) throws Exception {
        ExifInterface exif = exif("<x:xmpmeta xmlns:x='adobe:ns:meta/'>" + contents + "</x:xmpmeta>");
        Method motion = SpecialFormatDetector.class.getDeclaredMethod("isMotionPhoto", ExifInterface.class);
        motion.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> motion.invoke(null, exif));
        assertTrue(failure.getCause() instanceof IOException
                || failure.getCause() instanceof org.xmlpull.v1.XmlPullParserException);
    }
    @Test public void motionPhotoXmpSharesTheMetadataBudgets() throws Exception {
        refused("<item>".repeat(70) + "</item>".repeat(70));
        refused("<item/>".repeat(20000));
        refused("x".repeat(1024 * 1024));
    }
}
