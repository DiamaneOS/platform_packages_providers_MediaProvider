/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.scan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded exact scan retries with durable per-attempt revisions. */
final class ScanRetryLedger {
    static final int LIMIT = 256;
    static final class State {
        final LinkedHashMap<String, String> files;
        State(Map<String, String> files) {
            this.files = new LinkedHashMap<>(files);
        }
    }
    interface Store { State read(); void write(State state); }
    private final Store store;
    private State state = new State(Map.of());
    private boolean loaded;
    private final Map<String, String> claimed = new LinkedHashMap<>();

    ScanRetryLedger(Store store) {
        this.store = store;
        try { load(); } catch (RuntimeException unavailable) { }
    }

    private void load() {
        if (loaded) return;
        final State disk = store.read();
        if (disk != null) {
            final Map<String, String> files = new LinkedHashMap<>(disk.files);
            if (files.size() > LIMIT) throw new IllegalStateException();
            for (Map.Entry<String, String> entry : state.files.entrySet()) {
                if (files.size() == LIMIT) break;
                files.putIfAbsent(entry.getKey(), entry.getValue());
            }
            state = new State(files);
        }
        loaded = true;
    }
    private static String token() { return UUID.randomUUID().toString(); }
    private void save(Map<String, String> files) {
        state = new State(files);
        if (loaded) store.write(state);
    }
    synchronized boolean defer(String file) {
        try { load(); } catch (RuntimeException unavailable) { }
        if (file == null || !state.files.containsKey(file) && state.files.size() >= LIMIT) return false;
        if (state.files.containsKey(file) && !claimed.containsKey(file)) return true;
        final Map<String, String> files = new LinkedHashMap<>(state.files);
        files.put(file, token());
        save(files);
        return true;
    }
    synchronized Map<String, String> claim(int limit) {
        load();
        final Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : state.files.entrySet()) {
            if (result.size() >= limit) break;
            result.put(entry.getKey(), entry.getValue());
            claimed.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
    synchronized void finish(String file, String version, boolean complete) {
        claimed.remove(file);
        if (!state.files.containsKey(file)) return;
        final Map<String, String> files = new LinkedHashMap<>(state.files);
        final String current = files.remove(file);
        if (!complete || !version.equals(current)) files.put(file, current);
        save(files);
    }
    synchronized boolean pending() { return !loaded || !state.files.isEmpty(); }
    synchronized void flush() {
        load();
        store.write(state);
    }
}
