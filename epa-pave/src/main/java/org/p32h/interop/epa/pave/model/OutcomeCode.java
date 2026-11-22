package org.p32h.interop.epa.pave.model;

/**
 * What every protocol's outcome enum exposes, so the ledger, the expectation check and the screen
 * can treat a CRD, DTR or PAS outcome uniformly without sharing one enum between protocols.
 *
 * <p>The protocols deliberately do not share an enum. CRD has no denial; PAS has a real one.
 */
public interface OutcomeCode {

    /** The stable code written to the ledger and compared with a declared expectation. */
    String name();

    /** The outcome in plain language, for the verdict strip and the history list. */
    String label();

    /**
     * The outcome in two or three words, at most 24 characters, for a chip, a step of the journey
     * and a table cell, where the label does not fit. The label stays the one sentence a result
     * gives; this is the same answer said shorter, so the two never disagree.
     */
    String shortLabel();

    Tone tone();

    /** True when no usable answer came back: a transport failure, an HTTP error or an unreadable body. */
    boolean fault();
}
