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

import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces readiness and bounded timer retries for a still-attached permission preview. */
public final class VmDialogReload implements AutoCloseable, View.OnAttachStateChangeListener {
    private static final Set<VmDialogReload> sLive =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final AtomicBoolean sListening = new AtomicBoolean();
    private final View mView;
    private final Runnable mLoad;
    private final long mEnd = SystemClock.uptimeMillis() + 60_000;
    private final Runnable mRetry = this::start;
    private volatile boolean mClosed;
    private boolean mLoading;
    private boolean mNeeded;
    private boolean mAnnounced;
    private long mWait = 1000;

    public VmDialogReload(View view, Runnable load) {
        mView = view;
        mLoad = load;
        view.addOnAttachStateChangeListener(this);
        synchronized (sLive) { sLive.add(this); }
        if (sListening.compareAndSet(false, true)) VmDecode.addAnswerListener(VmDialogReload::answered);
    }

    public void start() {
        mView.removeCallbacks(mRetry);
        if (mClosed || mLoading || remainingMillis() <= 0) return;
        mLoading = true;
        mNeeded = false;
        mLoad.run();
    }

    public boolean isClosed() { return mClosed; }

    public long remainingMillis() {
        return Math.max(0, mEnd - SystemClock.uptimeMillis());
    }

    public void complete(boolean retryable) {
        mLoading = false;
        mNeeded = retryable;
        if (!mClosed && retryable && SystemClock.uptimeMillis() < mEnd) {
            mView.postDelayed(mRetry, Math.min(mWait, mEnd - SystemClock.uptimeMillis()));
            mWait = Math.min(5000, mWait * 2);
        }
    }

    public boolean firstResult() {
        final boolean first = !mAnnounced;
        mAnnounced = true;
        return first;
    }

    private static void answered() {
        final ArrayList<VmDialogReload> live;
        synchronized (sLive) { live = new ArrayList<>(sLive); }
        for (VmDialogReload reload : live) reload.mView.post(() -> {
            if (!reload.mClosed && reload.mNeeded && !reload.mLoading
                    && reload.mView.isAttachedToWindow() && SystemClock.uptimeMillis() < reload.mEnd) {
                reload.start();
            }
        });
    }

    public static void recovered(ImageView view) {
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setBackground(null);
        view.setClipToOutline(false);
    }

    @Override
    public void close() {
        mClosed = true;
        mView.removeCallbacks(mRetry);
        mView.removeOnAttachStateChangeListener(this);
        synchronized (sLive) { sLive.remove(this); }
    }
    public void onViewAttachedToWindow(View view) { }
    public void onViewDetachedFromWindow(View view) { close(); }
}
