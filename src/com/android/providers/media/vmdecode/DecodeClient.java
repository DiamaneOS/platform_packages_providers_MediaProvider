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
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.IDecodeService;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
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
 * A caller that must not wait long (a scan that an app's binder call waits for) bounds every wait of its thread with
 * {@link VmDecode#boundWaits}: past that bound the call fails as unavailable and the binding stays. When the service
 * answers again after calls failed, or connects anew, the answer listeners are told ({@link VmDecode#addAnswerListener})
 * so what was left out can be asked for again.
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

    // The prepare hint: at most this often (queries come in bursts), sent from one thread of its own that binds when
    // needed, so the query that sends it never waits. A hint that finds the thread busy is dropped.
    private static final long PREPARE_GAP_MS = 2_000;
    private static final ThreadPoolExecutor PREPARE = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> {
                final Thread t = new Thread(r, "VmDecodePrepare");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());
    @GuardedBy("mLock")
    private final java.util.LinkedHashMap<Integer, Long> mLastPrepare = new java.util.LinkedHashMap<>();

    /** One call to the service. */
    interface Call<T> {
        T run(IDecodeService service) throws RemoteException, VmDecodeRejectedException;
    }

    private final Context mContext;
    // User-waiting: 4 threads, an owner may queue 8 and run 2 while others wait. Background: 2 threads, an owner may
    // queue 16 and run 1 while others wait.
    private final Lane mUserWaiting = new Lane("VmDecodeUser", 4, 8, 2);
    private final Lane mBackground = new Lane("VmDecodeBackground", 2, 16, 1);

    private final ThreadPoolExecutor mBindingWork = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), work -> {
                final Thread thread = new Thread(work, "VmDecodeBind");
                thread.setDaemon(true);
                return thread;
            });
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IDecodeService mService;
    @GuardedBy("mLock")
    private boolean mBinding;
    @GuardedBy("mLock")
    private long mBindStart;
    @GuardedBy("mLock")
    private Connection mConnection;
    private static final long STALE_BIND_MS = 30_000;

    // A call failed as unavailable and none succeeded since.
    private volatile boolean mFailed;

    private final class Connected {
        final IDecodeService service;
        final Connection connection;
        Connected(IDecodeService service, Connection connection) {
            this.service = service;
            this.connection = connection;
        }
    }

    private final class Connection implements ServiceConnection {
        boolean finished;
        boolean registered;
        boolean retired;
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) {
                if (mConnection != this || !mBinding || retired) return;
                mService = IDecodeService.Stub.asInterface(binder);
                mLock.notifyAll();
            }
            // A service that (re)started: what was left out may be asked for again.
            VmDecode.serviceAnswers();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                if (mConnection == this) mService = null;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind(this);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind(this);
        }
    }

    DecodeClient(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /** Runs {@code call} for the bytes of {@code owner} on the lane and within the deadlines of {@code priority}. */
    <T> T call(int priority, @Nullable String owner, @NonNull Call<T> call)
            throws VmDecodeUnavailableException, VmDecodeRejectedException {
        try {
            final T result = callOnce(priority, owner, call);
            if (mFailed) {
                mFailed = false;
                VmDecode.serviceAnswers();
            }
            return result;
        } catch (VmDecodeUnavailableException e) {
            mFailed = true;
            VmDecode.countUnavailable();
            throw e;
        }
    }

    // The wait left for this thread's bound (VmDecode.boundWaits), at most {@code wait}.
    private static long bounded(long wait) throws VmDecodeUnavailableException {
        final long end = VmDecode.boundEnd();
        if (end == 0) {
            return wait;
        }
        final long left = end - SystemClock.uptimeMillis();
        if (left <= 0) {
            throw new VmDecodeUnavailableException("no time left for the decode service");
        }
        return Math.min(wait, left);
    }

    private <T> T callOnce(int priority, @Nullable String owner, @NonNull Call<T> call)
            throws VmDecodeUnavailableException, VmDecodeRejectedException {
        final boolean waiting = priority == IDecodeService.PRIORITY_USER_WAITING;
        final Connected connected = connect(bounded(waiting ? CONNECT_TIMEOUT_USER_WAITING_MS
                : CONNECT_TIMEOUT_BACKGROUND_MS));
        final IDecodeService service = connected.service;
        final Lane lane = waiting ? mUserWaiting : mBackground;
        final String key = owner == null ? "" : owner;
        final Task<T> task = new Task<>(() -> call.run(service));
        if (!lane.queue.offer(key, task)) {
            throw new VmDecodeUnavailableException("too many decode requests from " + key);
        }
        lane.start();
        try {
            final long queueWait = waiting ? QUEUE_TIMEOUT_USER_WAITING_MS : QUEUE_TIMEOUT_BACKGROUND_MS;
            final long boundedQueueWait;
            try {
                boundedQueueWait = bounded(queueWait);
            } catch (VmDecodeUnavailableException e) {
                lane.queue.remove(key, task);
                throw e;
            }
            if (!await(task.started, boundedQueueWait) && lane.queue.remove(key, task)) {
                // Never started: the service is not at fault, the binding stays.
                throw new VmDecodeUnavailableException("waited too long in the queue");
            }
            final long callWait = waiting ? CALL_TIMEOUT_USER_WAITING_MS : CALL_TIMEOUT_BACKGROUND_MS;
            final long end = VmDecode.boundEnd();
            final long boundedCallWait = end == 0 ? callWait
                    : Math.max(1, Math.min(callWait, end - SystemClock.uptimeMillis()));
            if (!await(task.done, boundedCallWait)) {
                if (boundedCallWait < callWait) {
                    // Only this caller's bound ran out: the service may still answer in its time, the call runs on
                    // in its lane and the binding stays.
                    throw new VmDecodeUnavailableException("no answer within the caller's bound");
                }
                // Started and not answered in time: the service broke its own deadline. Drop the binding, the next
                // call binds again.
                unbind(connected.connection);
                throw new VmDecodeUnavailableException("decode service missed its deadline");
            }
        } catch (android.os.OperationCanceledException e) {
            lane.queue.remove(key, task);
            throw e;
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

    private static boolean await(CountDownLatch latch, long timeout) throws InterruptedException {
        final long end = SystemClock.uptimeMillis() + timeout;
        while (true) {
            VmDecode.checkBoundCancellation();
            final long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return latch.getCount() == 0;
            if (latch.await(Math.min(left, 50), TimeUnit.MILLISECONDS)) return true;
        }
    }

    /**
     * Tells the service that thumbnails of this user follow soon, so it may start its VM for them now. Never waits
     * and never fails: a hint that cannot be sent is dropped.
     */
    void prepare(int sourceUser) {
        final long now = SystemClock.uptimeMillis();
        synchronized (mLock) {
            if (now - mLastPrepare.getOrDefault(sourceUser, -PREPARE_GAP_MS) < PREPARE_GAP_MS) {
                return;
            }
            mLastPrepare.put(sourceUser, now);
            if (mLastPrepare.size() > 32) mLastPrepare.remove(mLastPrepare.keySet().iterator().next());
        }
        PREPARE.execute(() -> {
            try {
                final DecodeRequest request = new DecodeRequest();
                request.ownerUser = sourceUser;
                connect(CONNECT_TIMEOUT_USER_WAITING_MS).service.prepare(request);
            } catch (VmDecodeUnavailableException | RemoteException | RuntimeException e) {
                Log.w(TAG, "prepare hint not sent: " + e.getMessage());
            }
        });
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

    private Connected connect(long timeoutMs) throws VmDecodeUnavailableException {
        final long callerEnd = VmDecode.boundEnd();
        final long end = callerEnd == 0 ? SystemClock.uptimeMillis() + timeoutMs
                : Math.min(callerEnd, SystemClock.uptimeMillis() + timeoutMs);
        synchronized (mLock) {
            Connection attempt = null;
            while (true) {
                VmDecode.checkBoundCancellation();
                if (attempt != null && attempt.retired) {
                    throw new VmDecodeUnavailableException("decode binding was dropped");
                }
                final long now = SystemClock.uptimeMillis();
                if (now >= end) {
                    throw new VmDecodeUnavailableException("decode service did not connect in time");
                }
                if (mService != null && mService.asBinder().isBinderAlive()) {
                    return new Connected(mService, mConnection);
                }
                mService = null;
                if (mBinding && now - mBindStart >= STALE_BIND_MS) unbind(mConnection);
                if (mConnection == null) {
                    final Connection connection = new Connection();
                    mConnection = connection;
                    mBindStart = now;
                    mBinding = true;
                    try {
                        mBindingWork.execute(() -> beginBind(connection));
                    } catch (RuntimeException failure) {
                        mConnection = null;
                        mBinding = false;
                        throw new VmDecodeUnavailableException("decode bind worker unavailable", failure);
                    }
                }
                if (attempt == null && mBinding) attempt = mConnection;
                try {
                    mLock.wait(Math.min(end - now, 50));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new VmDecodeUnavailableException("interrupted", failure);
                }
            }
        }
    }

    private void beginBind(Connection connection) {
        boolean registered = false;
        try {
            final Intent intent = serviceIntent();
            if (intent != null) {
                registered = mContext.bindService(intent, connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "decode binding failed: " + failure.getMessage());
        } finally {
            final boolean cleanup;
            synchronized (mLock) {
                connection.registered = registered;
                connection.finished = true;
                if (!registered) connection.retired = true;
                cleanup = connection.retired;
                mLock.notifyAll();
            }
            if (cleanup) finishUnbind(connection);
        }
    }

    private void finishUnbind(Connection connection) {
        try {
            if (connection.registered) mContext.unbindService(connection);
        } catch (RuntimeException ignored) {
            // The framework may already have removed this registration.
        } finally {
            synchronized (mLock) {
                if (mConnection == connection) {
                    mConnection = null;
                    mBinding = false;
                    mService = null;
                }
                mLock.notifyAll();
            }
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

    private void unbind(Connection expected) {
        synchronized (mLock) {
            if (expected == null || mConnection != expected || expected.retired) return;
            expected.retired = true;
            mBinding = false;
            mService = null;
            if (expected.finished) mBindingWork.execute(() -> finishUnbind(expected));
            mLock.notifyAll();
        }
    }
}
