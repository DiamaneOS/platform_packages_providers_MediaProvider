/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;

import android.database.MatrixCursor;
import android.net.Uri;
import java.io.IOException;
import org.junit.Test;

/** Exercises profile separators with the real Android URI builder. */
public class VmMediaRowsUriTest {
    @Test public void pickerItemsKeepTheSelectedUserBeforeRowValidation() throws Exception {
        for (int user : new int[] {0, 10, 11}) {
            final Uri item = VmMediaRows.pickerItem(user, 7);
            assertEquals("media", item.getHost());
            assertEquals(Integer.toString(user), item.getUserInfo());
            assertEquals(user, VmMediaRows.row(query(user), item, null).user);
        }
    }

    @Test public void qualifiedCollectionKeepsTheRealSeparator() throws Exception {
        final Uri item = Uri.parse("content://10@media/external_primary/images/media/7");
        assertEquals(10, VmMediaRows.row(query(10), item, null).user);
    }

    @Test public void unqualifiedCollectionStaysUnqualified() throws Exception {
        final Uri item = Uri.parse("content://media/external_primary/images/media/7");
        assertEquals(0, VmMediaRows.row(query(-1), item, null).user);
    }

    @Test public void escapedSeparatorDoesNotNameTheMediaProvider() {
        assertThrows(IOException.class, () -> VmMediaRows.row((uri, projection, arguments, signal) -> {
            throw new AssertionError("An escaped provider name must not be queried");
        }, Uri.parse("content://10%40media/external/file/7"), null));
    }

    private static VmMediaRows.Query query(int user) {
        return (uri, projection, arguments, signal) -> {
            assertEquals(user < 0 ? "media" : user + "@media", uri.getEncodedAuthority());
            assertEquals("media", uri.getHost());
            assertEquals(user < 0 ? null : Integer.toString(user), uri.getUserInfo());
            final MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(new Object[] {"/synthetic/proof.jpg", "proof.package", Math.max(0, user),
                    1L, "image/jpeg", "proof.jpg"});
            return cursor;
        };
    }
}
