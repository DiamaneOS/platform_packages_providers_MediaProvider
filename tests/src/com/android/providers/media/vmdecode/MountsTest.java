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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** FUSE detection from mountinfo text. Plain Java, no device state needed. */
@RunWith(JUnit4.class)
public class MountsTest {
    private static final String MOUNTINFO = String.join("\n",
            "1 0 253:0 / / ro,relatime shared:1 - erofs /dev/root ro",
            "30 1 254:40 / /data rw,nosuid shared:20 - f2fs /dev/block/dm-40 rw",
            "41 30 254:40 /media /data/media rw shared:20 - f2fs /dev/block/dm-40 rw",
            "52 1 0:44 / /storage/emulated rw,nosuid shared:30 - fuse /dev/fuse rw,user_id=0",
            "53 1 0:45 / /mnt/appfuse/10123_1 rw shared:31 - fuse /dev/fuse rw",
            "54 52 254:40 /media/0/Android/data /storage/emulated/0/Android/data rw - f2fs /dev/block/dm-40 rw",
            "55 1 0:46 / /mnt/my\\040disk rw - fuse.sshfs remote rw",
            "");

    @Test
    public void fuseMounts() {
        assertTrue(Mounts.isFuse("/storage/emulated/0/DCIM/a.jpg", MOUNTINFO));
        assertTrue(Mounts.isFuse("/mnt/appfuse/10123_1/5", MOUNTINFO));
        assertTrue(Mounts.isFuse("/mnt/my disk/x", MOUNTINFO));
    }

    @Test
    public void notFuse() {
        assertFalse(Mounts.isFuse("/data/media/0/DCIM/a.jpg", MOUNTINFO));
        // A bind mount below a FUSE mount: the longest mount point decides.
        assertFalse(Mounts.isFuse("/storage/emulated/0/Android/data/x/y.jpg", MOUNTINFO));
        assertFalse(Mounts.isFuse("/storage/emulatedX/a", MOUNTINFO));
        assertFalse(Mounts.isFuse("pipe:[1234]", MOUNTINFO));
        assertFalse(Mounts.isFuse("/memfd:vmdecode (deleted)", MOUNTINFO));
        assertFalse(Mounts.isFuse(null, MOUNTINFO));
    }
}
