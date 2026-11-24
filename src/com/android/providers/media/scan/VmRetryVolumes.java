/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.scan;

import android.content.Context;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.UserCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Retry identities bind a relative path to a user lifetime and a stable mounted volume. */
final class VmRetryVolumes {
    static final class Resolved {
        final File file;
        final boolean retired;
        Resolved(File file, boolean retired) { this.file = file; this.retired = retired; }
    }
    private final Context context;
    VmRetryVolumes(Context context) { this.context = context; }
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
    private static boolean mounted(StorageVolume volume) {
        return Environment.MEDIA_MOUNTED.equals(volume.getState())
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState());
    }
    String identify(File file) {
        try {
            final File real = file.getCanonicalFile();
            final StorageVolume volume = FileUtils.getStorageVolume(context, real);
            int id = FileUtils.extractUserId(real.getPath());
            if (id < 0) id = volume.getOwner() == null ? context.getUser().getIdentifier() : volume.getOwner().getIdentifier();
            final UserHandle user = UserHandle.of(id);
            final UserCache users = new UserCache(context);
            if (!users.getUsersCached().contains(user) || !mounted(volume) || volume.getDirectory() == null) return null;
            final long serial = context.getSystemService(UserManager.class).getSerialNumberForUser(user);
            final java.nio.file.Path root = volume.getDirectory().getCanonicalFile().toPath();
            if (serial < 0 || !real.toPath().startsWith(root)) return null;
            final String name = volume.getMediaStoreVolumeName();
            if (name == null) return null;
            return id + ":" + serial + ":" + encode(name) + ":"
                    + encode(String.valueOf(volume.getStorageUuid())) + ":"
                    + encode(root.relativize(real.toPath()).toString());
        } catch (IOException | RuntimeException failure) { return null; }
    }
    Resolved resolve(String identity) {
        try {
            final String[] fields = identity.split(":", -1);
            if (fields.length != 5) return new Resolved(null, true);
            final int id = Integer.parseInt(fields[0]);
            final long serial = Long.parseLong(fields[1]);
            final UserManager manager = context.getSystemService(UserManager.class);
            final long currentSerial = manager.getSerialNumberForUser(UserHandle.of(id));
            if (currentSerial < 0) return new Resolved(null, true);
            if (currentSerial != serial) return new Resolved(null, true);
            final UserCache users = new UserCache(context);
            if (!users.getUsersCached().contains(UserHandle.of(id))) return new Resolved(null, false);
            final Context served = users.getContextForUser(UserHandle.of(id));
            if (!served.getSystemService(UserManager.class).isUserUnlocked()) return new Resolved(null, false);
            final String name = decode(fields[2]);
            final String storage = decode(fields[3]);
            final String relative = decode(fields[4]);
            for (StorageVolume volume : served.getSystemService(StorageManager.class).getStorageVolumes()) {
                if (!name.equals(volume.getMediaStoreVolumeName())
                        || !storage.equals(String.valueOf(volume.getStorageUuid())) || !mounted(volume)
                        || volume.getDirectory() == null) continue;
                final File root = volume.getDirectory().getCanonicalFile();
                final File file = new File(root, relative).getCanonicalFile();
                if (!file.toPath().startsWith(root.toPath())) return new Resolved(null, true);
                try {
                    final BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    return attributes.isRegularFile() ? new Resolved(file, false) : new Resolved(null, true);
                } catch (java.nio.file.NoSuchFileException absent) {
                    return new Resolved(null, root.isDirectory());
                }
            }
            return new Resolved(null, false);
        } catch (IOException | RuntimeException failure) { return new Resolved(null, false); }
    }
}
