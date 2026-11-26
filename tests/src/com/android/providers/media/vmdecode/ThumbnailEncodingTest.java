/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import org.junit.Test;

public class ThumbnailEncodingTest {
    @Test public void falseEncodePreservesValidCacheAndRemovesTemporary() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thumbnail-failure").toFile();
        File target = new File(directory, "valid.jpg");
        Files.write(target.toPath(), new byte[]{1, 2, 3});
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try {
            assertThrows(IOException.class, () -> ThumbnailEncoding.publishEncoded(pipe[1],
                    () -> output -> { output.write(4); return false; }, () -> target, File::delete));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.toPath()));
            assertEquals(1, directory.list().length);
        } finally {
            pipe[0].close();
            pipe[1].close();
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    @Test public void asyncFailureIsReportedBeforeOutputCloses() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            assertThrows(IOException.class, () -> ThumbnailEncoding.publish(pipe[1],
                    () -> { throw new VmDecodeUnavailableException("test outage"); },
                    () -> { throw new AssertionError("cache queried after source failure"); }, File::delete));
            IOException failure = assertThrows(IOException.class, input::read);
            assertTrue(failure.getMessage().contains(VmDecodeUnavailableException.PREFIX));
        } finally { pipe[1].close(); }
    }

    @Test public void replacementAtRenameCannotReplaceTheOpenedOutputInode() throws Exception {
        final File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thumbnail-inode").toFile();
        final File target = new File(root, "7.11");
        final File replacement = new File(root, "replacement");
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        Files.write(replacement.toPath(), new byte[] {9});
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            ThumbnailEncoding.publishEncoded(pipe[1], () -> output -> {
                output.write(new byte[] {1, 2, 3}); return true;
            }, () -> target, File::delete, (temporary, destination) -> {
                Files.move(temporary.toPath(), destination.toPath());
                Files.move(replacement.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            });
            assertArrayEquals(new byte[] {1, 2, 3}, input.readAllBytes());
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(target.toPath()));
        } finally {
            pipe[1].close(); target.delete(); replacement.delete(); root.delete();
        }
    }

    @Test public void encoderTemporaryCarriesTheProcessTag() throws Exception {
        final File root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "thumbnail-temporary").toFile();
        final File target = new File(root, "7.11");
        final java.util.concurrent.atomic.AtomicReference<String> name =
                new java.util.concurrent.atomic.AtomicReference<>();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            ThumbnailEncoding.publishEncoded(pipe[1], () -> output -> {
                output.write(new byte[] {1}); return true;
            }, () -> target, File::delete, (temporary, destination) -> {
                name.set(temporary.getName());
                Files.move(temporary.toPath(), destination.toPath());
            });
            assertArrayEquals(new byte[] {1}, input.readAllBytes());
            assertTrue(name.get(), name.get().startsWith("thumb" + ThumbnailCacheFiles.PROCESS + "-"));
            assertTrue(name.get(), name.get().endsWith(".tmp"));
        } finally {
            pipe[1].close(); target.delete(); root.delete();
        }
    }

    @Test public void firstHopOutageRemainsRetryable() throws Exception {
        try (AssetFileDescriptor asset = VmThumbnailErrors.open(() -> {
            throw new FileNotFoundException(VmDecodeUnavailableException.PREFIX + "test outage");
        })) {
            assertTrue(asset.getExtras().getBoolean(VmThumbnailErrors.LATER));
        }
    }
}
