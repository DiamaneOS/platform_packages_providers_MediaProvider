/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media;

import android.content.ContentProviderClient;
import android.content.Context;
import android.provider.MediaStore;
import com.android.providers.media.util.FileUtils;
import java.io.File;
import java.io.FileNotFoundException;

/** Cache removal uses the same active-volume and FUSE guards as ordinary media removal. */
public final class ThumbnailDentries {
    private ThumbnailDentries() { }
    public static void deleted(Context context, File file) {
        if (FileUtils.shouldBeVisible(file.getAbsolutePath())) return;
        try (ContentProviderClient client = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            if (client == null || !(client.getLocalContentProvider() instanceof MediaProvider)) return;
            final MediaProvider provider = (MediaProvider) client.getLocalContentProvider();
            if (provider.isFuseThread()) return;
            MediaProvider.getFuseDaemonForFile(file, provider.getVolumeCache())
                    .markPathAsDeletedAndInvalidateFuseDentry(file.getAbsolutePath());
        } catch (FileNotFoundException | RuntimeException ignored) {
            // A detached daemon is revisited through ordinary mount reconciliation.
        }
    }
}
