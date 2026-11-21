package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What became of one run a replay was asked to run again. Written in lower case with hyphens.
 *
 * <p>Only the first three say anything about the service. The next five say that the run was not
 * sent again, each for a different reason, because what the tester does next differs: nothing,
 * unlock or switch environment, fix the configuration, fix the sign-in, or look at the inputs on
 * the line. The last
 * says that this tool lost track of the run: a request may have been sent, and the defect to
 * report is in this tool.
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
    /**
     * Not sent again: this tool could not sign in, because a sign-in setting is not given or the token endpoint gave no access
     * token. Nothing reached the service, so nothing is compared and the service is not said to have changed.
     */
    NOT_SIGNED_IN("not-signed-in"),
    /** The runner found the inputs on the line not valid. */
    INVALID("invalid"),
    /**
     * This tool failed while running it again, or the runner did not return what it should. A
     * request may have been sent, and nothing can be said about what the service answered.
     */
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
