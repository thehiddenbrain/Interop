package org.p32h.interop.mmi;

import org.p32h.interop.domain.CoverageSpan;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.support.Masking;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MMI member DTO -> domain record. Dates are parsed strictly; what cannot be read is skipped, logged and counted. */
public final class MmiRecordMapper {

    private static final Logger log = LoggerFactory.getLogger(MmiRecordMapper.class);

    public MemberRecord toRecord(MmiMember m) {
        String stored = m.memberId();
        String matchKey = MemberRecord.matchKeyOf(stored);
        String company = m.company() == null ? "" : m.company().strip().toUpperCase(Locale.ROOT);
        if (company.isEmpty() && matchKey != null) {
            company = matchKey.startsWith("HP") ? "HPHC" : "THP";
            log.warn("marker=COMPANY_INFERRED company missing on MMI record {}; inferred {}", Masking.memberId(stored), company);
        }
        LocalDate dob = MmiDates.parseOrNull(m.memberDob());
        if (dob == null && m.memberDob() != null && !m.memberDob().isBlank()) {
            log.warn("marker=DOB_UNREADABLE memberDob on record {} is not MM/dd/yyyy", Masking.memberId(stored));
        }
        List<CoverageSpan> spans = new ArrayList<>();
        int unreadable = 0;
        for (MmiCoverage c : m.coverage() == null ? List.<MmiCoverage>of() : m.coverage()) {
            if (c == null) {
                continue;
            }
            if (c.voidFlag() != null && c.voidFlag().strip().equalsIgnoreCase("Y")) {
                continue;
            }
            LocalDate eff = MmiDates.parseOrNull(c.effDate());
            LocalDate end = MmiDates.isOpenEnd(c.endDate()) ? null : MmiDates.parseOrNull(c.endDate());
            boolean endUnreadable = !MmiDates.isOpenEnd(c.endDate()) && end == null;
            if (eff == null || endUnreadable || (end != null && end.isBefore(eff))) {
                unreadable++;
                log.warn("marker=UNREADABLE_SPAN record {} span effDate shape '{}' endDate shape '{}' skipped", Masking.memberId(stored),
                        Masking.shape(c.effDate()), Masking.shape(c.endDate()));
                continue;
            }
            spans.add(new CoverageSpan(eff, end));
        }
        return new MemberRecord(stored, matchKey, company,
                m.lineOfBusiness() == null ? null : m.lineOfBusiness().strip(),
                MemberRecord.matchKeyOf(m.legacyMemberId()), dob, List.copyOf(spans), unreadable);
    }
}
