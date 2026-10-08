package org.p32h.interop.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /v1/interop/resolve}. Lean by design: the outcome, one sentence, the member id as
 * received, as resolved and in every configured vendor's format with the payer for that vendor's request, the line of
 * business (Onyx routes on it), the date
 * evaluated ({@code dateOfServiceEnd} too when a period was asked about), the coverage flag with its period and id, the
 * caller's request id, and a trace id for support (the id of the lookup behind the answer). An AMBIGUOUS answer lists
 * nobody: it says in {@code message} what to resend. Optional blocks are
 * absent when they do not apply (global non-null inclusion); an open-ended coverage period ends {@code 3999-12-31}.
 * {@code sourceMessage} is what the member lookup said when it had no member for the id (NOT_FOUND only). Nothing in
 * the body names MMI. The correlation id travels in the {@code X-Correlation-Id} response header.
 */
public record MemberResolutionResponse(
        Outcome outcome,
        String message,
        MemberId memberId,
        String lineOfBusiness,
        LocalDate dateOfService,
        LocalDate dateOfServiceEnd,
        boolean dateOfServiceDefaulted,
        Coverage coverage,
        String requestId,
        String traceId,
        SourceMessage sourceMessage) {

    /**
     * @param received   as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param resolved   the id it resolved to, exactly as MMI stores it for the member it settled on (ACTIVE / INACTIVE
     *                   only); every vendor format and the coverage id are built from it
     * @param forVendors the resolved id in every configured vendor's format, one entry per vendor sorted by vendor code
     *                   (ACTIVE / INACTIVE only)
     */
    public record MemberId(String received, String resolved, List<VendorMemberId> forVendors) {
    }

    /**
     * Everything Onyx puts on one vendor's request.
     *
     * @param vendor    the vendor code as configured in {@code member-id.vendors}
     * @param memberId  the stored id in that vendor's format: what goes into that vendor's payload
     * @param payerId   the payer id that vendor keys on: its own for the member's company when configured, else the default
     * @param payerName the payer name that goes with it
     */
    public record VendorMemberId(String vendor, String memberId, String payerId, String payerName) {
    }
}
