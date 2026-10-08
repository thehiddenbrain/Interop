package org.p32h.interop.memberinfo;

import java.time.LocalDate;
import reactor.core.publisher.Mono;

/** The member information service: the member's coverage records. One implementation talks HTTP; one serves fixtures. */
public interface MemberInfoClient {

    /**
     * @param memberId      the member id as the member index stores it (the resolved id)
     * @param dateOfService the date of service (the first day of a period), sent as dos
     * @param correlationId the Onyx correlation id, forwarded as a header
     * @return the answer; a 404 comes back as an answer with no members. Fails with {@link MemberInfoException} when the
     *         service cannot be reached, rejects the call or answers unreadably.
     */
    Mono<MemberInfoResponse> lookup(String memberId, LocalDate dateOfService, String correlationId);

    /** {@code REST} or {@code STUB}, shown in the startup report. */
    String kind();
}
