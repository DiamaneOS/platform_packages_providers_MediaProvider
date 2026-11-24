/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import java.io.FileNotFoundException;

/** A completed authenticated row lookup established that the item has disappeared. */
public final class VmMediaGoneException extends FileNotFoundException {
    public static final String PREFIX = "decode media gone: ";
    public VmMediaGoneException(String message) { super(PREFIX + message); }

    static boolean isGone(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof VmMediaGoneException) return true;
        }
        return false;
    }
}
