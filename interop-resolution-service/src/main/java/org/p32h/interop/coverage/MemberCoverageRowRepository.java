package org.p32h.interop.coverage;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the coverage view: one query, every row of one member. The transaction is read only and limited
 * to two seconds, which bounds the query. A database that cannot be reached or does not answer in time fails the
 * answer with 503 {@code COVERAGE_LOOKUP_UNAVAILABLE} (see {@code ApiExceptionHandler}).
 */
@Transactional(readOnly = true, timeout = 2)
public interface MemberCoverageRowRepository extends Repository<MemberCoverageRow, MemberCoverageRow.Key> {

    /** @param memberId the member id exactly as MMI stores it (the resolved id); the view holds the same format */
    List<MemberCoverageRow> findByMemberId(String memberId);
}
