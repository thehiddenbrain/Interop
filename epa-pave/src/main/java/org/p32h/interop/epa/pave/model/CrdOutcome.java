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

    AUTH_REQUIRED("PA required", "Prior authorization is required", Tone.ACTION, false),
    NO_AUTH_REQUIRED("PA not required", "Prior authorization is not required", Tone.CLEAR, false),
    AUTH_SATISFIED("PA already met", "Prior authorization requirements are already met", Tone.CLEAR, false),
    PERFORMER_AUTH("Performer must get PA", "The performing provider must obtain prior authorization", Tone.ACTION, false),
    DOCUMENTATION_REQUIRED("Documentation required", "Documentation is required", Tone.ACTION, false),
    INFORMATION_NEEDED("Information needed", "More information is needed before the payer can answer", Tone.ACTION, false),
    CONDITIONAL("Conditional", "The answer is conditional on information not yet supplied", Tone.ACTION, false),
    NOT_COVERED("Not covered", "Not a covered benefit", Tone.STOP, false),
    COVERAGE_INACTIVE("Coverage inactive", "Coverage is not active on the date of service", Tone.STOP, false),
    MEMBER_NOT_FOUND("Member not found", "The service does not recognise this member", Tone.DATA, false),
    PROVIDER_NOT_FOUND("Provider not found", "The service does not recognise this provider", Tone.DATA, false),
    AUTH_NOT_STATED("PA not stated", "The service did not state whether prior authorization is required", Tone.NO_ANSWER, false),
    UNRECOGNISED_CODE("Unknown code", "The service returned a code this tool does not recognise", Tone.NO_ANSWER, false),
    MIXED("Mixed answers", "Not every answer in this response is the same", Tone.ACTION, false),
    NO_DETERMINATION("No determination", "The service returned no determination", Tone.NO_ANSWER, false),
    REJECTED("Rejected", "The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("Service error", "The service failed", Tone.FAULT, true),
    TIMEOUT("Timed out", "The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("Unreachable", "The service could not be reached", Tone.FAULT, true),
    /** Also a readable answer that is not a CDS Hooks response, such as a FHIR OperationOutcome at HTTP 200: the label says so. */
    MALFORMED_RESPONSE("Unreadable answer", "The service answered with no body, with a body that could not be read, or with something other than a CDS "
            + "Hooks response", Tone.FAULT, true);

    private final String shortLabel;
    private final String label;
    private final Tone tone;
    private final boolean fault;

    CrdOutcome(String shortLabel, String label, Tone tone, boolean fault) {
        this.shortLabel = shortLabel;
        this.label = label;
        this.tone = tone;
        this.fault = fault;
    }

    @Override
    public String shortLabel() {
        return shortLabel;
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
