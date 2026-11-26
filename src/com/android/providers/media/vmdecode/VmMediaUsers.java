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
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;

/** A provider context may also serve only the clone whose media the system shares with it. */
final class VmMediaUsers {
    static boolean serves(Context context, int user) {
        final int provider = context.getUser().getIdentifier();
        if (user == provider) return true;
        if (user < 0) return false;
        final long token = Binder.clearCallingIdentity();
        try {
            final UserHandle named = UserHandle.of(user);
            final UserManager users = context.getSystemService(UserManager.class);
            final UserHandle parent = users.getProfileParent(named);
            return parent != null && parent.getIdentifier() == provider
                    && contextForUser(context, user).getSystemService(UserManager.class).isCloneProfile()
                    && users.getUserProperties(named).isMediaSharedWithParent();
        } catch (RuntimeException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static Context contextForUser(Context context, int user) {
        try {
            return context.createPackageContextAsUser("system", 0, UserHandle.of(user));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("No system context for media user", e);
        }
    }
}
