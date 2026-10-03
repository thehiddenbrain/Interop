package com.thehiddenbrain.interop.memberid.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.thehiddenbrain.interop.memberid.support.Masking;
import java.util.List;

/**
 * Only the MMI fields the service uses are declared. Names, SSN, gender, PCP, group name, MBI and the
 * agency numbers are dropped during deserialization and can never be logged or returned by accident.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiMember(String memberId, String subscriberMemberId, String memberDob, String legacyMemberId,
        String company, String lineOfBusiness, String restrictedData, String migrationEffectiveDate,
        List<MmiCoverage> coverage) {

    @Override
    public String toString() {
        return "MmiMember[memberId=" + Masking.memberId(memberId) + ", company=" + company + ", lob=" + lineOfBusiness
                + ", spans=" + (coverage == null ? 0 : coverage.size()) + "]";
    }
}
