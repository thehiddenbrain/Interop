package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What became of one run a replay was asked to run again. Written in lower case with hyphens.
 *
 * <p>Only the first three say anything about the service. The rest say that the run was not sent
 * again, and each says so for a different reason, because what the tester does next differs: fix
 * nothing, unlock or switch environment, fix the configuration, or report a defect in this tool.
 */
public enum ReplayStatus {

    /** Sent again, and everything the ledger compares came back the same. */
    SAME("same"),
    /** Sent again, and something came back different. The differences are listed. */
    CHANGED("changed"),
    /**
     * Sent again, and what the ledger kept is the same, but part of an answer was not kept as
     * text on one line or both. The two cannot be shown to be the same, so they are not called so.
     */
    NOT_COMPARABLE("not-comparable"),
    /** The same request cannot be sent again from what the ledger holds. Nothing was sent. */
    NOT_REPLAYABLE("not-replayable"),
    /** The production guardrail refused. Not a failure of the run. */
    BLOCKED("blocked"),
    /** The tool has no url or value for this operation, or the configuration changed during the replay. The record is not at fault. */
    NOT_CONFIGURED("not-configured"),
    /** The runner found the inputs on the line not valid. */
    INVALID("invalid"),
    /** This tool failed while running it again, or the runner did not return what it should. Nothing can be said about the service. */
    ERROR("error");

    private final String wire;

    ReplayStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
