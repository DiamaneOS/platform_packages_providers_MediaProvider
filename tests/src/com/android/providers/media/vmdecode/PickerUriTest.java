/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.vmdecode;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.PickerSyncController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The photo picker's image call (VmDecode.PICKER_IMAGE_CALL) takes only the picker's own items, checked against the
 * caller's own access: MediaProvider opens them with its own identity afterwards.
 */
@RunWith(JUnit4.class)
public class PickerUriTest {
    private static final String LOCAL = PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;

    private static Context access(boolean granted) {
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override
            public int checkContentUriPermissionFull(Uri uri, int pid, int uid, int modeFlags) {
                return granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            }
        };
    }

    private static void check(Context context, String uri) {
        VmDecode.checkPickerUri(context, Uri.parse(uri), 1, 10123);
    }

    @Test
    public void signatureAuthorizedCallerNeedsNoExplicitItemGrant() {
        final Context authorized = new ContextWrapper(access(true)) {
            @Override
            public int checkUriPermission(Uri uri, int pid, int uid, int flags) {
                return PackageManager.PERMISSION_DENIED;
            }
        };
        check(authorized, "content://" + LOCAL + "/media/1");
    }

    @Test
    public void pickerItemOfThisUserPasses() {
        check(access(true), "content://" + UserHandle.myUserId() + "@" + LOCAL + "/media/1");
        check(access(true), "content://" + LOCAL + "/media/1");
    }

    @Test
    public void mediaStoreUriIsRefused() {
        assertThrows(SecurityException.class, () -> check(access(true), "content://media/external/file/1"));
    }

    @Test
    public void otherProviderIsRefused() {
        assertThrows(SecurityException.class, () -> check(access(true), "content://com.example.files/media/1"));
    }

    @Test
    public void otherUserIsRefused() {
        int other = UserHandle.myUserId() + 10;
        assertThrows(SecurityException.class,
                () -> check(access(true), "content://" + other + "@" + LOCAL + "/media/1"));
    }

    @Test
    public void otherPathsAreRefused() {
        assertThrows(SecurityException.class, () -> check(access(true), "content://" + LOCAL + "/media/1/x"));
        assertThrows(SecurityException.class, () -> check(access(true), "content://" + LOCAL + "/albums/1"));
        assertThrows(SecurityException.class, () -> check(access(true), "content://" + LOCAL + "/media/1?x=1"));
        assertThrows(SecurityException.class, () -> check(access(true), "file:///sdcard/a.jpg"));
    }

    @Test
    public void itemTheCallerMayNotReadIsRefused() {
        assertThrows(SecurityException.class, () -> check(access(false), "content://" + LOCAL + "/media/1"));
    }
}
