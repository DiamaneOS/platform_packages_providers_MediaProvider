/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.net.Uri;
import com.android.providers.media.photopicker.PickerSyncController;
import org.junit.Test;

public class PickerAuthorityTest {
    private static final String LOCAL = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

    @Test public void portLikeCloudNameCannotSelectTheLocalLane() {
        final Uri cloud = Uri.parse("content://" + LOCAL + ":443/media/opaque%2Fitem");
        assertEquals(LOCAL, cloud.getHost());
        assertEquals(LOCAL + ":443", ProviderAuthority.picker(cloud));
        assertFalse(ProviderAuthority.localPicker(cloud));
    }

    @Test public void fullProviderNameSurvivesAConcreteUserPrefix() {
        final Uri local = Uri.parse("content://10@" + LOCAL + "/media/7");
        final Uri cloud = Uri.parse("content://10@" + LOCAL + ":443/media/item");
        assertTrue(ProviderAuthority.localPicker(local));
        assertEquals(LOCAL + ":443", ProviderAuthority.picker(cloud));
        assertFalse(ProviderAuthority.localPicker(cloud));
    }

    @Test public void malformedSyntaxIsRejectedBeforeAdmission() {
        for (String item : new String[] {
                "content://-2@" + LOCAL + "/media/1",
                "content://2147483648@" + LOCAL + "/media/1",
                "content://0@10@" + LOCAL + "/media/1",
                "content://%63om.android.providers.media.photopicker/media/1",
                "content://" + LOCAL + "/media/1?query",
                "content://" + LOCAL + "/media/1#fragment",
                "content://" + LOCAL + "//media/1",
                "content://" + LOCAL + "/%6dedia/1",
                "content://" + LOCAL + "/media",
                "file://" + LOCAL + "/media/1"}) {
            try { ProviderAuthority.picker(Uri.parse(item)); fail(item); }
            catch (SecurityException expected) { }
        }
    }
}
