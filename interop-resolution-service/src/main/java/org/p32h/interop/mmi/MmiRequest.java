package org.p32h.interop.mmi;

/**
 * The MMI search body. The same id goes in memberId and legacyMemberId (the MMI spec's hit-rate advice); dosStartDate is
 * the date of service, MM/dd/yyyy like every MMI date.
 */
public record MmiRequest(String memberId, String legacyMemberId, String dosStartDate, String voidCoverageRecord, String clientId,
        String clientType, String requestId) {
}
