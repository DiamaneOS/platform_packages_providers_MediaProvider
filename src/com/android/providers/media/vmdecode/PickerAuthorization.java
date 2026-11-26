/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** A stalled full URI permission check keeps its provider admission until it actually returns. */
final class PickerAuthorization {
    private static final SourceBudget CLOUD_BUDGET = new SourceBudget();
    private static final SourceBudget LOCAL_BUDGET = new SourceBudget();
    private static final ThreadPoolExecutor CLOUD_WORK = pool(4);
    private static final ThreadPoolExecutor LOCAL_WORK = pool(2);

    private static ThreadPoolExecutor pool(int workers) {
        return new ThreadPoolExecutor(0, workers, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), job -> {
                final Thread thread = new Thread(job, "VmPickerAuthorization");
                thread.setDaemon(true);
                return thread;
            });
    }

    static void submit(IntSupplier providerUid, Runnable check, Runnable accepted,
            Runnable busy, Runnable denied) {
        submit(false, providerUid, check, accepted, busy, denied);
    }

    static void submit(boolean local, IntSupplier providerUid, Runnable check, Runnable accepted,
            Runnable busy, Runnable denied) {
        final ThreadPoolExecutor work = local ? LOCAL_WORK : CLOUD_WORK;
        final SourceBudget budget = local ? LOCAL_BUDGET : CLOUD_BUDGET;
        try {
            work.execute(() -> {
                try {
                    final int uid = providerUid.getAsInt();
                    if (uid < 0) { busy.run(); return; }
                    try (SourceBudget.Ticket ticket = budget.reserve(Integer.toString(uid), 0)) {
                        if (ticket == null) { busy.run(); return; }
                        check.run();
                    }
                } catch (SecurityException | IllegalArgumentException failure) {
                    denied.run();
                    return;
                } catch (RuntimeException failure) {
                    busy.run();
                    return;
                }
                accepted.run();
            });
        } catch (RuntimeException failure) { busy.run(); }
    }
}
