package org.p32h.interop.coverage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * One row of the coverage view {@code member_coverage_rel_v}: a member's coverage as the plan's coverage table holds
 * it. Read only. The view has no primary key; member id, effective date and end date together are unique, so the three
 * are the id. An open end is 12/31/3999, never null. ACTIVE or INACTIVE, the coverage dates and the coverageId of an
 * answer come from here.
 */
@Entity
@Immutable
@Table(name = "member_coverage_rel_v")
@IdClass(MemberCoverageRow.Key.class)
public class MemberCoverageRow {

    @Id
    @Column(name = "member_id")
    private String memberId;

    @Id
    @Column(name = "eff_date")
    private LocalDate effDate;

    @Id
    @Column(name = "end_date")
    private LocalDate endDate;

    protected MemberCoverageRow() {
    }

    public MemberCoverageRow(String memberId, LocalDate effDate, LocalDate endDate) {
        this.memberId = memberId;
        this.effDate = effDate;
        this.endDate = endDate;
    }

    public String getMemberId() {
        return memberId;
    }

    public LocalDate getEffDate() {
        return effDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    /** Whether the date falls within the row, both ends inclusive. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(effDate) && !date.isAfter(endDate);
    }

    /** The three columns that make a row unique. */
    public static class Key implements Serializable {

        private String memberId;
        private LocalDate effDate;
        private LocalDate endDate;

        public Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(memberId, k.memberId)
                    && Objects.equals(effDate, k.effDate) && Objects.equals(endDate, k.endDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(memberId, effDate, endDate);
        }
    }
}
