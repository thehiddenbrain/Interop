package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The member's plan as the member information service returns it, every field as sent. The line of business is derived
 * from these fields. Plan dates come as date-times (for example {@code 2024-02-01T05:00:00.000+00:00}) and are kept as text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberPlan(
        String benefitId,
        String businessLineKey,
        String businessTypeIndicator,
        String carrier,
        String carrierCode,
        String gridId,
        String groupId,
        String ratingState,
        String homeGroupId,
        String network,
        String networkId,
        String planCode,
        String planEndDate,
        String planName,
        String planStartDate,
        String planType,
        String productCode,
        String sourceSystemId,
        String subsidiary,
        String voidFlag,
        String ipa,
        String hierarchyLineOfBusiness,
        String hierarchyCompany,
        String hierarchyCompanyDesc,
        String lineOfBusinessDesc) {

    /** A plan flagged void ({@code voidFlag} Y) is not the member's plan. */
    public boolean voided() {
        return voidFlag != null && voidFlag.strip().equalsIgnoreCase("Y");
    }
}
