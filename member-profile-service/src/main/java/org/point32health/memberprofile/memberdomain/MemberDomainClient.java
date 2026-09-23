package org.point32health.memberprofile.memberdomain;

import java.util.Optional;

/** Fetches a member, their rule facts and their family roster from the MemberDomain service. */
public interface MemberDomainClient {

    /**
     * @return the member, or empty when MemberDomain does not know the id
     * @throws org.point32health.memberprofile.common.MemberProfileException with
     *         {@code MEMBER_DOMAIN_UNREACHABLE} or {@code MEMBER_DOMAIN_ERROR} when the call fails
     */
    Optional<MemberDomainMember> findMember(String memberId);
}
