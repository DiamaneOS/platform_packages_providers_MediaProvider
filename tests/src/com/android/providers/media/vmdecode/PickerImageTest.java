/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import com.android.providers.media.vmdecode.frames.PickerImage;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.Resource;
import org.junit.Test;

public class PickerImageTest {
    @Test public void checkedBitmapAndGainmapReachTheGlideResourceWithoutEncoding() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888);
        Bitmap gain = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        bitmap.setGainmap(new Gainmap(gain));
        PickerImage data = new PickerImage(bitmap, null);
        Resource<Bitmap> resource = new PickerImage.BitmapDecoder().decode(data, 8, 4, new Options());
        data.close();
        assertSame(bitmap, resource.get());
        assertFalse(bitmap.isRecycled());
        assertTrue(resource.get().hasGainmap());
        assertEquals(bitmap.getAllocationByteCount() + gain.getAllocationByteCount(), resource.getSize());
        resource.recycle();
        assertTrue(bitmap.isRecycled());
    }
}
