package org.p32h.interop.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Only the MMI fields the service uses are declared. The name fields are read to be returned in every
 * {@code memberId.forVendors} entry and nowhere else ({@link #toString()} leaves them out); SSN, gender, PCP, group
 * name, MBI and the agency numbers are dropped during deserialization and can never be logged or returned by accident.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiMember(String memberId, String subscriberMemberId, String memberDob, String legacyMemberId,
        String memberFirstName, String memberMiddleName, String memberLastName,
        String company, String lineOfBusiness, String restrictedData, String migrationEffectiveDate,
        List<MmiCoverage> coverage) {

    @Override
    public String toString() {
        return "MmiMember[memberId=" + memberId + ", company=" + company + ", lob=" + lineOfBusiness
                + ", spans=" + (coverage == null ? 0 : coverage.size()) + "]";
    }
}
