package org.p32h.interop.epa.pave.model;

/**
 * What one CRD call established.
 *
 * <p>There is no member for a clinical refusal, and there must never be one. CRD answers whether
 * authorization is required, what documentation is wanted and where the request routes. It does
 * not decide medical necessity. A test asserts that this enum carries no such member.
 *
 * <p>{@link #NO_AUTH_REQUIRED} and {@link #NO_DETERMINATION} are different answers and are never
 * merged: the first is the payer saying authorization is not needed, the second is the payer
 * saying nothing. Keeping those apart is the reason this tool exists.
 */
public enum CrdOutcome implements OutcomeCode {

    AUTH_REQUIRED("Prior authorization is required", Tone.ACTION, false),
    NO_AUTH_REQUIRED("Prior authorization is not required", Tone.CLEAR, false),
    AUTH_SATISFIED("Prior authorization requirements are already met", Tone.CLEAR, false),
    PERFORMER_AUTH("The performing provider must obtain prior authorization", Tone.ACTION, false),
    DOCUMENTATION_REQUIRED("Documentation is required", Tone.ACTION, false),
    INFORMATION_NEEDED("More information is needed before the payer can answer", Tone.ACTION, false),
    CONDITIONAL("The answer is conditional on information not yet supplied", Tone.ACTION, false),
    NOT_COVERED("Not a covered benefit", Tone.STOP, false),
    COVERAGE_INACTIVE("Coverage is not active on the date of service", Tone.STOP, false),
    MEMBER_NOT_FOUND("The service does not recognise this member", Tone.DATA, false),
    PROVIDER_NOT_FOUND("The service does not recognise this provider", Tone.DATA, false),
    AUTH_NOT_STATED("The service did not state whether prior authorization is required", Tone.NO_ANSWER, false),
    UNRECOGNISED_CODE("The service returned a code this tool does not recognise", Tone.NO_ANSWER, false),
    MIXED("Not every answer in this response is the same", Tone.ACTION, false),
    NO_DETERMINATION("The service returned no determination", Tone.NO_ANSWER, false),
    REJECTED("The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("The service failed", Tone.FAULT, true),
    TIMEOUT("The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("The service could not be reached", Tone.FAULT, true),
    MALFORMED_RESPONSE("The service answered with no body, or with a body that could not be read", Tone.FAULT, true);

    private final String label;
    private final Tone tone;
    private final boolean fault;

    CrdOutcome(String label, Tone tone, boolean fault) {
        this.label = label;
        this.tone = tone;
        this.fault = fault;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Tone tone() {
        return tone;
    }

    @Override
    public boolean fault() {
        return fault;
    }
}
