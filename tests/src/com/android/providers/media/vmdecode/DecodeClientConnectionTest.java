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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.SystemClock;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class DecodeClientConnectionTest {
    @Test
    public void missingCallbackExpiresAndOldCallbacksCannotReplaceTheNewService() throws Exception {
        final Context context = mock(Context.class);
        final PackageManager pm = mock(PackageManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getPackageManager()).thenReturn(pm);
        final ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.name = "Decode";
        info.serviceInfo.applicationInfo = new ApplicationInfo();
        info.serviceInfo.applicationInfo.packageName = DecodeClient.SERVICE_PACKAGE;
        info.serviceInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(pm.resolveService(any(), anyInt())).thenReturn(info);
        final List<ServiceConnection> connections = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(context.bindService(any(), any(ServiceConnection.class), anyInt())).thenAnswer(call -> {
            connections.add(call.getArgument(1));
            return true;
        });
        final DecodeClient client = new DecodeClient(context);
        final java.lang.reflect.Method connect = DecodeClient.class.getDeclaredMethod("connect", long.class);
        connect.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> connect.invoke(client, 100L));
        final java.lang.reflect.Field start = DecodeClient.class.getDeclaredField("mBindStart");
        start.setAccessible(true);
        start.setLong(client, SystemClock.uptimeMillis() - 31000);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> connect.invoke(client, 100L));
        assertEquals(2, connections.size());
        final ComponentName name = new ComponentName(DecodeClient.SERVICE_PACKAGE, "Decode");
        connections.get(0).onServiceConnected(name, new Binder());
        final Binder current = new Binder();
        connections.get(1).onServiceConnected(name, current);
        connections.get(0).onBindingDied(name);
        final Object connected = connect.invoke(client, 100L);
        final java.lang.reflect.Field selected = connected.getClass().getDeclaredField("service");
        selected.setAccessible(true);
        final de.diamaneos.sanitizer.decode.IDecodeService service =
                (de.diamaneos.sanitizer.decode.IDecodeService) selected.get(connected);
        assertSame(current, service.asBinder());
        verify(context).unbindService(connections.get(0));
        verify(context, never()).unbindService(connections.get(1));
    }
    @Test
    public void slowRealConnectLeavesEachWaiterOnItsOwnDeadline() throws Exception {
        final Context context = mock(Context.class);
        final PackageManager pm = mock(PackageManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getPackageManager()).thenReturn(pm);
        final ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.name = "Decode";
        info.serviceInfo.applicationInfo = new ApplicationInfo();
        info.serviceInfo.applicationInfo.packageName = DecodeClient.SERVICE_PACKAGE;
        info.serviceInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(pm.resolveService(any(), anyInt())).thenReturn(info);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(context.bindService(any(), any(ServiceConnection.class), anyInt())).thenAnswer(call -> {
            entered.countDown();
            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        });
        final DecodeClient client = new DecodeClient(context);
        final java.lang.reflect.Method connect = DecodeClient.class.getDeclaredMethod("connect", long.class);
        connect.setAccessible(true);
        final java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final java.util.concurrent.Future<?> first = callers.submit(() ->
                    assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> connect.invoke(client, 300L)));
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.Future<?> second = callers.submit(() ->
                    assertThrows(java.lang.reflect.InvocationTargetException.class,
                            () -> connect.invoke(client, 60L)));
            second.get(220, java.util.concurrent.TimeUnit.MILLISECONDS);
            first.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            verify(context).bindService(any(), any(ServiceConnection.class), anyInt());
        } finally { release.countDown(); callers.shutdownNow(); }
    }

}
