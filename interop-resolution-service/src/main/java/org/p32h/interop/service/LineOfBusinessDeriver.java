package org.p32h.interop.service;

import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.memberinfo.MemberPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The line of business Onyx routes on, derived from the member's plan as the member information service returns it.
 *
 * <p>The derivation rules are still to come. Until they do, the line of business on the member record (from MMI) is
 * returned unchanged, and the plan fields the rules are expected to read are logged with {@code marker=LOB_RULES_PENDING},
 * so a PQA run shows what each member's plan carries. TMP members will also need a database lookup; it belongs here,
 * beside the rules.
 */
public class LineOfBusinessDeriver {

    private static final Logger log = LoggerFactory.getLogger(LineOfBusinessDeriver.class);

    /**
     * @param record the member record MMI returned for the member
     * @param plan   the member's plan on the date of service, or null when the member information service has none
     */
    public String derive(MemberRecord record, MemberPlan plan) {
        String fromRecord = record.lineOfBusiness();
        if (plan == null) {
            log.info("marker=LOB_RULES_PENDING memberId={} plan=none; lob={} from the member record", record.storedMemberId(), fromRecord);
            return fromRecord;
        }
        log.info("marker=LOB_RULES_PENDING memberId={} hierarchyLineOfBusiness={} lineOfBusinessDesc={} hierarchyCompany={} "
                        + "subsidiary={} carrierCode={} productCode={} businessLineKey={} planCode={} planName={}; lob={} from the member record",
                record.storedMemberId(), plan.hierarchyLineOfBusiness(), plan.lineOfBusinessDesc(), plan.hierarchyCompany(),
                plan.subsidiary(), plan.carrierCode(), plan.productCode(), plan.businessLineKey(), plan.planCode(), plan.planName(),
                fromRecord);
        return fromRecord;
    }
}
