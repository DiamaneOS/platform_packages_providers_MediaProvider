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
import androidx.annotation.Nullable;

import de.diamaneos.sanitizer.decode.IDecodeService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The connection to the decode service. Binds on first use, only to the system app that holds the service.
 *
 * <p>Calls run on two lanes with their own threads, user-waiting and background, so background work can never take
 * the capacity of requests the user waits on. Inside a lane every owner (the package that supplied the bytes) has its
 * own queue, served in turn, with a cap on what one owner can queue and run while others wait. A call has two
 * deadlines: one for waiting in its queue, which fails it as unavailable and nothing else, and one from the moment it
 * started. Only a call that started and then missed its deadline drops the binding, since only then has the service
 * broken its own deadline. A missing, slow or dead service makes calls fail with {@link VmDecodeUnavailableException}.
 * Nothing here ever decodes a file in MediaProvider.
 */
final class DecodeClient {
    private static final String TAG = "VmDecode";

    static final String SERVICE_PACKAGE = "de.diamaneos.sanitizer";
    static final String SERVICE_ACTION = "de.diamaneos.sanitizer.action.DECODE";

    private static final long CONNECT_TIMEOUT_USER_WAITING_MS = 3_000;
    private static final long CONNECT_TIMEOUT_BACKGROUND_MS = 15_000;
    private static final long QUEUE_TIMEOUT_USER_WAITING_MS = 10_000;
    private static final long QUEUE_TIMEOUT_BACKGROUND_MS = 60_000;
    // From the start of the call: a little longer than the service's own budgets (20 s and 60 s), so a miss here
    // means the service broke its deadline.
    private static final long CALL_TIMEOUT_USER_WAITING_MS = 25_000;
    private static final long CALL_TIMEOUT_BACKGROUND_MS = 75_000;

    /** One call to the service. */
    interface Call<T> {
        T run(IDecodeService service) throws RemoteException, VmDecodeRejectedException;
    }

    private final Context mContext;
    // User-waiting: 4 threads, an owner may queue 8 and run 2 while others wait. Background: 2 threads, an owner may
    // queue 16 and run 1 while others wait.
    private final Lane mUserWaiting = new Lane("VmDecodeUser", 4, 8, 2);
    private final Lane mBackground = new Lane("VmDecodeBackground", 2, 16, 1);

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

    /** Runs {@code call} for the bytes of {@code owner} on the lane and within the deadlines of {@code priority}. */
    <T> T call(int priority, @Nullable String owner, @NonNull Call<T> call)
            throws VmDecodeUnavailableException, VmDecodeRejectedException {
        final boolean waiting = priority == IDecodeService.PRIORITY_USER_WAITING;
        final IDecodeService service = connect(waiting ? CONNECT_TIMEOUT_USER_WAITING_MS
                : CONNECT_TIMEOUT_BACKGROUND_MS);
        final Lane lane = waiting ? mUserWaiting : mBackground;
        final String key = owner == null ? "" : owner;
        final Task<T> task = new Task<>(() -> call.run(service));
        if (!lane.queue.offer(key, task)) {
            throw new VmDecodeUnavailableException("too many decode requests from " + key);
        }
        lane.start();
        try {
            if (!task.started.await(waiting ? QUEUE_TIMEOUT_USER_WAITING_MS : QUEUE_TIMEOUT_BACKGROUND_MS,
                    TimeUnit.MILLISECONDS) && lane.queue.remove(key, task)) {
                // Never started: the service is not at fault, the binding stays.
                throw new VmDecodeUnavailableException("waited too long in the queue");
            }
            if (!task.done.await(waiting ? CALL_TIMEOUT_USER_WAITING_MS : CALL_TIMEOUT_BACKGROUND_MS,
                    TimeUnit.MILLISECONDS)) {
                // Started and not answered in time: the service broke its own deadline. Drop the binding, the next
                // call binds again.
                unbind();
                throw new VmDecodeUnavailableException("decode service missed its deadline");
            }
        } catch (InterruptedException e) {
            lane.queue.remove(key, task);
            Thread.currentThread().interrupt();
            throw new VmDecodeUnavailableException("interrupted", e);
        }
        if (task.error instanceof VmDecodeRejectedException) {
            throw (VmDecodeRejectedException) task.error;
        }
        if (task.error != null) {
            throw new VmDecodeUnavailableException("decode call failed", task.error);
        }
        return task.result;
    }

    /** A call waiting in a lane. */
    private static final class Task<T> implements Runnable {
        interface Work<T> {
            T run() throws Exception;
        }

        final Work<T> work;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile T result;
        volatile Throwable error;

        Task(Work<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            started.countDown();
            try {
                result = work.run();
            } catch (Throwable t) {
                error = t;
            } finally {
                done.countDown();
            }
        }
    }

    /** Worker threads taking tasks from a fair queue. */
    private static final class Lane {
        final FairQueue<Runnable> queue;
        private final String mName;
        private final int mThreads;
        @GuardedBy("this")
        private boolean mStarted;

        Lane(String name, int threads, int perOwnerQueued, int perOwnerRunning) {
            mName = name;
            mThreads = threads;
            queue = new FairQueue<>(perOwnerQueued, perOwnerRunning);
        }

        synchronized void start() {
            if (mStarted) {
                return;
            }
            mStarted = true;
            for (int i = 0; i < mThreads; i++) {
                final Thread worker = new Thread(this::work, mName + i);
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void work() {
            while (true) {
                final FairQueue.Owned<Runnable> next;
                try {
                    next = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    next.item.run();
                } finally {
                    queue.done(next.owner);
                }
            }
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
