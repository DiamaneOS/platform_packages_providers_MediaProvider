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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A queue per owner (the package that supplied the bytes), served in turn. One owner can queue at most
 * {@code perOwnerQueued} items, and while other owners wait, an owner already running {@code perOwnerRunning} items
 * is passed over. So one app's flood of slow or large files cannot hold up everyone else's. Plain Java, tested off the
 * device too.
 */
final class FairQueue<T> {
    private final int mPerOwnerQueued;
    private final int mPerOwnerRunning;
    // Insertion order is the turn order: an owner that was served moves to the end.
    private final LinkedHashMap<String, ArrayDeque<T>> mQueued = new LinkedHashMap<>();
    private final Map<String, Integer> mRunning = new HashMap<>();

    FairQueue(int perOwnerQueued, int perOwnerRunning) {
        mPerOwnerQueued = perOwnerQueued;
        mPerOwnerRunning = perOwnerRunning;
    }

    /** Queues {@code item} for {@code owner}, or returns false when that owner's queue is full. */
    synchronized boolean offer(String owner, T item) {
        final ArrayDeque<T> queue = mQueued.computeIfAbsent(owner, k -> new ArrayDeque<>());
        if (queue.size() >= mPerOwnerQueued) {
            if (queue.isEmpty()) {
                mQueued.remove(owner);
            }
            return false;
        }
        queue.addLast(item);
        notifyAll();
        return true;
    }

    /** Takes {@code item} back if it has not started. Returns false when a worker already took it. */
    synchronized boolean remove(String owner, T item) {
        final ArrayDeque<T> queue = mQueued.get(owner);
        if (queue == null || !queue.remove(item)) {
            return false;
        }
        if (queue.isEmpty()) {
            mQueued.remove(owner);
        }
        return true;
    }

    /** The next item, in turn, without waiting. Null when nothing is queued. Counts it as running. */
    synchronized Owned<T> poll() {
        String pick = null;
        for (Map.Entry<String, ArrayDeque<T>> e : mQueued.entrySet()) {
            if (mRunning.getOrDefault(e.getKey(), 0) < mPerOwnerRunning) {
                pick = e.getKey();
                break;
            }
        }
        if (pick == null) {
            // Every waiting owner is at its share: stay busy anyway, in turn.
            final Iterator<String> it = mQueued.keySet().iterator();
            if (!it.hasNext()) {
                return null;
            }
            pick = it.next();
        }
        final ArrayDeque<T> queue = mQueued.remove(pick);
        final T item = queue.removeFirst();
        if (!queue.isEmpty()) {
            mQueued.put(pick, queue);
        }
        mRunning.merge(pick, 1, Integer::sum);
        return new Owned<>(pick, item);
    }

    /** Waits for the next item. */
    synchronized Owned<T> take() throws InterruptedException {
        Owned<T> next;
        while ((next = poll()) == null) {
            wait();
        }
        return next;
    }

    /** An item of {@code owner} finished. */
    synchronized void done(String owner) {
        final int left = mRunning.getOrDefault(owner, 0) - 1;
        if (left <= 0) {
            mRunning.remove(owner);
        } else {
            mRunning.put(owner, left);
        }
        notifyAll();
    }

    synchronized int queued(String owner) {
        final ArrayDeque<T> queue = mQueued.get(owner);
        return queue == null ? 0 : queue.size();
    }

    synchronized int running(String owner) {
        return mRunning.getOrDefault(owner, 0);
    }

    /** An item and its owner. */
    static final class Owned<T> {
        final String owner;
        final T item;

        Owned(String owner, T item) {
            this.owner = owner;
            this.item = item;
        }
    }
}
