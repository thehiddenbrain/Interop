package org.p32h.interop.coverage;

import java.util.List;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the coverage view: one query, every row of one member. The transaction is read only; its two
 * seconds are handed to the statement as the query timeout, so a query that runs longer is cancelled by the database.
 * A database that has stopped answering altogether cannot cancel anything: the driver's read timeout
 * ({@code oracle.jdbc.ReadTimeout} in application.yaml) ends that wait. Either way, and when no connection can be had,
 * the answer fails with 503 {@code COVERAGE_LOOKUP_UNAVAILABLE} (see {@code ResolutionService}).
 */
@Transactional(readOnly = true, timeout = 2)
public interface MemberCoverageRowRepository extends Repository<MemberCoverageRow, MemberCoverageRow.Key> {

    /** @param memberId the member id exactly as MMI stores it (the resolved id); the view holds the same format */
    List<MemberCoverageRow> findByMemberId(String memberId);
}
