/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import android.net.Uri;
import com.android.providers.media.photopicker.PickerSyncController;

/** Provider dispatch uses the complete authority after its optional numeric user prefix. */
final class ProviderAuthority {
    private ProviderAuthority() { }

    static String of(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw new SecurityException("Invalid provider URI");
        }
        final String encoded = uri.getEncodedAuthority();
        final String authority = uri.getAuthority();
        if (authority == null || authority.isEmpty() || !authority.equals(encoded)) {
            throw new SecurityException("Noncanonical provider authority");
        }
        final int at = authority.lastIndexOf('@');
        final String name;
        if (at >= 0) {
            final String user = authority.substring(0, at);
            if (!user.matches("[0-9]+")) throw new SecurityException("Invalid provider user");
            try { Integer.parseInt(user); }
            catch (NumberFormatException invalid) { throw new SecurityException("Invalid provider user"); }
            name = authority.substring(at + 1);
        } else name = authority;
        if (name.isEmpty() || name.indexOf('@') >= 0 || name.indexOf('%') >= 0
                || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new SecurityException("Invalid provider authority");
        }
        return name;
    }

    static String picker(Uri uri) {
        final String authority = of(uri);
        final java.util.List<String> path = uri.getPathSegments();
        final String encodedPath = uri.getEncodedPath();
        if (path.size() != 2 || !"media".equals(path.get(0)) || path.get(1).isEmpty()
                || encodedPath == null || !encodedPath.startsWith("/media/")
                || encodedPath.indexOf('/', 7) >= 0
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new SecurityException("Invalid picker item URI");
        }
        return authority;
    }

    static boolean localPicker(Uri uri) {
        return PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY.equals(picker(uri));
    }
}
