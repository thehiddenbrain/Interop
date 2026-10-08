package org.p32h.interop.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.memberinfo.MemberPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The line of business Onyx routes on, derived from the member's coverage records as the member information service
 * returns them (about the last five years). Only the records whose plan start and end dates include the date of service
 * are read; the rules run on those.
 *
 * <p>The rules are the member portal's "ES Members" criteria, read against a coverage record: {@code sourceSysId} is the
 * record's {@code sourceSystemId}, {@code coverage.subsidiary} its {@code subsidiary} and {@code coverage.product} its
 * {@code productCode}. SCO is read from {@code businessTypeIndicator} and {@code planCode}, and is checked first. Codes
 * are compared ignoring case and surrounding spaces.
 * <pre>
 *   SCO             businessTypeIndicator SH and a planCode with SCO in it
 *   D-SNP           sourceSystemId 2064 and productCode DMA
 *   MA-TOGETHER     subsidiary THPPMA, sourceSystemId 2026 and productCode PL or GT
 *   RI-TOGETHER     subsidiary THPPRI and sourceSystemId 2048
 *   MA-QHP   subsidiary THPPMA, sourceSystemId 2026 and productCode NS or SB
 * </pre>
 * When several records cover the date, the first one a rule matches gives the value; if they give different values a
 * warning is logged ({@code marker=LOB_SEVERAL_ON_DATE}). A member with no record covering the date, or whose records match
 * no rule (TMP, HPHC and the other populations whose rules are still to come), keeps the line of business on the member
 * record (from MMI), logged with {@code marker=LOB_NOT_DERIVED}. TMP members will also need a database lookup; it belongs
 * here, beside the rules.
 */
public class LineOfBusinessDeriver {

    private static final Logger log = LoggerFactory.getLogger(LineOfBusinessDeriver.class);

    public static final String SCO = "SCO";
    public static final String DSNP = "D-SNP";
    public static final String MA_TOGETHER = "MA-TOGETHER";
    public static final String RI_TOGETHER = "RI-TOGETHER";
    public static final String MA_QHP_DIRECT = "MA-QHP";

    /** One rule: the line of business when the record's source system, subsidiary and product match; a null condition matches anything. */
    private record Rule(String lineOfBusiness, String sourceSystemId, String subsidiary, Set<String> products) {

        boolean matches(MemberPlan record) {
            String product = code(record.productCode());
            return sourceSystemId.equals(code(record.sourceSystemId()))
                    && (subsidiary == null || subsidiary.equals(code(record.subsidiary())))
                    && (products == null || (product != null && products.contains(product)));
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule(DSNP, "2064", null, Set.of("DMA")),
            new Rule(MA_TOGETHER, "2026", "THPPMA", Set.of("PL", "GT")),
            new Rule(RI_TOGETHER, "2048", "THPPRI", null),
            new Rule(MA_QHP_DIRECT, "2026", "THPPMA", Set.of("NS", "SB")));

    /**
     * @param record          the member record MMI returned for the member
     * @param coverageRecords the member's coverage records from the member information service, void ones already left out
     * @param dateOfService   the date of service (the first day of a period)
     */
    public String derive(MemberRecord record, List<MemberPlan> coverageRecords, LocalDate dateOfService) {
        String fromRecord = record.lineOfBusiness();
        List<MemberPlan> covering = coverageRecords.stream().filter(c -> c.covers(dateOfService)).toList();
        if (covering.isEmpty()) {
            log.info("marker=LOB_NOT_DERIVED memberId={} dos={} coverageRecords={} covering=0; lob={} from the member record",
                    record.storedMemberId(), dateOfService, coverageRecords.size(), fromRecord);
            return fromRecord;
        }
        MemberPlan matched = null;
        String lob = null;
        for (MemberPlan c : covering) {
            String value = lineOfBusinessOf(c);
            if (value == null) {
                continue;
            }
            if (lob == null) {
                matched = c;
                lob = value;
            } else if (!lob.equals(value)) {
                log.warn("marker=LOB_SEVERAL_ON_DATE memberId={} dos={} first={} also={}: the first is used", record.storedMemberId(),
                        dateOfService, lob, value);
            }
        }
        if (lob == null) {
            MemberPlan c = covering.get(0);
            log.info("marker=LOB_NOT_DERIVED memberId={} dos={} covering={} sourceSystemId={} subsidiary={} productCode={} planCode={} "
                            + "businessTypeIndicator={} hierarchyLineOfBusiness={} lineOfBusinessDesc={}; no rule matches, lob={} from the member record",
                    record.storedMemberId(), dateOfService, covering.size(), c.sourceSystemId(), c.subsidiary(), c.productCode(),
                    c.planCode(), c.businessTypeIndicator(), c.hierarchyLineOfBusiness(), c.lineOfBusinessDesc(), fromRecord);
            return fromRecord;
        }
        log.info("lob derived memberId={} dos={} lob={} sourceSystemId={} subsidiary={} productCode={} planStartDate={} planEndDate={}",
                record.storedMemberId(), dateOfService, lob, matched.sourceSystemId(), matched.subsidiary(), matched.productCode(),
                matched.planStartDate(), matched.planEndDate());
        return lob;
    }

    /** The line of business the first matching rule gives this record, or null when none matches. */
    private static String lineOfBusinessOf(MemberPlan record) {
        if (isSco(record)) {
            return SCO;
        }
        for (Rule rule : RULES) {
            if (rule.matches(record)) {
                return rule.lineOfBusiness();
            }
        }
        return null;
    }

    /** SCO: businessTypeIndicator SH and a plan code with SCO in it. */
    private static boolean isSco(MemberPlan record) {
        String planCode = code(record.planCode());
        return "SH".equals(code(record.businessTypeIndicator())) && planCode != null && planCode.contains("SCO");
    }

    private static String code(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
