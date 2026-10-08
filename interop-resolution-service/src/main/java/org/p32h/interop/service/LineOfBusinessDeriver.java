package org.p32h.interop.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.memberinfo.MemberPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The line of business Onyx routes on, derived from the member's plan as the member information service returns it.
 *
 * <p>The rules are the member portal's "ES Members" criteria, read against the plan: {@code sourceSysId} is the plan's
 * {@code sourceSystemId}, {@code coverage.subsidiary} its {@code subsidiary} and {@code coverage.product} its
 * {@code productCode}. Codes are compared ignoring case and surrounding spaces.
 * <pre>
 *   D-SNP           sourceSystemId 2064 and productCode DMA
 *   MA-TOGETHER     subsidiary THPPMA, sourceSystemId 2026 and productCode PL or GT
 *   RI-TOGETHER     subsidiary THPPRI and sourceSystemId 2048
 *   MA-QHP-DIRECT   subsidiary THPPMA, sourceSystemId 2026 and productCode NS or SB
 * </pre>
 * A member with no plan, or a plan no rule matches (TMP, SCO, HPHC and the other populations whose rules are still to
 * come), keeps the line of business on the member record (from MMI), logged with {@code marker=LOB_NOT_DERIVED} and the
 * plan fields. TMP members will also need a database lookup; it belongs here, beside the rules.
 */
public class LineOfBusinessDeriver {

    private static final Logger log = LoggerFactory.getLogger(LineOfBusinessDeriver.class);

    public static final String DSNP = "D-SNP";
    public static final String MA_TOGETHER = "MA-TOGETHER";
    public static final String RI_TOGETHER = "RI-TOGETHER";
    public static final String MA_QHP_DIRECT = "MA-QHP-DIRECT";

    /** One rule: the line of business when the plan's source system, subsidiary and product match; a null condition matches anything. */
    private record Rule(String lineOfBusiness, String sourceSystemId, String subsidiary, Set<String> products) {

        boolean matches(MemberPlan plan) {
            String product = code(plan.productCode());
            return sourceSystemId.equals(code(plan.sourceSystemId()))
                    && (subsidiary == null || subsidiary.equals(code(plan.subsidiary())))
                    && (products == null || (product != null && products.contains(product)));
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule(DSNP, "2064", null, Set.of("DMA")),
            new Rule(MA_TOGETHER, "2026", "THPPMA", Set.of("PL", "GT")),
            new Rule(RI_TOGETHER, "2048", "THPPRI", null),
            new Rule(MA_QHP_DIRECT, "2026", "THPPMA", Set.of("NS", "SB")));

    /**
     * @param record the member record MMI returned for the member
     * @param plan   the member's plan on the date of service, or null when the member information service has none
     */
    public String derive(MemberRecord record, MemberPlan plan) {
        String fromRecord = record.lineOfBusiness();
        if (plan == null) {
            log.info("marker=LOB_NOT_DERIVED memberId={} plan=none; lob={} from the member record", record.storedMemberId(), fromRecord);
            return fromRecord;
        }
        for (Rule rule : RULES) {
            if (rule.matches(plan)) {
                log.info("lob derived memberId={} lob={} sourceSystemId={} subsidiary={} productCode={}", record.storedMemberId(),
                        rule.lineOfBusiness(), plan.sourceSystemId(), plan.subsidiary(), plan.productCode());
                return rule.lineOfBusiness();
            }
        }
        log.info("marker=LOB_NOT_DERIVED memberId={} sourceSystemId={} subsidiary={} productCode={} planCode={} businessTypeIndicator={} "
                        + "hierarchyLineOfBusiness={} lineOfBusinessDesc={}; no rule matches, lob={} from the member record",
                record.storedMemberId(), plan.sourceSystemId(), plan.subsidiary(), plan.productCode(), plan.planCode(),
                plan.businessTypeIndicator(), plan.hierarchyLineOfBusiness(), plan.lineOfBusinessDesc(), fromRecord);
        return fromRecord;
    }

    private static String code(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
