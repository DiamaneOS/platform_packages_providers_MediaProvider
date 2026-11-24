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

import android.content.Context;
import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.util.Size;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.UserCache;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Source identity comes from an authenticated media row or the selected profile's provider. */
public final class VmMediaRows {
    public static final int AUDIO = 1;
    public static final int VIDEO = 2;
    public static final int IMAGE = 3;
    private static final String[] PROJECTION = {"_data", "owner_package_name", "_user_id",
            "generation_modified", "mime_type", "_display_name"};
    private static final String CACHE = "vm-pixels-v2-u";

    public interface Query {
        Cursor run(Uri uri, String[] projection, String selection, String[] arguments,
                CancellationSignal signal) throws FileNotFoundException;
    }

    public static final class Source {
        public final String path;
        public final String principal;
        public final int user;
        public final long generation;
        public final String mimeType;
        public final String displayName;
        final Uri rowUri;
        Source(String path, String principal, int user, long generation, Uri rowUri,
                String mimeType, String displayName) {
            this.path = path;
            this.principal = principal;
            this.user = user;
            this.generation = generation;
            this.rowUri = rowUri;
            this.mimeType = mimeType;
            this.displayName = displayName;
        }
        boolean same(Source other) {
            return other != null && user == other.user && generation == other.generation
                    && Objects.equals(path, other.path) && Objects.equals(principal, other.principal);
        }
    }

    static Source row(Query query, Uri uri, CancellationSignal signal) throws IOException {
        try (Cursor cursor = query.run(uri, PROJECTION, null, null, signal)) {
            if (cursor == null || cursor.getCount() != 1 || !cursor.moveToFirst()
                    || cursor.isNull(0) || cursor.isNull(2)) {
                throw new FileNotFoundException("No authenticated media row");
            }
            final int user = cursor.getInt(2);
            if (user < 0) throw new FileNotFoundException("No media source user");
            return new Source(cursor.getString(0), cursor.getString(1), user, cursor.getLong(3), uri,
                    cursor.getString(4), cursor.getString(5));
        }
    }

    public static Bitmap thumbnail(Query query, Uri uri, Size size, CancellationSignal signal, int type)
            throws IOException {
        final Source source = row(query, uri, signal);
        try (VmDecode.UserScope user = VmDecode.forUser(source.user)) {
            VmDecode.prepareForUser(source.user);
            switch (type) {
                case AUDIO: return VmDecode.audioThumbnail(new File(source.path), size, source.principal, signal);
                case VIDEO: return VmDecode.videoThumbnail(new File(source.path), size, source.principal, signal);
                case IMAGE: return VmDecode.imageThumbnail(new File(source.path), size, source.principal, signal);
                default: throw new IOException("Unknown media thumbnail type");
            }
        }
    }

    public static File cacheFile(Query query, Uri uri, UserCache users, String directory) throws IOException {
        final Source source = row(query, uri, null);
        final Context context = users.getContextForUser(UserHandle.of(source.user));
        final File storage = context.getSystemService(StorageManager.class).getPrimaryStorageVolume().getDirectory();
        if (storage == null) throw new IOException("No media thumbnail volume");
        return FileUtils.buildPath(storage, directory, ".thumbnails", CACHE + source.user,
                ContentUris.parseId(uri) + ".jpg");
    }

    public static List<File> cacheDirectories(File volume, List<UserHandle> users) {
        final List<File> directories = new ArrayList<>();
        for (UserHandle user : users) {
            for (String kind : new String[] {Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES,
                    Environment.DIRECTORY_PICTURES}) {
                directories.add(FileUtils.buildPath(volume, kind, ".thumbnails", CACHE + user.getIdentifier()));
            }
        }
        return directories;
    }

    public static Source forDisplay(android.content.ContentResolver resolver, Uri uri,
            CancellationSignal signal) throws IOException {
        return row((address, columns, selection, args, cancel) ->
                resolver.query(address, columns, selection, args, null, cancel), uri, signal);
    }

    static int uriUser(Context context, Uri uri) {
        if (uri.getUserInfo() == null) return context.getUser().getIdentifier();
        try { return Integer.parseInt(uri.getUserInfo()); }
        catch (NumberFormatException e) { throw new SecurityException("Invalid picker source user"); }
    }

    static Source picker(Context context, Uri uri) throws IOException {
        final int user = uriUser(context, uri);
        if (!VmMediaUsers.serves(context, user)) throw new SecurityException("Unserved picker source user");
        if (PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(uri.getHost())) {
            final long id;
            try { id = Long.parseLong(uri.getLastPathSegment()); }
            catch (RuntimeException e) { throw new IOException("Invalid picker media id", e); }
            final Uri item = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id).buildUpon()
                    .authority(user + "@" + MediaStore.AUTHORITY).build();
            final Source source = row((address, columns, selection, args, signal) ->
                    context.getContentResolver().query(address, columns, selection, args, null, signal), item, null);
            if (!VmMediaUsers.serves(context, source.user)) throw new SecurityException("Unserved media row user");
            return source;
        }
        final ProviderInfo provider = VmMediaUsers.contextForUser(context, user)
                .getPackageManager().resolveContentProvider(uri.getHost(), 0);
        if (provider == null || provider.packageName == null) throw new IOException("No selected media provider");
        return new Source(null, provider.packageName, user, -1, null, null, null);
    }

    static void verify(Context context, Uri uri, Source source) throws IOException {
        if (!source.same(picker(context, uri))) {
            throw new VmDecodeUnavailableException("Media row changed during source open");
        }
    }
}
