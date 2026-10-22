package org.p32h.interop.mmi;

/**
 * The MMI search body. The same id goes in memberId and legacyMemberId (the MMI spec's hit-rate advice). No date of
 * service: without dosStartDate MMI returns every coverage record of the member, which the service evaluates itself.
 */
public record MmiRequest(String memberId, String legacyMemberId, String voidCoverageRecord, String clientId, String clientType,
        String requestId) {
}
