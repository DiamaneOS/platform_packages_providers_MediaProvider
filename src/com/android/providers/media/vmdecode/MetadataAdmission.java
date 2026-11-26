/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;

/** Ordinary scans and idle parsing charge the same source budget until parsing ends. */
public final class MetadataAdmission implements AutoCloseable {
    static final long BYTES = 40L * 1024 * 1024;
    private final SourceBudget.Ticket ticket;

    public static final class Pressure extends RuntimeException {
        Pressure() { super("Metadata parser admission full"); }
    }

    MetadataAdmission(SourceBudget budget, String principal) {
        ticket = budget.reserve(principal, BYTES);
        if (ticket == null) throw new Pressure();
    }

    @Override public void close() { ticket.close(); }
}
