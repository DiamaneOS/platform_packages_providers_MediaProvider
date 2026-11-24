/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.scan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.android.providers.media.vmdecode.VmDecode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Durable bounded exact retry survives parser pressure and a provider restart. */
final class VmFillIns {
    private static final long FIRST_MS = 5_000;
    private static final long MAX_MS = 10 * 60_000;
    private static final ThreadLocal<Boolean> THREAD = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> DEFERRED = ThreadLocal.withInitial(() -> false);
    private final Predicate<File> scan;
    private final VmRetryVolumes volumes;
    private final ScanRetryLedger ledger;
    private final LinkedHashMap<File, Integer> mFiles = new LinkedHashMap<>();
    private Handler handler;
    private boolean scheduled;
    private boolean running;
    private boolean answered;
    private long wait = FIRST_MS;
    private long nextExact;
    private final Runnable task = this::run;
    VmFillIns(Context context, Predicate<File> scan) {
        this(scan, null, new VmRetryVolumes(context), new Preferences(context));
    }
    VmFillIns(Predicate<File> scan) { this(scan, null); }
    VmFillIns(Predicate<File> scan, Handler handler) {
        this(scan, handler, null, new ScanRetryLedger.Store() {
            ScanRetryLedger.State state = new ScanRetryLedger.State(Map.of());
            public ScanRetryLedger.State read() { return state; }
            public void write(ScanRetryLedger.State next) { state = next; }
        });
    }
    private VmFillIns(Predicate<File> scan, Handler handler, VmRetryVolumes volumes, ScanRetryLedger.Store store) {
        this.scan = scan;
        this.handler = handler;
        this.volumes = volumes;
        ledger = new ScanRetryLedger(store);
        if (ledger.pending()) start();
    }
    static boolean isFillInThread() { return THREAD.get(); }
    static void deferred() { DEFERRED.set(true); }
    static boolean wasDeferred() { return DEFERRED.get(); }
    static void clearOutcome() { DEFERRED.set(false); }

    void add(File file) {
        deferred();
        if (isFillInThread()) return;
        try {
            final String identity = volumes == null ? file.getPath() : volumes.identify(file);
            if (!ledger.defer(identity)) {
                Log.d("VmFillIns", "File left for idle or mount scan");
                return;
            }
            synchronized (this) { if (mFiles.size() < ScanRetryLedger.LIMIT) mFiles.put(file, 0); }
        } catch (RuntimeException failure) { Log.w("VmFillIns", "Retry persistence deferred"); }
        finally { if (ledger.pending()) start(); }
    }
    private synchronized void start() {
        if (handler == null) {
            final HandlerThread thread = new HandlerThread("VmFillIns", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
            VmDecode.addAnswerListener(this::answered);
        }
        schedule(wait);
    }
    private synchronized void schedule(long delay) {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(task, delay);
    }
    private synchronized void answered() {
        if (running) { answered = true; return; }
        if (handler == null || !ledger.pending()) return;
        handler.removeCallbacks(task);
        scheduled = false;
        schedule(0);
    }
    void run() {
        synchronized (this) { scheduled = false; running = true; answered = false; }
        THREAD.set(true);
        boolean progressed = false;
        try {
            ledger.flush();
            final long now = android.os.SystemClock.uptimeMillis();
            final Map<String, String> attempts = now >= nextExact ? ledger.claim(8) : Map.of();
            for (Map.Entry<String, String> entry : attempts.entrySet()) {
                final VmRetryVolumes.Resolved resolved = volumes == null
                        ? new VmRetryVolumes.Resolved(new File(entry.getKey()), false) : volumes.resolve(entry.getKey());
                boolean done = resolved.retired;
                if (resolved.file != null) {
                    clearOutcome();
                    try { done = scan.test(resolved.file) && !wasDeferred(); }
                    catch (RuntimeException failure) { done = false; }
                }
                ledger.finish(entry.getKey(), entry.getValue(), done);
                progressed |= done;
                if (done && resolved.file != null) synchronized (this) { mFiles.remove(resolved.file); }
            }
            if (!attempts.isEmpty()) nextExact = now + (progressed ? 20 : FIRST_MS);
        } catch (RuntimeException failure) {
            Log.w("VmFillIns", "Retry pass deferred: " + failure.getClass().getSimpleName());
        } finally {
            THREAD.remove();
            DEFERRED.remove();
            synchronized (this) {
                running = false;
                if (!ledger.pending()) wait = FIRST_MS;
                else if (progressed) { wait = FIRST_MS; schedule(20); }
                else if (answered) { wait = FIRST_MS; schedule(FIRST_MS); }
                else { wait = Math.min(wait * 2, MAX_MS); schedule(wait); }
            }
        }
    }
    private static final class Preferences implements ScanRetryLedger.Store {
        final Context context;
        Preferences(Context context) { this.context = context; }
        private SharedPreferences preferences() {
            return context.getSharedPreferences("diamaneos_vm_scan_retries", Context.MODE_PRIVATE);
        }
        public ScanRetryLedger.State read() {
            final SharedPreferences prefs = preferences();
            final Map<String, String> files = new LinkedHashMap<>();
            for (String entry : prefs.getStringSet("files", Set.of())) {
                final int dot = entry.indexOf('.');
                if (dot <= 0) throw new IllegalStateException();
                final String identity = new String(Base64.getUrlDecoder().decode(entry.substring(dot + 1)), StandardCharsets.UTF_8);
                if (identity.length() > 8192) throw new IllegalStateException();
                files.put(identity, entry.substring(0, dot));
            }
            return new ScanRetryLedger.State(files);
        }
        public void write(ScanRetryLedger.State state) {
            final SharedPreferences prefs = preferences();
            final Set<String> files = new HashSet<>();
            state.files.forEach((identity, revision) -> files.add(revision + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(identity.getBytes(StandardCharsets.UTF_8))));
            if (!prefs.edit().putStringSet("files", files).remove("broad").commit()) {
                throw new IllegalStateException("Retry storage unavailable");
            }
        }
    }
}
