package org.point32health.interop.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * One MMI member record reduced to what the service needs. Names, SSN and the rest never get this far.
 *
 * @param storedMemberId   the id exactly as MMI stores it (returned to Onyx verbatim)
 * @param matchKey         the stored id without whitespace, upper-cased, for comparisons
 * @param company          THP or HPHC
 * @param lineOfBusiness   e.g. MCR, COM, PP (passed through)
 * @param legacyMatchKey   the pre-conversion id's match key, or null
 * @param dateOfBirth      parsed, or null when absent or unreadable
 * @param spans            readable, non-void coverage spans
 * @param unreadableSpans  spans skipped because a date could not be read (logged, counted)
 */
public record MemberRecord(String storedMemberId, String matchKey, String company, String lineOfBusiness,
        String legacyMatchKey, LocalDate dateOfBirth, List<CoverageSpan> spans, int unreadableSpans) {

    public static String matchKeyOf(String id) {
        if (id == null) {
            return null;
        }
        String key = id.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    public MemberRecord withSpans(List<CoverageSpan> merged, int unreadable) {
        return new MemberRecord(storedMemberId, matchKey, company, lineOfBusiness, legacyMatchKey, dateOfBirth, merged, unreadable);
    }
}
