/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.database.MatrixCursor;
import android.net.Uri;
import org.junit.Test;
public class VmMediaRowsTest {
    private static final String[] COLUMNS = {"_data", "owner_package_name", "_user_id",
            "generation_modified", "mime_type", "_display_name"};
    private MatrixCursor row(Integer user, long generation) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{"/storage/emulated/10/image.jpg", "work.app", user,
                generation, "image/jpeg", "Fixture"});
        return cursor;
    }
    private VmMediaRows.Source source(Integer user, long generation) throws Exception {
        return VmMediaRows.row((u, p, s, a, c) -> row(user, generation),
                Uri.parse("content://10@media/external/file/7"), null);
    }
    @Test public void rowUserAndPackageStayTogether() throws Exception {
        VmMediaRows.Source source = source(10, 3);
        assertEquals(10, source.user);
        assertEquals("work.app", source.principal);
        assertEquals(3, source.generation);
    }
    @Test public void unknownUserIsNotTheProcessUser() {
        assertThrows(java.io.FileNotFoundException.class, () -> source(null, 3));
    }
    @Test public void anotherGenerationIsAnotherSource() throws Exception {
        assertFalse(source(10, 3).same(source(10, 4)));
        assertFalse(source(10, 3).same(source(0, 3)));
    }
}
