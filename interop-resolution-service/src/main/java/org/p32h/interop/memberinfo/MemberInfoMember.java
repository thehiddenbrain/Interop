package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One entry of the member information answer: the member id as requested and that member's plan. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberInfoMember(String requestId, String memberId, MemberPlan memberPlan) {
}
