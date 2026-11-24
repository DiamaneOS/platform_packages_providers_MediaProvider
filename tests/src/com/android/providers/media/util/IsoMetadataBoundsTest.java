/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;

public class IsoMetadataBoundsTest {
    private static byte[] box(int type, byte[] payload) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length + 8);
        data.writeInt(type);
        data.write(payload);
        return out.toByteArray();
    }

    private static IsoInterface parse(byte[] payload) throws Exception {
        final File file = File.createTempFile("iso-limits", ".mp4");
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(box(IsoInterface.BOX_FTYP, new byte[8]));
            bytes.write(payload);
            Files.write(file.toPath(), bytes.toByteArray());
            return IsoInterface.fromFile(file);
        } finally {
            file.delete();
        }
    }

    @Test
    public void normalMetadataPreservesBytesAndRanges() throws Exception {
        final byte[] payload = {1, 2, 3};
        final IsoInterface iso = parse(box(IsoInterface.BOX_XMP, payload));
        org.junit.Assert.assertArrayEquals(payload, iso.getBoxBytes(IsoInterface.BOX_XMP));
        org.junit.Assert.assertArrayEquals(new long[] {24, 27},
                iso.getBoxRanges(IsoInterface.BOX_XMP));
    }

    @Test
    public void nestingStopsBeforeStackExhaustion() throws Exception {
        byte[] nested = new byte[0];
        for (int i = 0; i < 128; ++i) nested = box(0x6d6f6f76, nested);
        final byte[] input = nested;
        assertThrows(java.io.IOException.class, () -> parse(input));
    }

    @Test
    public void countIncludesBoxesWhosePayloadIsNotRead() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 20000; ++i) bytes.write(box(0x66726565, new byte[0]));
        assertThrows(java.io.IOException.class, () -> parse(bytes.toByteArray()));
    }

    @Test
    public void retainedPayloadHasOneAggregateBudget() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 12; ++i) {
            bytes.write(box(IsoInterface.BOX_XMP, new byte[1024 * 1024]));
        }
        assertThrows(java.io.IOException.class, () -> parse(bytes.toByteArray()));
    }

    @Test
    public void uuidMustFitInsideItsBox() throws Exception {
        assertThrows(java.io.IOException.class,
                () -> parse(box(IsoInterface.BOX_UUID, new byte[2])));
    }
}
