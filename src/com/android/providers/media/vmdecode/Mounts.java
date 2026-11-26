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

/**
 * Whether a path lies on a FUSE file system, from the text of /proc/self/mountinfo. A read from FUSE goes to a
 * daemon, which may be an app (a StorageManager proxy file descriptor needs no permission) and can stall it for ever.
 * Finding the mount needs no access to the file itself. Plain Java, tested off the device too.
 */
final class Mounts {
    private Mounts() {
    }

    /**
     * True when the longest mount point containing {@code path} is a FUSE mount ("fuse", "fuse.*", "fuseblk"). A path
     * that is not absolute (a pipe, a socket, an anonymous inode) is not on FUSE.
     */
    static boolean isFuse(String path, String mountinfo) {
        if (path == null || !path.startsWith("/")) {
            return false;
        }
        String bestPoint = null;
        String bestType = null;
        for (String line : mountinfo.split("\n")) {
            final String[] fields = line.split(" ");
            if (fields.length < 7) {
                continue;
            }
            int dash = -1;
            for (int i = 6; i < fields.length; i++) {
                if (fields[i].equals("-")) {
                    dash = i;
                    break;
                }
            }
            if (dash < 0 || dash + 1 >= fields.length) {
                continue;
            }
            final String point = unescape(fields[4]);
            if (!contains(point, path)) {
                continue;
            }
            // Later lines are later mounts: on the same point the last one is on top.
            if (bestPoint == null || point.length() >= bestPoint.length()) {
                bestPoint = point;
                bestType = fields[dash + 1];
            }
        }
        return bestType != null && bestType.startsWith("fuse");
    }

    private static boolean contains(String point, String path) {
        if (point.equals("/")) {
            return true;
        }
        return path.equals(point) || path.startsWith(point + "/");
    }

    /** mountinfo writes space, tab, newline and backslash as octal escapes. */
    private static String unescape(String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length()
                    && isOctal(s.charAt(i + 1)) && isOctal(s.charAt(i + 2)) && isOctal(s.charAt(i + 3))) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }
}
