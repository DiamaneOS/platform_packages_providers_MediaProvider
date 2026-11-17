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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import de.diamaneos.sanitizer.decode.IDecodeService;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The connection to the decode service. Binds on first use, only to the system app that holds the service, and puts
 * a deadline on every call. A service that is missing, not a system app, slow or gone makes every call fail with
 * {@link VmDecodeUnavailableException}. Nothing here ever decodes a file in MediaProvider.
 */
final class DecodeClient {
    private static final String TAG = "VmDecode";

    static final String SERVICE_PACKAGE = "de.diamaneos.sanitizer";
    static final String SERVICE_ACTION = "de.diamaneos.sanitizer.action.DECODE";

    private static final long CONNECT_TIMEOUT_USER_WAITING_MS = 3_000;
    private static final long CONNECT_TIMEOUT_BACKGROUND_MS = 15_000;
    // A little longer than the service's own budgets (20 s and 60 s, queue included), so a miss here means the service
    // broke its deadline.
    private static final long CALL_TIMEOUT_USER_WAITING_MS = 25_000;
    private static final long CALL_TIMEOUT_BACKGROUND_MS = 75_000;

    // Two lanes with their own threads and queues, so background work (scans, idle maintenance) can never take the
    // capacity of requests the user waits on. A call stuck past its deadline keeps its thread until the service
    // answers or the binder dies: few threads, so a stuck service makes calls fail fast instead of piling threads up.
    private static final int USER_WAITING_THREADS = 4;
    private static final int USER_WAITING_QUEUE = 32;
    private static final int BACKGROUND_THREADS = 2;
    private static final int BACKGROUND_QUEUE = 64;

    /** One call to the service. */
    interface Call<T> {
        T run(IDecodeService service) throws RemoteException, VmDecodeRejectedException;
    }

    private final Context mContext;
    private final ThreadPoolExecutor mUserWaiting = lane(USER_WAITING_THREADS, USER_WAITING_QUEUE);
    private final ThreadPoolExecutor mBackground = lane(BACKGROUND_THREADS, BACKGROUND_QUEUE);

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IDecodeService mService;
    @GuardedBy("mLock")
    private boolean mBinding;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                mService = IDecodeService.Stub.asInterface(binder);
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind();
        }
    };

    DecodeClient(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    private static ThreadPoolExecutor lane(int threads, int queue) {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Runs {@code call} against the service within the deadline for {@code priority}. */
    <T> T call(int priority, @NonNull Call<T> call) throws VmDecodeUnavailableException,
            VmDecodeRejectedException {
        final boolean waiting = priority == IDecodeService.PRIORITY_USER_WAITING;
        final IDecodeService service = connect(waiting ? CONNECT_TIMEOUT_USER_WAITING_MS
                : CONNECT_TIMEOUT_BACKGROUND_MS);
        final Future<T> future;
        try {
            future = (waiting ? mUserWaiting : mBackground).submit(() -> call.run(service));
        } catch (RuntimeException e) {
            throw new VmDecodeUnavailableException("decode calls are all busy", e);
        }
        try {
            return future.get(waiting ? CALL_TIMEOUT_USER_WAITING_MS : CALL_TIMEOUT_BACKGROUND_MS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // A service past its deadline is not trusted to recover by itself: drop the binding, the next call binds
            // again.
            unbind();
            throw new VmDecodeUnavailableException("decode service missed its deadline", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new VmDecodeUnavailableException("interrupted", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof VmDecodeRejectedException) {
                throw (VmDecodeRejectedException) cause;
            }
            throw new VmDecodeUnavailableException("decode call failed", cause);
        }
    }

    private IDecodeService connect(long timeoutMs) throws VmDecodeUnavailableException {
        synchronized (mLock) {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                return mService;
            }
            mService = null;
            if (!mBinding) {
                final Intent intent = serviceIntent();
                if (intent == null) {
                    throw new VmDecodeUnavailableException("no decode service in the system image");
                }
                try {
                    mBinding = mContext.bindService(intent, mConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
                } catch (SecurityException e) {
                    throw new VmDecodeUnavailableException("not allowed to bind the decode service", e);
                }
                if (!mBinding) {
                    throw new VmDecodeUnavailableException("decode service did not bind");
                }
            }
            final long end = System.currentTimeMillis() + timeoutMs;
            while (mService == null) {
                final long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    throw new VmDecodeUnavailableException("decode service did not connect in time");
                }
                try {
                    mLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VmDecodeUnavailableException("interrupted", e);
                }
            }
            return mService;
        }
    }

    /** The service intent, only when a system app provides it. An app installed later can never stand in. */
    private Intent serviceIntent() {
        final Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
        final PackageManager pm = mContext.getPackageManager();
        final ResolveInfo info = pm.resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (info == null || info.serviceInfo == null || info.serviceInfo.applicationInfo == null) {
            return null;
        }
        final ApplicationInfo app = info.serviceInfo.applicationInfo;
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || !SERVICE_PACKAGE.equals(app.packageName)) {
            Log.w(TAG, "decode service is not a system app, not used");
            return null;
        }
        return intent.setComponent(new ComponentName(app.packageName, info.serviceInfo.name));
    }

    private void unbind() {
        synchronized (mLock) {
            if (mBinding) {
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException ignored) {
                    // Already unbound.
                }
            }
            mBinding = false;
            mService = null;
        }
    }
}
