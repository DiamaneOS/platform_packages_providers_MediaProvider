/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Consumer;

/** A streaming, child-first walk never follows shared-storage links. */
final class ThumbnailSweeper implements AutoCloseable {
    interface Rows { Long generation(long id, int user) throws IOException; }
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Rows rows;
    private final Rows internalRows;
    private final Consumer<File> invalidated;
    private final int user;
    private final String version;
    private SecureDirectoryStream<Path> parent;
    int removed;
    boolean failed;

    private static final class Frame {
        final SecureDirectoryStream<Path> directory;
        final Iterator<Path> entries;
        final Path absolute;
        final Path name;
        final String session;
        Frame(SecureDirectoryStream<Path> directory, Path absolute, Path name, String session)
                throws IOException {
            this.directory = directory;
            try {
                entries = directory.iterator();
            } catch (RuntimeException failure) {
                directory.close();
                throw failure;
            }
            this.absolute = absolute;
            this.name = name;
            this.session = session;
        }
    }

    ThumbnailSweeper(File volume, String kind, int user, String version, Rows rows, Rows internalRows,
            Consumer<File> invalidated) throws IOException {
        this.rows = rows;
        this.internalRows = internalRows;
        this.invalidated = invalidated;
        this.user = user;
        this.version = version;
        final File root = ThumbnailCacheFiles.root(volume, kind, user, version);
        final Path name = root.toPath().getFileName();
        parent = openDirectory(volume, root.toPath().getParent());
        try {
            frames.push(new Frame(parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS),
                    root.toPath(), name, null));
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static SecureDirectoryStream<Path> openDirectory(File volume, Path target)
            throws IOException {
        final Path aboveVolume = volume.toPath().toAbsolutePath().normalize().getParent();
        final DirectoryStream<Path> stream = Files.newDirectoryStream(aboveVolume);
        if (!(stream instanceof SecureDirectoryStream)) {
            stream.close();
            throw new IOException("Directory-relative cache cleanup unavailable");
        }
        SecureDirectoryStream<Path> directory = (SecureDirectoryStream<Path>) stream;
        try {
            for (Path part : aboveVolume.relativize(target.toAbsolutePath().normalize())) {
                final SecureDirectoryStream<Path> next = directory.newDirectoryStream(
                        part, LinkOption.NOFOLLOW_LINKS);
                directory.close();
                directory = next;
            }
            return directory;
        } catch (IOException | RuntimeException failure) {
            directory.close();
            throw failure;
        }
    }

    static void unlinkExact(File volume, String kind, int user, String session, long id,
            long generation, Consumer<File> invalidated) throws IOException {
        final Path target = ThumbnailCacheFiles.root(volume, kind, user, "vm-pixels-v4-u")
                .toPath().resolve(session);
        final Path name = Path.of(id + "." + generation);
        try (SecureDirectoryStream<Path> directory = openDirectory(volume, target)) {
            if (!directory.getFileAttributeView(name, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile()) {
                throw new IOException("Unexpected exact thumbnail file type");
            }
            directory.deleteFile(name);
            invalidated.accept(target.resolve(name).toFile());
        } catch (java.nio.file.NoSuchFileException absent) {
            // An exact deletion can be replayed after a successful unlink.
        }
    }

    boolean step(int limit) throws IOException {
        while (limit-- > 0 && !frames.isEmpty()) {
            final Frame frame = frames.peek();
            if (!frame.entries.hasNext()) {
                frame.directory.close();
                frames.pop();
                final SecureDirectoryStream<Path> above = frames.isEmpty() ? parent : frames.peek().directory;
                try {
                    above.deleteDirectory(frame.name);
                    removed++;
                    invalidated.accept(frame.absolute.toFile());
                } catch (java.nio.file.DirectoryNotEmptyException retained) {
                    // Live files and active encoder temporaries keep their directory.
                } catch (java.nio.file.NoSuchFileException gone) {
                    // A replay can find a directory already removed.
                } catch (IOException failure) { failed = true; }
                continue;
            }
            final Path name = frame.entries.next().getFileName();
            final Path absolute = frame.absolute.resolve(name);
            final BasicFileAttributes attributes;
            try {
                attributes = frame.directory.getFileAttributeView(name,
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            } catch (java.nio.file.NoSuchFileException gone) { continue; }
            if (attributes.isDirectory()) {
                if (frames.size() >= 4) { failed = true; continue; }
                final String session = frame.session == null ? name.toString() : frame.session;
                try {
                    frames.push(new Frame(frame.directory.newDirectoryStream(name,
                            LinkOption.NOFOLLOW_LINKS), absolute, name, session));
                } catch (java.nio.file.NoSuchFileException gone) { }
                continue;
            }
            if (!attributes.isRegularFile() || !remove(frame, name.toString())) continue;
            try {
                frame.directory.deleteFile(name);
                removed++;
                invalidated.accept(absolute.toFile());
            } catch (java.nio.file.NoSuchFileException gone) {
                // Only a successful unlink contributes to the removed count.
            } catch (IOException failure) { failed = true; }
        }
        return !frames.isEmpty();
    }

    private boolean remove(Frame frame, String name) throws IOException {
        final boolean temporary = name.startsWith("thumb") && name.endsWith(".tmp");
        if (version.equals("vm-pixels-v2-u")) return temporary || name.matches("[0-9]+\\.jpe?g");
        if (version.equals("vm-pixels-v3-u")) {
            return temporary || name.equals(".database_uuid") || name.matches("[0-9]+\\.[0-9]+");
        }
        if (frame.session == null) return false;
        // Only the custody holder writes, so an untagged or foreign temporary belongs to a dead process.
        if (temporary) return !name.startsWith(ThumbnailCacheFiles.TEMPORARY);
        final ThumbnailCacheFiles.Session internal = ThumbnailCacheFiles.session(false);
        final ThumbnailCacheFiles.Session external = ThumbnailCacheFiles.session(true);
        // An unread or unopened database cannot prove its UUID directories are obsolete.
        if ((!internal.databaseBound
                && frame.session.startsWith(ThumbnailCacheFiles.INTERNAL_DATABASE_PREFIX))
                || (!external.databaseBound
                && frame.session.startsWith(ThumbnailCacheFiles.EXTERNAL_DATABASE_PREFIX))) return false;
        final boolean current = frame.session.equals(external.name)
                || frame.session.equals(internal.name);
        if (!name.matches("[0-9]+\\.[0-9]+")) return false;
        if (!current) return true;
        final Rows lookup = frame.session.equals(internal.name) ? internalRows : rows;
        if (lookup == null) return false;
        final int dot = name.indexOf('.');
        try {
            final long id = Long.parseLong(name.substring(0, dot));
            final long generation = Long.parseLong(name.substring(dot + 1));
            final Long live = lookup.generation(id, user);
            return live == null || generation < live;
        } catch (NumberFormatException invalid) { return false; }
    }

    @Override public void close() {
        while (!frames.isEmpty()) {
            try { frames.pop().directory.close(); } catch (IOException ignored) { }
        }
        if (parent != null) try { parent.close(); } catch (IOException ignored) { }
    }
}
