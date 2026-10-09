package org.p32h.interop.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * {@code productCode}. SCO is read from {@code businessTypeIndicator} and {@code planCode}, and is checked first; MA-HMO
 * and MA-PPO are read from {@code businessTypeIndicator}, {@code subsidiary} and {@code businessLineKey}, and come next.
 * Codes are compared ignoring case and surrounding spaces; the business line keys exactly as the four digits.
 * <pre>
 *   SCO             businessTypeIndicator SH and a planCode with SCO in it
 *   MA-HMO          businessTypeIndicator SH, subsidiary TAHMO and businessLineKey 0039 or 0099
 *   MA-PPO          businessTypeIndicator SH, subsidiary TAHMO and businessLineKey 0235
 *   D-SNP           sourceSystemId 2064 and productCode DMA
 *   MA-TOGETHER     subsidiary THPPMA, sourceSystemId 2026 and productCode PL or GT
 *   RI-TOGETHER     subsidiary THPPRI and sourceSystemId 2048
 *   MA-QHP          subsidiary THPPMA, sourceSystemId 2026 and productCode NS or SB
 * </pre>
 * When several records cover the date, the first one a rule matches gives the value; if they give different values a
 * warning is logged ({@code marker=LOB_SEVERAL_ON_DATE}). A member with no record covering the date, or whose records match
 * no rule (HPHC, commercial and the other populations whose rules are still to come), keeps the line of business on the
 * member record (from MMI), logged with {@code marker=LOB_NOT_DERIVED}. TMP members are covered by MA-HMO and MA-PPO; their
 * IPA variant is pending (see {@code tahmoMedicareAdvantage}).
 */
public class LineOfBusinessDeriver {

    private static final Logger log = LoggerFactory.getLogger(LineOfBusinessDeriver.class);

    public static final String SCO = "SCO";
    public static final String MA_HMO = "MA-HMO";
    public static final String MA_PPO = "MA-PPO";
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
     * @param memberId        the resolved member id, for the log lines
     * @param fromRecord      the line of business on the member record (MMI's), kept when no rule gives one
     * @param coverageRecords the member's coverage records from the member information service, void ones already left out
     * @param dateOfService   the date of service (the first day of a period)
     */
    public String derive(String memberId, String fromRecord, List<MemberPlan> coverageRecords, LocalDate dateOfService) {
        List<MemberPlan> covering = coverageRecords.stream().filter(c -> c.covers(dateOfService)).toList();
        if (covering.isEmpty()) {
            log.info("marker=LOB_NOT_DERIVED memberId={} dos={} coverageRecords={} covering=0; lob={} from the member record",
                    memberId, dateOfService, coverageRecords.size(), fromRecord);
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
                log.warn("marker=LOB_SEVERAL_ON_DATE memberId={} dos={} first={} also={}: the first is used", memberId,
                        dateOfService, lob, value);
            }
        }
        if (lob == null) {
            MemberPlan c = covering.get(0);
            log.info("marker=LOB_NOT_DERIVED memberId={} dos={} covering={} sourceSystemId={} subsidiary={} productCode={} planCode={} "
                            + "businessTypeIndicator={} hierarchyLineOfBusiness={} lineOfBusinessDesc={}; no rule matches, lob={} from the member record",
                    memberId, dateOfService, covering.size(), c.sourceSystemId(), c.subsidiary(), c.productCode(),
                    c.planCode(), c.businessTypeIndicator(), c.hierarchyLineOfBusiness(), c.lineOfBusinessDesc(), fromRecord);
            return fromRecord;
        }
        log.info("lob derived memberId={} dos={} lob={} sourceSystemId={} subsidiary={} productCode={} planStartDate={} planEndDate={}",
                memberId, dateOfService, lob, matched.sourceSystemId(), matched.subsidiary(), matched.productCode(),
                matched.planStartDate(), matched.planEndDate());
        return lob;
    }

    /** The line of business the first matching rule gives this record, or null when none matches. */
    private static String lineOfBusinessOf(MemberPlan record) {
        if (isSco(record)) {
            return SCO;
        }
        String medicareAdvantage = tahmoMedicareAdvantage(record);
        if (medicareAdvantage != null) {
            return medicareAdvantage;
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

    /**
     * MA-HMO or MA-PPO for a TAHMO Medicare Advantage record: businessTypeIndicator SH and subsidiary TAHMO, then
     * businessLineKey 0039 or 0099 gives MA-HMO and 0235 gives MA-PPO. Null for any other record or business line key.
     *
     * <p>PENDING, to build with CRC: check the record's {@code ipa} against the CRC RTU tables. For an IPA member MA-HMO may
     * become an MA-HMO IPA value and MA-PPO an MA-PPO IPA value; the exact values and how to read the tables are to be
     * confirmed with CRC. The lookup goes here, applied to the value this method returns.
     */
    private static String tahmoMedicareAdvantage(MemberPlan record) {
        if (!"SH".equals(code(record.businessTypeIndicator())) || !"TAHMO".equals(code(record.subsidiary()))) {
            return null;
        }
        String businessLineKey = code(record.businessLineKey());
        if ("0039".equals(businessLineKey) || "0099".equals(businessLineKey)) {
            return MA_HMO;
        }
        if ("0235".equals(businessLineKey)) {
            return MA_PPO;
        }
        return null;
    }

    private static String code(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
