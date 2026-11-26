package org.p32h.interop.epa.pave.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Mints the identifiers and timestamps that tie a run on screen to its line in the ledger. */
public final class RunStamps {

    private RunStamps() {
    }

    public static String newRunId() {
        return "run-" + UUID.randomUUID();
    }

    public static String newChainId() {
        return "chain-" + UUID.randomUUID();
    }

    /** True when the text has the shape of an id {@link #newChainId()} mints. */
    public static boolean isChainId(String text) {
        if (text == null || !text.startsWith("chain-")) {
            return false;
        }
        try {
            return UUID.fromString(text.substring(6)).toString().equals(text.substring(6));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String newSuiteRunId() {
        return "suite-" + UUID.randomUUID();
    }

    /** The current time as an ISO-8601 timestamp with offset, to the millisecond. */
    public static String timestamp(Clock clock) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS));
    }
}
