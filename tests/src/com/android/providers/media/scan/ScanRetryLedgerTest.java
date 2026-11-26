/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.scan;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class ScanRetryLedgerTest {
    private static final class Disk implements ScanRetryLedger.Store {
        ScanRetryLedger.State state = new ScanRetryLedger.State(Map.of());
        int writes;
        public ScanRetryLedger.State read() { return state; }
        public void write(ScanRetryLedger.State next) { state = next; writes++; }
    }
    @Test public void secondRefusalAndProcessRestartRetainTheRowlessFile() {
        final Disk disk = new Disk();
        ScanRetryLedger ledger = new ScanRetryLedger(disk);
        ledger.defer("rowless");
        for (int refusal = 0; refusal < 12; refusal++) {
            final String token = ledger.claim(1).get("rowless");
            ledger.finish("rowless", token, false);
        }
        ledger = new ScanRetryLedger(disk);
        assertTrue(ledger.pending());
        ledger.finish("rowless", ledger.claim(1).get("rowless"), true);
        assertFalse(ledger.pending());
    }
    @Test public void fullQueueRetainsOnlyBoundedExactWorkAfterRestart() {
        final Disk disk = new Disk();
        ScanRetryLedger ledger = new ScanRetryLedger(disk);
        for (int file = 0; file < 256; file++) assertTrue(ledger.defer("rowless-" + file));
        assertFalse(ledger.defer("rowless-overflow"));
        assertFalse(ledger.defer(null));
        ledger = new ScanRetryLedger(disk);
        final Map<String, String> attempts = ledger.claim(256);
        assertEquals(256, attempts.size());
        for (Map.Entry<String, String> attempt : attempts.entrySet()) {
            ledger.finish(attempt.getKey(), attempt.getValue(), true);
        }
        assertFalse(new ScanRetryLedger(disk).pending());
    }
    @Test public void anOldSuccessfulAttemptCannotClearANewerRefusal() {
        final Disk disk = new Disk();
        final ScanRetryLedger ledger = new ScanRetryLedger(disk);
        ledger.defer("file");
        final String old = ledger.claim(1).get("file");
        ledger.defer("file");
        ledger.finish("file", old, true);
        assertTrue(ledger.pending());
        ledger.finish("file", ledger.claim(1).get("file"), true);
        assertFalse(ledger.pending());
    }

    @Test public void recoverUnreadStoreBeforePersistingNewObligations() {
        final Disk disk = new Disk();
        new ScanRetryLedger(disk).defer("committed");
        final String revision = disk.state.files.get("committed");
        final boolean[] available = { false };
        final ScanRetryLedger ledger = new ScanRetryLedger(new ScanRetryLedger.Store() {
            public ScanRetryLedger.State read() {
                if (!available[0]) throw new IllegalStateException();
                return disk.read();
            }
            public void write(ScanRetryLedger.State state) {
                if (!available[0]) throw new IllegalStateException();
                disk.write(state);
            }
        });
        assertTrue(ledger.pending());
        assertTrue(ledger.defer("while-unread"));
        available[0] = true;
        assertTrue(ledger.defer("after-recovery"));
        final Map<String, String> attempts = new ScanRetryLedger(disk).claim(256);
        assertEquals(revision, attempts.get("committed"));
        assertTrue(attempts.containsKey("while-unread"));
        assertTrue(attempts.containsKey("after-recovery"));
    }
    @Test public void oneUnavailableFileDoesNotStrandLaterExactEntries() {
        final Disk disk = new Disk();
        final ScanRetryLedger ledger = new ScanRetryLedger(disk);
        for (int file = 0; file < 20; file++) ledger.defer("file-" + file);
        for (Map.Entry<String,String> attempt : ledger.claim(8).entrySet()) {
            ledger.finish(attempt.getKey(), attempt.getValue(), false);
        }
        assertTrue(ledger.claim(8).containsKey("file-8"));
        assertEquals("file-8", new ScanRetryLedger(disk).claim(1).keySet().iterator().next());
    }
    @Test public void duplicateAndOverflowWritesAreCoalesced() {
        final Disk disk = new Disk();
        final ScanRetryLedger ledger = new ScanRetryLedger(disk);
        ledger.defer("file");
        for (int duplicate = 0; duplicate < 1000; duplicate++) ledger.defer("file");
        assertEquals(1, disk.writes);
        for (int file = 0; file < 1000; file++) ledger.defer("file-" + file);
        assertEquals(256, disk.writes);
    }
}
