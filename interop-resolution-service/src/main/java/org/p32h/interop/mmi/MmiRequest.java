package org.p32h.interop.mmi;

import org.p32h.interop.support.Masking;

/** The MMI search body. The same id goes in memberId and legacyMemberId (the MMI spec's hit-rate advice). */
public record MmiRequest(String memberId, String legacyMemberId, String voidCoverageRecord, String clientId,
        String clientType, String requestId) {

    @Override
    public String toString() {
        return "MmiRequest[memberId=" + Masking.memberId(memberId) + ", requestId=" + requestId + "]";
    }
}
