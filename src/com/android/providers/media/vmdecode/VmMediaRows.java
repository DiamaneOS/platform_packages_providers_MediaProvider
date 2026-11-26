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
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Bundle;
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
    public static final String MEDIA_USER = "de.diamaneos.vmdecode.media_user";
    private static final ThreadLocal<ThumbnailSource> THUMBNAIL = new ThreadLocal<>();

    private static final class ThumbnailSource {
        Source source;
        Uri target;
        boolean gone;
        boolean retained;
        final ThumbnailCacheFiles.Session externalSession = ThumbnailCacheFiles.session(true);
        ThumbnailSource(Source source) { this.source = source; this.target = source == null ? null : source.rowUri; }
        void accept(Source next) throws IOException {
            if (source != null && !source.same(next)) {
                throw new VmDecodeUnavailableException("Thumbnail row changed before publication");
            }
            source = next;
            if (Objects.equals(target, next.rowUri)) gone = false;
        }
    }

    public static AssetFileDescriptor thumbnailAsset(VmThumbnailErrors.Open open)
            throws FileNotFoundException {
        final ThumbnailSource previous = THUMBNAIL.get();
        final ThumbnailSource capture = new ThumbnailSource(null);
        THUMBNAIL.set(capture);
        try {
            final AssetFileDescriptor asset;
            try {
                asset = VmThumbnailErrors.open(open);
            } catch (FileNotFoundException failure) {
                if (capture.gone) return VmThumbnailErrors.gone();
                throw failure;
            }
            if (capture.retained && capture.externalSession != ThumbnailCacheFiles.session(true)) {
                try { asset.close(); } catch (IOException ignored) { }
                return VmThumbnailErrors.later();
            }
            final Bundle extras = asset.getExtras() == null ? new Bundle() : new Bundle(asset.getExtras());
            if (extras.getBoolean(VmThumbnailErrors.LATER)) return asset;
            if (capture.source == null) {
                try { asset.close(); } catch (IOException ignored) { }
                throw new FileNotFoundException("No authenticated thumbnail row");
            }
            extras.putInt(MEDIA_USER, capture.source.user);
            return new AssetFileDescriptor(asset.getParcelFileDescriptor(), asset.getStartOffset(),
                    asset.getDeclaredLength(), extras);
        } finally {
            if (previous == null) THUMBNAIL.remove(); else THUMBNAIL.set(previous);
        }
    }

    public static Runnable thumbnailWork(Runnable work) {
        final ThumbnailSource current = THUMBNAIL.get();
        if (current == null) return work;
        final ThumbnailSource capture = new ThumbnailSource(current.source);
        return () -> {
            final ThumbnailSource previous = THUMBNAIL.get();
            THUMBNAIL.set(capture);
            try { work.run(); }
            finally {
                if (previous == null) THUMBNAIL.remove(); else THUMBNAIL.set(previous);
            }
        };
    }

    public interface Query {
        Cursor run(Uri uri, String[] projection, Bundle arguments, CancellationSignal signal) throws FileNotFoundException;
    }

    public static <T> Cursor asProvider(java.util.function.Supplier<T> clear,
            java.util.function.Consumer<T> restore, java.util.function.Supplier<Cursor> query) {
        final T token = clear.get();
        try { return query.get(); }
        finally { restore.accept(token); }
    }

    public static final class Source {
        public final String path;
        public final String principal;
        public final int user;
        public final long generation;
        public final String mimeType;
        public final String displayName;
        final Uri rowUri;
        final ThumbnailCacheFiles.Session cacheSession;
        Source(String path, String principal, int user, long generation, Uri rowUri,
                String mimeType, String displayName) {
            this.path = path;
            this.principal = principal;
            this.user = user;
            this.generation = generation;
            this.rowUri = rowUri;
            this.mimeType = mimeType;
            this.displayName = displayName;
            this.cacheSession = ThumbnailCacheFiles.session(rowUri == null || rowUri.getPathSegments().isEmpty()
                    || !"internal".equals(rowUri.getPathSegments().get(0)));
        }
        boolean same(Source other) {
            return other != null && cacheSession == other.cacheSession
                    && user == other.user && generation == other.generation
                    && Objects.equals(path, other.path) && Objects.equals(principal, other.principal);
        }
    }

    static Source row(Query query, Uri uri, CancellationSignal signal) throws IOException {
        return row(query, uri, signal, false);
    }

    private static Source row(Query query, Uri uri, CancellationSignal signal, boolean includeDetached)
            throws IOException {
        final ThumbnailSource attempt = THUMBNAIL.get();
        if (attempt != null && attempt.target == null) attempt.target = uri;
        final ThumbnailCacheFiles.Session session = ThumbnailCacheFiles.session(
                uri == null || uri.getPathSegments().isEmpty()
                        || !"internal".equals(uri.getPathSegments().get(0)));
        final Uri collection;
        final String id;
        final String volume;
        try {
            if (!"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getHost())) {
                throw new IllegalArgumentException("Not a media row URI");
            }
            final long rowId = ContentUris.parseId(uri);
            if (rowId < 0) throw new IllegalArgumentException("Negative media row ID");
            id = Long.toString(rowId);
            // The files collection exposes the authenticated row user, item projections do not.
            final Uri unqualified = uri.buildUpon().authority(MediaStore.AUTHORITY).build();
            volume = MediaStore.getVolumeName(unqualified);
            collection = MediaStore.Files.getContentUri(includeDetached
                    ? MediaStore.VOLUME_EXTERNAL : volume).buildUpon()
                    .encodedAuthority(uri.getEncodedAuthority()).build();
        } catch (IllegalArgumentException | UnsupportedOperationException invalid) {
            throw new IOException("Invalid media row URI", invalid);
        }
        final Bundle arguments = new Bundle();
        final boolean namedDetached = includeDetached && !MediaStore.VOLUME_EXTERNAL.equals(volume);
        arguments.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                namedDetached ? "_id=? AND volume_name=?" : "_id=?");
        arguments.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                namedDetached ? new String[] {id, volume} : new String[] {id});
        // Item lookups include pending and trashed rows while retaining provider access checks.
        arguments.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        arguments.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        if (includeDetached) {
            arguments.putBoolean(MediaStore.QUERY_ARG_INCLUDE_RECENTLY_UNMOUNTED_VOLUMES, true);
        }
        try (Cursor cursor = query.run(collection, PROJECTION, arguments, signal)) {
            if (cursor == null) throw new VmDecodeUnavailableException("Media row query unavailable");
            if (session != ThumbnailCacheFiles.session(session.external)) {
                throw new VmDecodeUnavailableException("Thumbnail row crossed a database reopen");
            }
            if (cursor.getCount() == 0) {
                if (includeDetached) throw new VmDecodeUnavailableException("Media row absence uncertain");
                if (attempt != null && Objects.equals(attempt.target, uri)) attempt.gone = true;
                throw new VmMediaGoneException("Media row no longer exists");
            }
            if (cursor.getCount() != 1 || !cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(2)) {
                throw new FileNotFoundException("No authenticated media row");
            }
            final int user = cursor.getInt(2);
            if (user < 0) throw new FileNotFoundException("No media source user");
            if (includeDetached && (cursor.isNull(3) || cursor.getString(0).isEmpty())) {
                throw new VmDecodeUnavailableException("Retained row identity unavailable");
            }
            final Source source = new Source(cursor.getString(0), cursor.getString(1), user, cursor.getLong(3), uri,
                    cursor.getString(4), cursor.getString(5));
            if (source.cacheSession != session) {
                throw new VmDecodeUnavailableException("Thumbnail row crossed a database reopen");
            }
            final ThumbnailSource capture = THUMBNAIL.get();
            if (capture != null) capture.accept(source);
            return source;
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
        final ThumbnailSource attempt = THUMBNAIL.get();
        Source source;
        boolean retained = false;
        try {
            source = row(query, uri, null);
        } catch (VmMediaGoneException | IllegalArgumentException failure) {
            // Only the initial authorized image or video lookup can reuse a detached row.
            if (attempt == null || attempt.source != null || !Objects.equals(attempt.target, uri)
                    || !("Pictures".equals(directory) || "Movies".equals(directory))) throw failure;
            final String volume = MediaStore.getVolumeName(uri);
            if (MediaStore.VOLUME_INTERNAL.equals(volume)
                    || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)) throw failure;
            if (failure instanceof IllegalArgumentException
                    && (MediaStore.VOLUME_EXTERNAL.equals(volume)
                    || !("Volume " + volume + " not found").equals(failure.getMessage()))) throw failure;
            attempt.gone = false;
            // The provider's compat state enables retained rows for this already authorized open.
            final long caller = Binder.clearCallingIdentity();
            try {
                source = row(query, uri, null, true);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
            retained = true;
        }
        final Context context = users.getContextForUser(UserHandle.of(source.user));
        final File storage = context.getSystemService(StorageManager.class).getPrimaryStorageVolume().getDirectory();
        if (storage == null) throw new IOException("No media thumbnail volume");
        final File file = ThumbnailCacheFiles.file(storage, directory, source.user,
                ContentUris.parseId(uri), source.generation, source.cacheSession);
        if (retained && (source.cacheSession != attempt.externalSession
                || !ThumbnailCacheFiles.regular(file))) {
            throw new VmDecodeUnavailableException("Retained row has no current warm thumbnail");
        }
        if (retained) attempt.retained = true;
        return file;
    }

    public static List<File> cacheDirectories(File volume, List<UserHandle> users) {
        final List<File> directories = new ArrayList<>();
        for (UserHandle user : users) {
            directories.addAll(ThumbnailCacheFiles.directories(volume, user.getIdentifier()));
        }
        return directories;
    }

    public static Source forDisplay(android.content.ContentResolver resolver, Uri uri,
            CancellationSignal signal) throws IOException {
        return row(resolver::query, uri, signal);
    }

    static int uriUser(Context context, Uri uri) {
        if (uri.getUserInfo() == null) return context.getUser().getIdentifier();
        try { return Integer.parseInt(uri.getUserInfo()); }
        catch (NumberFormatException e) { throw new SecurityException("Invalid picker source user"); }
    }

    static Source picker(Context context, Uri uri) throws IOException {
        final int user = uriUser(context, uri);
        if (!VmMediaUsers.serves(context, user)) throw new SecurityException("Unserved picker source user");
        final String authority = ProviderAuthority.picker(uri);
        if (PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(authority)) {
            final long id;
            try { id = Long.parseLong(uri.getLastPathSegment()); }
            catch (RuntimeException e) { throw new IOException("Invalid picker media id", e); }
            final Uri item = pickerItem(user, id);
            final Source source = row(context.getContentResolver()::query, item, null);
            if (!VmMediaUsers.serves(context, source.user)) throw new SecurityException("Unserved media row user");
            return source;
        }
        final ProviderInfo provider = VmMediaUsers.contextForUser(context, user)
                .getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || provider.packageName == null) throw new IOException("No selected media provider");
        return new Source(null, provider.packageName, user, -1, null, null, null);
    }

    static void verify(Context context, Uri uri, Source source) throws IOException {
        if (!source.same(picker(context, uri))) {
            throw new VmDecodeUnavailableException("Media row changed during source open");
        }
    }

    static Uri pickerItem(int user, long id) {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id).buildUpon()
                .encodedAuthority(user + "@" + MediaStore.AUTHORITY).build();
    }
}
