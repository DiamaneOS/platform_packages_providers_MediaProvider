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

import java.util.HashMap;
import java.util.Map;

/** Counts all unfinished source work, including work whose waiter has already left. */
final class SourceBudget {
    private static final int PER_USER = 2;
    private static final int GLOBAL = 8;
    private static final long USER_BYTES = 64L * 1024 * 1024;
    private static final long GLOBAL_BYTES = 128L * 1024 * 1024;
    private final Map<String, long[]> mUsers = new HashMap<>();
    private int mCount;
    private long mBytes;

    synchronized Ticket reserve(String principal, long bytes) {
        final String key = principal == null ? "" : principal;
        final long[] state = mUsers.get(key);
        if (bytes < 0 || bytes > GLOBAL_BYTES - mBytes || mCount >= GLOBAL
                || (state == null ? bytes > USER_BYTES
                        : state[0] >= PER_USER || bytes > USER_BYTES - state[1])) {
            return null;
        }
        final long[] current = state == null ? new long[2] : state;
        ++current[0];
        current[1] += bytes;
        mUsers.put(key, current);
        ++mCount;
        mBytes += bytes;
        return new Ticket(key, bytes);
    }

    final class Ticket implements AutoCloseable {
        private final String mPrincipal;
        private final long mReserved;
        private boolean mClosed;

        Ticket(String principal, long bytes) {
            mPrincipal = principal;
            mReserved = bytes;
        }

        @Override
        public void close() {
            synchronized (SourceBudget.this) {
                if (mClosed) return;
                mClosed = true;
                final long[] state = mUsers.get(mPrincipal);
                if (--state[0] == 0) mUsers.remove(mPrincipal);
                else state[1] -= mReserved;
                --mCount;
                mBytes -= mReserved;
            }
        }
    }
}
