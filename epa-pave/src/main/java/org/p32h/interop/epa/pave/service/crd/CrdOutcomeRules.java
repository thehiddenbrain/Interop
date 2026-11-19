package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;
import java.util.Set;

/**
 * Decides the outcome of a CRD call from standard codes and transport facts. Card wording is never
 * read here.
 *
 * <p>The rule that matters most: an answer is only ever derived from a code that was actually
 * returned. An element that is absent, null, empty or unrecognised contributes nothing. In
 * particular "prior authorization is not required" needs {@code pa-needed} to be present with the
 * value {@code no-auth}; a response that simply lacks {@code pa-needed} is never read that way.
 */
public final class CrdOutcomeRules {

    private static final Set<String> DOCUMENTATION_WANTED = Set.of("clinical", "admin", "both");

    private CrdOutcomeRules() {
    }

    /**
     * The outcome one coverage information extension establishes. First match wins, in this order.
     *
     * @param coverageInactive true when the client layer recognised a coverage-inactive reason on it
     */
    public static CrdOutcome forExtension(CodedValue covered, CodedValue paNeeded, CodedValue docNeeded,
                                          List<CodedValue> infoNeeded, boolean coverageInactive) {
        if (coverageInactive) {
            return CrdOutcome.COVERAGE_INACTIVE;
        }
        if (covered.is("not-covered")) {
            return CrdOutcome.NOT_COVERED;
        }
        // An authorization code the tool cannot read is stated as such before anything that would
        // imply an authorization answer.
        if (paNeeded.code() != null && !paNeeded.recognised()) {
            return CrdOutcome.UNRECOGNISED_CODE;
        }
        if (infoNeeded.stream().anyMatch(value -> value.code() != null)) {
            return CrdOutcome.INFORMATION_NEEDED;
        }
        if (paNeeded.is("satisfied")) {
            return CrdOutcome.AUTH_SATISFIED;
        }
        // Documentation wanted for a claim or to retain does not change a "no authorization" answer.
        if (paNeeded.is("no-auth")) {
            return CrdOutcome.NO_AUTH_REQUIRED;
        }
        if (docNeeded.code() != null && DOCUMENTATION_WANTED.contains(docNeeded.code())) {
            return CrdOutcome.DOCUMENTATION_REQUIRED;
        }
        if (paNeeded.is("auth-needed")) {
            return CrdOutcome.AUTH_REQUIRED;
        }
        if (paNeeded.is("performpa")) {
            return CrdOutcome.PERFORMER_AUTH;
        }
        if (paNeeded.is("conditional") || covered.is("conditional") || docNeeded.is("conditional")) {
            return CrdOutcome.CONDITIONAL;
        }
        if (covered.code() != null && !covered.recognised()) {
            return CrdOutcome.UNRECOGNISED_CODE;
        }
        if (covered.is("covered")) {
            return CrdOutcome.AUTH_NOT_STATED;
        }
        return CrdOutcome.NO_DETERMINATION;
    }

    /**
     * The outcome of the whole call.
     *
     * @param extensionOutcomes the outcome of each coverage information extension found, in order
     * @param unansweredOrders  how many of the orders sent no answer is for
     */
    public static CrdOutcome overall(VendorExchange exchange, List<CrdOutcome> extensionOutcomes, int unansweredOrders) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.TIMEOUT) {
            return CrdOutcome.TIMEOUT;
        }
        if (failure == OnyxFailureType.UNAVAILABLE) {
            return CrdOutcome.UNREACHABLE;
        }
        if (failure == OnyxFailureType.MALFORMED_RESPONSE) {
            return CrdOutcome.MALFORMED_RESPONSE;
        }
        // Not found is a finding about the test data, so it is told apart from a fault even when it
        // arrives with an error status.
        if (exchange.has(VendorSignal.MEMBER_NOT_FOUND)) {
            return CrdOutcome.MEMBER_NOT_FOUND;
        }
        if (exchange.has(VendorSignal.PROVIDER_NOT_FOUND)) {
            return CrdOutcome.PROVIDER_NOT_FOUND;
        }
        if (failure == OnyxFailureType.SERVER_ERROR) {
            return CrdOutcome.SERVICE_ERROR;
        }
        if (failure == OnyxFailureType.BAD_REQUEST) {
            return CrdOutcome.REJECTED;
        }
        if (extensionOutcomes.isEmpty()) {
            // The service answered and said nothing. This is never treated as "not required".
            return CrdOutcome.NO_DETERMINATION;
        }
        CrdOutcome first = extensionOutcomes.get(0);
        // When the codes in one order get different answers, none of them is chosen to stand for
        // the order. A code that got no answer counts as exactly that, so an answer for one code
        // can never be read as the answer for another that the service said nothing about.
        boolean alike = extensionOutcomes.stream().allMatch(first::equals)
                && (unansweredOrders == 0 || first == CrdOutcome.NO_DETERMINATION);
        return alike ? first : CrdOutcome.MIXED;
    }
}
