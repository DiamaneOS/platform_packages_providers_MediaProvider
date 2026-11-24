/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

/** Shell-only fixture selection with a Bundle that preserves the full authority. */
public final class PickerCloudSelection {
    public static void main(String[] args) throws Exception {
        final int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) throw new SecurityException("Shell or root required");
        if (args.length != 1) throw new IllegalArgumentException("One encoded authority required");
        final String authority = args[0].equals("-") ? null : new String(
                android.util.Base64.decode(args[0], android.util.Base64.URL_SAFE),
                java.nio.charset.StandardCharsets.UTF_8);
        final android.app.IActivityManager manager = android.app.ActivityManager.getService();
        final android.os.IBinder token = new android.os.Binder();
        final android.app.ContentProviderHolder holder = manager.getContentProviderExternal(
                "media", 0, token, "picker-test");
        if (holder == null || holder.provider == null) throw new IllegalStateException("No media provider");
        try {
            final android.os.Bundle extras = new android.os.Bundle();
            extras.putString("cloud_provider", authority);
            final android.os.Bundle answer = holder.provider.call(
                    new android.content.AttributionSource(uid,
                            uid == 0 ? "root" : "com.android.shell", null),
                    "media", "set_cloud_provider", null, extras);
            if (answer == null || !answer.getBoolean("set_cloud_provider_result")) {
                throw new AssertionError("Cloud provider selection refused");
            }
            System.out.println("true");
        } finally {
            manager.removeContentProviderExternalAsUser("media", token, 0);
        }
    }
}
