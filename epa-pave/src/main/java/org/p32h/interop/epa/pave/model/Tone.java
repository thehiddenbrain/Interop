package org.p32h.interop.epa.pave.model;

/**
 * What an outcome means, in terms a tester can name. The frontend maps each tone to one colour, so
 * no colour appears on screen without one of these meanings behind it.
 */
public enum Tone {
    /** Nothing further is required of the provider. */
    CLEAR,
    /** The provider has something to do: obtain authorization, send documentation, supply information. */
    ACTION,
    /** The payer said no: not covered, or an authorization denied in PAS. */
    STOP,
    /** A decision exists but is not final. */
    PENDING,
    /** The service answered and told the tester nothing. */
    NO_ANSWER,
    /** A finding about the test data: the member or provider is not known to the service. */
    DATA,
    /** A finding about the environment: the service failed, timed out or could not be reached. */
    FAULT,
    /** The tool refused to send the request. */
    BLOCKED,
    /** Informational, with no judgement attached. */
    NEUTRAL
}
