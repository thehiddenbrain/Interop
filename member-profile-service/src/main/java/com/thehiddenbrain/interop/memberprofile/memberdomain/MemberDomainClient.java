package com.thehiddenbrain.interop.memberprofile.memberdomain;

import java.util.Optional;

/** Fetches a member, their rule facts and their family roster from the MemberDomain service. */
public interface MemberDomainClient {

    /** @return the member, or empty when MemberDomain does not know the id. */
    Optional<MemberDomainMember> findMember(String memberId);
}
