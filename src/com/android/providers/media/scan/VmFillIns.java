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

package com.android.providers.media.scan;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.vmdecode.VmDecode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * DiamaneOS: files whose scan was left without the decode service's answers (its image size and special format, read
 * in the VM) because the service did not answer in time. They are scanned again on a thread of their own, with no
 * bound on the wait: at once when the service answers again or connects anew ({@link VmDecode#addAnswerListener}),
 * else after a wait that doubles from {@link #FIRST_MS} up to {@link #MAX_MS}. A file that still gets no answer after
 * {@link #MAX_TRIES} scans is left to idle maintenance, which fills in the same columns. Nothing here waits on a binder
 * thread, and nothing of the file is read here.
 */
final class VmFillIns {
    private static final String TAG = "VmFillIns";
    private static final int MAX_FILES = 256;
    private static final int MAX_TRIES = 8;
    private static final long FIRST_MS = 5_000;
    private static final long MAX_MS = 10 * 60_000;

    private static final ThreadLocal<Boolean> sFillInThread = ThreadLocal.withInitial(() -> false);

    private final Predicate<File> mScan;
    private final Object mLock = new Object();
    // File -> tries so far, oldest first. Guarded by mLock.
    private final LinkedHashMap<File, Integer> mFiles = new LinkedHashMap<>();
    private Handler mHandler; // guarded by mLock
    private boolean mScheduled; // guarded by mLock
    private long mWait = FIRST_MS; // guarded by mLock
    private boolean mRunning; // guarded by mLock
    private boolean mAnsweredWhileRunning; // guarded by mLock

    /** {@code scan} scans a file again and says whether the VM answered. */
    VmFillIns(@NonNull Predicate<File> scan) {
        this(scan, null);
    }

    VmFillIns(@NonNull Predicate<File> scan, Handler handler) {
        mScan = scan;
        mHandler = handler;
    }

    /** True on the fill-in thread: its scans wait for the VM as long as the service allows. */
    static boolean isFillInThread() {
        return sFillInThread.get();
    }

    /** {@code file} was scanned without the VM's answers: it is scanned again later. */
    void add(@NonNull File file) {
        if (isFillInThread()) {
            return; // The fill-in thread counts its own tries.
        }
        synchronized (mLock) {
            if (!mFiles.containsKey(file)) {
                if (mFiles.size() >= MAX_FILES) {
                    return; // Durable missing fields remain eligible for idle repair.
                }
                mFiles.put(file, 0);
            }
            if (mHandler == null) {
                final HandlerThread thread = new HandlerThread("VmFillIns", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                mHandler = new Handler(thread.getLooper());
                VmDecode.addAnswerListener(this::answered);
            }
            scheduleLocked(mWait);
        }
    }

    // The service answered or connected: the files are scanned now.
    private void answered() {
        synchronized (mLock) {
            if (mRunning) {
                mAnsweredWhileRunning = true; // the files this run gives back are tried again at once
                return;
            }
            if (mHandler == null || mFiles.isEmpty()) {
                return;
            }
            mWait = FIRST_MS;
            mHandler.removeCallbacksAndMessages(null);
            mScheduled = false;
            scheduleLocked(0);
        }
    }

    private void scheduleLocked(long delay) {
        if (mScheduled) {
            return;
        }
        mScheduled = true;
        mHandler.postDelayed(this::run, delay);
    }

    void run() {
        final List<Map.Entry<File, Integer>> files;
        synchronized (mLock) {
            mScheduled = false;
            mRunning = true;
            mAnsweredWhileRunning = false;
            files = new ArrayList<>();
            for (Map.Entry<File, Integer> entry : mFiles.entrySet()) {
                files.add(new java.util.AbstractMap.SimpleImmutableEntry<>(entry));
            }
            // Running entries keep their admission until the scan finishes.
        }
        int left = 0;
        sFillInThread.set(true);
        try {
            for (Map.Entry<File, Integer> entry : files) {
                boolean answered;
                try {
                    answered = mScan.test(entry.getKey());
                } catch (RuntimeException e) {
                    Log.w(TAG, "scan again failed: " + e.getMessage());
                    answered = true; // Not the VM's: idle maintenance has it.
                }
                synchronized (mLock) {
                    if (!answered && entry.getValue() + 1 < MAX_TRIES) {
                        mFiles.put(entry.getKey(), entry.getValue() + 1);
                        left++;
                    } else {
                        mFiles.remove(entry.getKey());
                    }
                }
            }
        } finally {
            sFillInThread.set(false);
        }
        synchronized (mLock) {
            mRunning = false;
            if (mFiles.isEmpty()) {
                mWait = FIRST_MS;
                return;
            }
            if (mAnsweredWhileRunning) {
                mWait = FIRST_MS;
                scheduleLocked(0);
            } else {
                if (left > 0) {
                    mWait = Math.min(mWait * 2, MAX_MS);
                }
                scheduleLocked(mWait);
            }
        }
        if (left > 0) {
            Log.i(TAG, left + " files still without the decode service's answers, scanned again later");
        }
    }
}
