package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * One coverage record as the member information service returns it in {@code coverageRecords}: the plan fields, every
 * field as sent (the same fields as its {@code memberPlan}). The line of business is derived from the record that covers
 * the date of service. Plan dates come as date-times (for example {@code 2024-02-01T05:00:00.000+00:00}) and are kept as
 * text; {@link #covers} reads their date as written.
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

    /** A record flagged void ({@code voidFlag} Y) is not the member's coverage. */
    public boolean voided() {
        return voidFlag != null && voidFlag.strip().equalsIgnoreCase("Y");
    }

    /**
     * Whether {@code date} falls within the plan start and end dates, both inclusive. No end date is open-ended; a missing
     * or unreadable start date, or an unreadable end date, covers nothing.
     */
    public boolean covers(LocalDate date) {
        LocalDate start = dateOf(planStartDate);
        if (start == null || date.isBefore(start)) {
            return false;
        }
        if (planEndDate == null || planEndDate.isBlank()) {
            return true;
        }
        LocalDate end = dateOf(planEndDate);
        return end != null && !date.isAfter(end);
    }

    /**
     * The date as written, the leading {@code yyyy-MM-dd}: {@code 2024-02-01T05:00:00.000+00:00}, {@code ...+0000},
     * {@code 2024-02-01T00:00:00} and {@code 2024-02-01} are all 2024-02-01. Null when blank or not a date.
     */
    static LocalDate dateOf(String value) {
        if (value == null || value.strip().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
