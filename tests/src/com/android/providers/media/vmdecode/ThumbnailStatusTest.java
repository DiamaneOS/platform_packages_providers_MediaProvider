/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import static org.junit.Assert.*;
import android.content.res.AssetFileDescriptor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.junit.Test;

public class ThumbnailStatusTest {
    @Test public void textInAGenericErrorIsNeverConfirmedAbsence() {
        assertFalse(VmMediaGoneException.isGone(new IOException("temporary: " + VmMediaGoneException.PREFIX)));
        assertTrue(VmMediaGoneException.isGone(new IOException(new VmMediaGoneException("typed"))));
    }
    @Test public void typedCaptureSurvivesThePinnedExceptionFlattening() throws Exception {
        try (AssetFileDescriptor status = VmMediaRows.thumbnailAsset(() -> {
            try {
                VmMediaRows.row((uri,projection,args,signal) -> new MatrixCursor(projection),
                        Uri.parse("content://media/external/images/media/7"), null);
            } catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
            throw new AssertionError();
        })) {
            assertEquals(VmThumbnailErrors.GONE, status.getExtras().getInt(VmThumbnailErrors.STATUS));
            assertTrue("An old consumer needs its conservative retry status",
                    status.getExtras().getBoolean(VmThumbnailErrors.LATER));
        }
    }
    @Test public void nestedMissingOpenCannotClassifyItsParent() throws Exception {
        try {
            VmMediaRows.thumbnailAsset(() -> {
                try (AssetFileDescriptor nested = VmMediaRows.thumbnailAsset(() -> {
                    try {
                        VmMediaRows.row((uri,projection,args,signal) -> new MatrixCursor(projection),
                                Uri.parse("content://media/external/images/media/8"), null);
                    } catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
                    throw new AssertionError();
                })) { }
                catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
                throw new FileNotFoundException("The parent has no confirmed absent row");
            });
            fail("A nested open classified its parent as gone");
        } catch (FileNotFoundException expected) { }
    }

    @Test public void genericPipeTextStaysRetryableAndNoTypedCaptureEscapesItsOpen() throws Exception {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            VmThumbnailErrors.failPipe(pipe[1], new IOException("temporary " + VmMediaGoneException.PREFIX));
            try { input.read(); fail("The reliable pipe lost its failure"); }
            catch (IOException failure) { assertFalse(VmThumbnailErrors.classify(failure) instanceof VmMediaGoneException); }
        } finally { pipe[1].close(); }
        try {
            VmMediaRows.thumbnailAsset(() -> { throw new FileNotFoundException("unrelated later open"); });
            fail("An unrelated open inherited GONE");
        } catch (FileNotFoundException expected) { }
    }
}
