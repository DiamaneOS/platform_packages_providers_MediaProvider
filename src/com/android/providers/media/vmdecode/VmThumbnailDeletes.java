/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.ThumbnailDentries;
import com.android.providers.media.util.UserCache;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Committed SQL intent is drained in bounded filesystem turns outside a writer. */
public final class VmThumbnailDeletes implements AutoCloseable {
    private static final String[] VERSIONS = {"vm-pixels-v2-u", "vm-pixels-v3-u", "vm-pixels-v4-u"};
    private static FileChannel custody;
    private static FileLock lease;
    private static VmThumbnailDeletes active;
    private static Handler handler;
    private static volatile SQLiteDatabase internalDatabase;
    private final Context context;
    private final SQLiteDatabase database;
    private final ArrayDeque<Root> roots = new ArrayDeque<>();
    private final Set<Root> retryRoots = new HashSet<>();
    private final Map<Integer, StorageVolume> available = new HashMap<>();
    private final Set<Integer> unavailable = new HashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private String revision;
    private String coverageRevision;
    private final ThumbnailCacheFiles.Session session = ThumbnailCacheFiles.session(true);
    private boolean reconcile = true;
    private boolean dirty;
    private boolean retryExact;
    private ThumbnailSweeper walker;
    private Root walking;
    private boolean queued;
    private boolean refresh;
    private final Runnable task = this::run;
    private volatile boolean closed;
    private int removed;

    private record Root(String kind, String version, int user) {}
    private record Entry(int user, long id, long generation, String revision) {}

    public static synchronized VmThumbnailDeletes open(Context context, SQLiteDatabase database,
            boolean external) {
        final boolean held = custody(context);
        ThumbnailCacheFiles.writerReady(held);
        if (external && active != null) active.close();
        String databaseUuid = null;
        try {
            databaseUuid = DatabaseHelper.getOrCreateUuid(database);
        } catch (RuntimeException unavailable) {
            android.util.Log.w("VmThumbnailCleanup", "Database UUID unavailable for thumbnail cache");
        }
        ThumbnailCacheFiles.opened(external, databaseUuid);
        if (!external) {
            internalDatabase = database;
            return null;
        }
        if (handler == null) {
            final HandlerThread thread = new HandlerThread("VmThumbnailCleanup",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        active = new VmThumbnailDeletes(context, database);
        active.wake();
        return active;
    }

    public static synchronized void idle() {
        if (active != null) active.reconcile();
    }

    private static synchronized boolean custody(Context context) {
        if (sharedClone(context)) return false;
        if (lease != null && lease.isValid()) return true;
        try {
            final android.content.pm.ProviderInfo producer = context.getPackageManager()
                    .resolveContentProvider("media", 0);
            if (producer == null || producer.applicationInfo == null
                    || producer.applicationInfo.uid != android.os.Process.myUid()) return false;
        } catch (RuntimeException unavailable) { return false; }
        try {
            final File file = new File(context.getFilesDir(), "diamaneos-thumbnail-custody");
            final FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
            final FileLock held = channel.tryLock();
            if (held == null) { channel.close(); return false; }
            custody = channel;
            lease = held;
            return true;
        } catch (IOException | RuntimeException failure) { return false; }
    }

    // A media-sharing clone's storage root is its parent's, swept by the parent user's provider: a provider started in
    // the clone never takes custody of it. Unknown counts as a clone.
    private static boolean sharedClone(Context context) {
        try {
            final UserManager users = context.getSystemService(UserManager.class);
            return users.isCloneProfile() && users.getUserProperties(context.getUser()).isMediaSharedWithParent();
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private VmThumbnailDeletes(Context context, SQLiteDatabase database) {
        this.context = context;
        this.database = database;
    }

    public synchronized void wake() {
        if (closed) return;
        refresh = true;
        if (queued) handler.removeCallbacks(task);
        queued = true;
        handler.post(task);
    }

    private synchronized void reconcile() {
        reconcile = true;
        wake();
    }

    private synchronized void later(long delay) {
        if (!closed && !queued) {
            queued = true;
            handler.postDelayed(task, delay);
        }
    }

    private boolean begin() {
        synchronized (this) { refresh = false; }
        available.clear();
        entries.clear();
        removed = 0;
        final String currentRevision;
        try (Cursor cursor = database.rawQuery("SELECT hex(revision),dirty FROM "
                + ThumbnailDeleteQueue.STATE + " WHERE _id=1", null)) {
            if (!cursor.moveToFirst()) return false;
            currentRevision = cursor.getString(0);
            dirty = cursor.getInt(1) != 0;
        }
        try (Cursor cursor = database.rawQuery("SELECT user_id,media_id,generation,hex(revision) FROM "
                + ThumbnailDeleteQueue.TABLE + " LIMIT 256", null)) {
            while (cursor.moveToNext()) entries.add(new Entry(cursor.getInt(0),
                    cursor.getLong(1), cursor.getLong(2), cursor.getString(3)));
        }
        final boolean scan;
        synchronized (this) {
            scan = reconcile || (dirty && !currentRevision.equals(coverageRevision));
            reconcile = false;
        }
        final boolean coverage = scan || !retryRoots.isEmpty() || !unavailable.isEmpty();
        if (!coverage && entries.isEmpty()) return false;
        final Set<Integer> previouslyUnavailable = Set.copyOf(unavailable);
        unavailable.clear();
        if (scan) retryRoots.clear();
        revision = currentRevision;
        retryExact = false;
        final UserCache users = new UserCache(context);
        for (UserHandle user : users.getUsersCached()) {
            final int id = user.getIdentifier();
            try {
                final StorageVolume primary = primary(users, user);
                if (!mounted(primary)) { unavailable.add(id); continue; }
                available.put(id, primary);
                if (coverage) for (String kind : ThumbnailCacheFiles.KINDS) for (String version : VERSIONS) {
                    final Root root = new Root(kind, version, id);
                    if (retryRoots.remove(root) || scan || previouslyUnavailable.contains(id)) roots.add(root);
                }
            } catch (RuntimeException failure) { unavailable.add(id); }
        }
        final UserManager profiles = context.getSystemService(UserManager.class);
        for (UserHandle user : profiles.getUserProfiles()) {
            if (!available.containsKey(user.getIdentifier()) && profiles.getUserProperties(user)
                    .isMediaSharedWithParent()) unavailable.add(user.getIdentifier());
        }
        retryRoots.removeIf(root -> !available.containsKey(root.user) && !unavailable.contains(root.user)
                && profiles.getSerialNumberForUser(UserHandle.of(root.user)) < 0);
        drainExact(profiles);
        if (scan && !available.isEmpty()) coverageRevision = currentRevision;
        if (!coverage) unavailable.clear();
        return true;
    }

    private static StorageVolume primary(UserCache users, UserHandle user) {
        return users.getContextForUser(user).getSystemService(StorageManager.class)
                .getPrimaryStorageVolume();
    }

    private static boolean mounted(StorageVolume volume) {
        final File path = volume.getDirectory();
        return android.os.Environment.MEDIA_MOUNTED.equals(volume.getState())
                && path != null && path.isDirectory();
    }

    private boolean stillMounted(int user) {
        try {
            final StorageVolume checked = primary(new UserCache(context), UserHandle.of(user));
            final StorageVolume expected = available.get(user);
            return mounted(checked) && checked.equals(expected)
                    && Objects.equals(checked.getStorageUuid(), expected.getStorageUuid());
        } catch (RuntimeException failure) { return false; }
    }

    private Long generation(long id, int user) { return generation(database, id, user); }

    private static Long generation(SQLiteDatabase database, long id, int user) {
        try (Cursor cursor = database.rawQuery("SELECT generation_modified FROM files"
                + " WHERE _id=? AND _user_id=?", new String[] {Long.toString(id), Integer.toString(user)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : null;
        }
    }

    private void drainExact(UserManager users) {
        for (Entry entry : entries) {
            if (closed) return;
            try {
                final StorageVolume primary = available.get(entry.user);
                if (primary == null) {
                    if (unavailable.contains(entry.user)
                            || users.getSerialNumberForUser(UserHandle.of(entry.user)) >= 0) {
                        retryExact = true;
                        continue;
                    }
                } else if (!Long.valueOf(entry.generation).equals(generation(entry.id, entry.user))) {
                    for (String kind : ThumbnailCacheFiles.KINDS) {
                        ThumbnailSweeper.unlinkExact(primary.getDirectory(), kind, entry.user, session.name, entry.id,
                                entry.generation, file -> ThumbnailDentries.deleted(context, file));
                    }
                    if (!stillMounted(entry.user)) { retryExact = true; continue; }
                }
                if (!closed) ThumbnailDeleteQueue.acknowledge(database, entry.user, entry.id, entry.revision);
            } catch (IOException | RuntimeException failure) { retryExact = true; }
        }
    }

    private void finish() {
        if (dirty) for (int user : available.keySet()) {
            if (!stillMounted(user)) unavailable.add(user);
        }
        if (dirty && unavailable.isEmpty() && retryRoots.isEmpty() && !available.isEmpty()) {
            ThumbnailDeleteQueue.acknowledgeCoverage(database, revision);
        }
        android.util.Log.d("VmThumbnailCleanup", "Removed cache entries: " + removed);
        revision = null;
        synchronized (this) {
            if (refresh) later(0);
            else if (retryExact || !retryRoots.isEmpty() || !unavailable.isEmpty()
                    || (dirty && available.isEmpty())) later(60_000);
        }
    }

    private void run() {
        synchronized (this) { queued = false; }
        if (closed) return;
        if (!custody(context)) {
            if (!sharedClone(context)) later(60_000);
            return;
        }
        ThumbnailCacheFiles.writerReady(true);
        try {
            if (revision == null && !begin()) return;
            int left = 64;
            while (left-- > 0 && !closed) {
                if (walker != null) {
                    try {
                        if (walker.step(1)) continue;
                    } catch (IOException | RuntimeException failure) { walker.failed = true; }
                    removed += walker.removed;
                    if (walker.failed) retryRoots.add(walking);
                    walker.close();
                    walker = null;
                }
                walking = roots.poll();
                if (walking == null) { finish(); return; }
                final SQLiteDatabase internal = internalDatabase;
                try {
                    walker = new ThumbnailSweeper(available.get(walking.user).getDirectory(),
                            walking.kind, walking.user, walking.version, this::generation,
                            internal != null && internal.isOpen()
                                    ? (id, user) -> generation(internal, id, user) : null,
                            file -> ThumbnailDentries.deleted(context, file));
                } catch (java.nio.file.NoSuchFileException absent) {
                    if (!stillMounted(walking.user)) retryRoots.add(walking);
                } catch (IOException | RuntimeException failure) { retryRoots.add(walking); }
            }
            later(20);
        } catch (RuntimeException failure) {
            if (walker != null) walker.close();
            walker = null;
            roots.clear();
            revision = null;
            coverageRevision = null;
            android.util.Log.w("VmThumbnailCleanup", "Cache pass deferred: "
                    + failure.getClass().getSimpleName());
            later(60_000);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (handler != null) {
            handler.removeCallbacks(task);
            handler.post(() -> {
                if (walker != null) walker.close();
                walker = null;
                roots.clear();
            });
        }
    }
}
