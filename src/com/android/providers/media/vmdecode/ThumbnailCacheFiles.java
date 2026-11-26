/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.UUID;

/** Immutable cache identities isolate an encoder from a reopened or restored database. */
public final class ThumbnailCacheFiles {
    static final String[] KINDS = {"Music", "Movies", "Pictures"};
    static final String PROCESS = UUID.randomUUID().toString();
    /** Encoder temporaries carry the writing process, so a later process can remove orphans. */
    static final String TEMPORARY = "thumb" + PROCESS + "-";
    static final String EXTERNAL_DATABASE_PREFIX = "db-e-";
    static final String INTERNAL_DATABASE_PREFIX = "db-i-";
    private static long opens;
    private static volatile boolean writerReady = true;
    static void writerReady(boolean ready) { writerReady = ready; }
    private static volatile Session external = new Session(true, 0, null);
    private static volatile Session internal = new Session(false, 0, null);

    static final class Session {
        final boolean external;
        final boolean databaseBound;
        final String name;
        Session(boolean external, long number, String uuid) {
            this.external = external;
            databaseBound = uuid != null && uuid.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            name = databaseBound ? (external ? EXTERNAL_DATABASE_PREFIX : INTERNAL_DATABASE_PREFIX) + uuid
                    : PROCESS + (external ? "-e" : "-i") + number;
        }
    }

    static synchronized Session opened(boolean isExternal, String databaseUuid) {
        // Keep the fresh object fence even when the stock UUID lets the directory survive.
        final Session next = new Session(isExternal, ++opens, databaseUuid);
        if (isExternal) external = next;
        else internal = next;
        return next;
    }

    static Session session(boolean isExternal) { return isExternal ? external : internal; }

    private ThumbnailCacheFiles() { }

    static File root(File volume, String kind, int user, String version) throws IOException {
        if (user < 0 || !List.of(KINDS).contains(kind)) throw new IOException("Invalid cache root");
        final File root = new File(new File(new File(volume, kind), ".thumbnails"), version + user);
        contained(volume, root);
        return root;
    }

    static File file(File volume, String kind, int user, long id, long generation) throws IOException {
        return file(volume, kind, user, id, generation, external);
    }

    static File file(File volume, String kind, int user, long id, long generation, Session session)
            throws IOException {
        if (user < 0 || id < 0 || generation < 0) throw new IOException("Invalid thumbnail identity");
        if (!writerReady || session != session(session.external)) {
            throw new VmDecodeUnavailableException("Thumbnail database reopened");
        }
        final File directory = new File(root(volume, kind, user, "vm-pixels-v4-u"), session.name);
        contained(volume, directory);
        return new File(directory, id + "." + generation);
    }

    /** The pinned stock caller holds a writer and must perform no cache filesystem work. */
    static List<File> directories(File volume, int user) { return List.of(); }

    static void contained(File volume, File candidate) throws IOException {
        final java.nio.file.Path base = volume.toPath().toAbsolutePath().normalize();
        final java.nio.file.Path path = candidate.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(base) || path.equals(base)) throw new IOException("Uncontained cache path");
        java.nio.file.Path check = base;
        if (Files.isSymbolicLink(check)) throw new IOException("Symlinked media volume");
        for (java.nio.file.Path component : base.relativize(path)) {
            check = check.resolve(component);
            if (Files.isSymbolicLink(check)) throw new IOException("Symlinked cache component");
        }
    }

    static boolean regular(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
}
