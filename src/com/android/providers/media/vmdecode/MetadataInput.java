/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;

/** Records source failures even when a metadata parser catches them internally. */
final class MetadataInput extends FilterInputStream {
    private IOException mFailure;
    MetadataInput(InputStream input) { super(input); }
    void check() throws IOException { if (mFailure != null) throw mFailure; }
    public int read() throws IOException {
        try { return in.read(); } catch (IOException e) { mFailure = e; throw e; }
    }
    public int read(byte[] bytes, int offset, int length) throws IOException {
        try { return in.read(bytes, offset, length); } catch (IOException e) { mFailure = e; throw e; }
    }
    public long skip(long length) throws IOException {
        try { return in.skip(length); } catch (IOException e) { mFailure = e; throw e; }
    }
    public int available() throws IOException {
        try { return in.available(); } catch (IOException e) { mFailure = e; throw e; }
    }
}
