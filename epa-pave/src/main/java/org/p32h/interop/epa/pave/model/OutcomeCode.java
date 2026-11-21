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

    Tone tone();

    /** True when no usable answer came back: a transport failure, an HTTP error or an unreadable body. */
    boolean fault();
}
