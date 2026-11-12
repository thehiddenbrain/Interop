package org.p32h.interop.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The mapping and the query against a real table: the in-memory stand-in for the coverage view. The view has no
 * primary key, so the test that matters is that rows differing in one date only all come back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MemberCoverageRowRepositoryTest {

    @Autowired MemberCoverageRowRepository repository;
    @Autowired JdbcTemplate jdbc;

    private void insert(String memberId, String eff, String end) {
        jdbc.update("insert into member_coverage_rel_v (member_id, eff_date, end_date) values (?, ?, ?)",
                memberId, LocalDate.parse(eff), LocalDate.parse(end));
    }

    @Test
    void everyRowOfTheMemberAndOnlyThose() {
        insert("ROWS-A", "2024-01-01", "2024-12-31");
        insert("ROWS-A", "2025-01-01", "2025-06-30");
        insert("ROWS-A", "2025-01-01", "3999-12-31");   // the same member and start as the row above: only the end differs
        insert("ROWS-A", "2024-06-01", "3999-12-31");   // the same member and end as the row above: only the start differs
        insert("ROWS-B", "2025-01-01", "3999-12-31");

        List<MemberCoverageRow> rows = repository.findByMemberId("ROWS-A");
        assertThat(rows).extracting(r -> r.getEffDate() + ".." + r.getEndDate())
                .containsExactlyInAnyOrder("2024-01-01..2024-12-31", "2025-01-01..2025-06-30", "2025-01-01..3999-12-31", "2024-06-01..3999-12-31");
        assertThat(rows).allMatch(r -> r.getMemberId().equals("ROWS-A"));
        assertThat(repository.findByMemberId("ROWS-NONE")).isEmpty();
    }

    @Test
    void theMemberIdIsMatchedExactlyAsStored() {
        insert("ROWS  C", "2025-01-01", "3999-12-31");
        assertThat(repository.findByMemberId("ROWS  C")).as("spaces included").hasSize(1);
        assertThat(repository.findByMemberId("ROWSC")).as("no compacting on either side").isEmpty();
    }

    @Test
    void aRowCoversItsFirstAndLastDay() {
        MemberCoverageRow row = new MemberCoverageRow("ROWS-D", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(row.covers(LocalDate.parse("2026-01-01"))).isTrue();
        assertThat(row.covers(LocalDate.parse("2026-12-31"))).isTrue();
        assertThat(row.covers(LocalDate.parse("2025-12-31"))).isFalse();
        assertThat(row.covers(LocalDate.parse("2027-01-01"))).isFalse();
    }

    @Test
    void theStubMembersHaveTheirRows() {
        Integer rows = jdbc.queryForObject("select count(*) from member_coverage_rel_v where member_id not like 'ROWS%'", Integer.class);
        assertThat(rows).as("coverage-dev/data.sql was loaded").isGreaterThan(10);
    }
}
