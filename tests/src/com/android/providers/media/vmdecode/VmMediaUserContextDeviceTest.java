/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.UserHandle;
import android.provider.MediaStore;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A sharing clone redirects its provider without installing that provider package. */
public class VmMediaUserContextDeviceTest {
    @Before public void allowProfileMetadataQueries() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity();
    }

    @After public void restoreTestPermissions() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static Context providerContext() throws Exception {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ProviderInfo provider = target.getPackageManager().resolveContentProvider(
                MediaStore.AUTHORITY, 0);
        assertNotNull(provider);
        return target.createPackageContext(provider.packageName, 0);
    }

    @Test public void sharingCloneNeedsNoProviderPackageInstalledThere() throws Exception {
        final String argument = InstrumentationRegistry.getArguments().getString("cloneUser");
        assumeTrue("The device proof supplies its own sharing clone", argument != null);
        final int user = Integer.parseInt(argument);
        final Context provider = providerContext();
        assertThrows(IllegalStateException.class,
                () -> provider.createContextAsUser(UserHandle.of(user), 0));
        assertEquals(user, VmMediaUsers.contextForUser(provider, user).getUser().getIdentifier());
        assertTrue(VmMediaUsers.serves(provider, user));
    }

    @Test public void managedProfileDoesNotBecomeASharingClone() throws Exception {
        final String argument = InstrumentationRegistry.getArguments().getString("workUser");
        assumeTrue("The device proof supplies its own work profile", argument != null);
        assertFalse(VmMediaUsers.serves(providerContext(), Integer.parseInt(argument)));
    }

    @Test public void providerUserRemainsAccepted() throws Exception {
        final Context provider = providerContext();
        assertTrue(VmMediaUsers.serves(provider, provider.getUser().getIdentifier()));
    }
}
