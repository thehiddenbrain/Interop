package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One entry of the member information answer: the member id as requested and that member's coverage records (about the
 * last five years, each with the plan fields). The entry's {@code memberPlan} is not read: it is not the plan on the date
 * of service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberInfoMember(String requestId, String memberId, List<MemberPlan> coverageRecords) {
}
