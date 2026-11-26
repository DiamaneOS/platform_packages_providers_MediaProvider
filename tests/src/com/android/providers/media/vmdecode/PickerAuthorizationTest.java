/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PickerAuthorizationTest {
    @Test public void allCloudWorkersHeldStillAllowsLocalCheck() throws Exception {
        final CountDownLatch entered = new CountDownLatch(4);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch local = new CountDownLatch(1);
        final AtomicInteger premature = new AtomicInteger();
        final Runnable held = () -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        try {
            for (int uid : new int[] {10001, 10001, 10002, 10002}) {
                PickerAuthorization.submit(false, () -> uid, held,
                        premature::incrementAndGet, () -> { }, () -> { });
            }
            assertTrue("All four cloud checks must be held", entered.await(2, TimeUnit.SECONDS));
            PickerAuthorization.submit(true, () -> 10003, () -> { }, local::countDown,
                    () -> { }, () -> { });
            assertTrue("Cloud checks consumed local authorization capacity",
                    local.await(2, TimeUnit.SECONDS));
            assertEquals("An unfinished check opened its asset", 0, premature.get());
        } finally { release.countDown(); }
    }

    @Test public void bothPoolsKeepDenialAheadOfOpen() throws Exception {
        for (boolean local : new boolean[] {true, false}) {
            final CountDownLatch denied = new CountDownLatch(1);
            final AtomicInteger opened = new AtomicInteger();
            PickerAuthorization.submit(local, () -> 10004,
                    () -> { throw new SecurityException("Denied by the full permission check"); },
                    opened::incrementAndGet, () -> { }, denied::countDown);
            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertEquals(0, opened.get());
        }
    }
}
