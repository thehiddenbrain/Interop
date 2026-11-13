package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.Outcome;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import org.p32h.interop.coverage.MemberCoverageRow;
import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.MemberSelector;
import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoResponse;
import org.p32h.interop.mmi.MmiClient;
import org.p32h.interop.mmi.MmiCoverage;
import org.p32h.interop.mmi.MmiMember;
import org.p32h.interop.mmi.MmiProperties;
import org.p32h.interop.mmi.MmiRecordMapper;
import org.p32h.interop.mmi.MmiResponse;
import org.p32h.interop.mmi.MmiResult;
import org.p32h.interop.vendor.Payer;
import org.p32h.interop.vendor.VendorFormatter;
import org.p32h.interop.vendor.VendorIdFormat;
import org.p32h.interop.vendor.VendorRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ACTIVE or INACTIVE, the message, the coverage dates and the coverageId all come from the member's rows in the
 * coverage view; MMI tells who the member is. The row shown is the one covering the date of service, else the most
 * recent one.
 */
class ResolutionServiceCoverageRowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));
    /** The id as the EMR typed it, and the same member's id as MMI stores it: the view is asked with the stored one. */
    private static final String SENT = "TESTMEMBER";
    private static final String STORED = "TEST  MEMBER";

    /** The member ids the coverage view was asked for, in order. */
    private final List<String> asked = new ArrayList<>();

    private static MemberCoverageRow row(String eff, String end) {
        return new MemberCoverageRow(STORED, LocalDate.parse(eff), LocalDate.parse(end));
    }

    /** A service whose member lookup returns one THP member with the given MMI coverage, and whose coverage view holds the given rows. */
    private ResolutionService service(List<MmiCoverage> mmiCoverage, List<MemberCoverageRow> rows) {
        Map<String, VendorConfig> table = Map.of("MHK", new VendorConfig("MHK", VendorIdFormat.SPACED_14, null));
        MemberIdProperties properties = new MemberIdProperties(new MemberIdProperties.DateOfService(10, "America/New_York"), 125, 24, table);
        MmiClient mmi = new MmiClient() {
            @Override
            public MmiResult search(String memberId, String correlationId) {
                List<MmiMember> members = mmiCoverage == null ? List.of()
                        : List.of(new MmiMember(STORED, null, null, null, "Taylor", null, "Morgan", "THP", "MCR", null, null, mmiCoverage));
                return new MmiResult("INTEROP-TEST", 200, new MmiResponse("INTEROP", "INT", "INTEROP-TEST", null, members));
            }

            @Override
            public String kind() {
                return "TEST";
            }
        };
        MmiProperties mmiProperties = new MmiProperties("http://mmi.test", "/master/member/v1", "INTEROP", "INT", Duration.ofSeconds(2),
                Duration.ofSeconds(5), "N", List.of("ERROR"), false, new MmiProperties.Stub(false, ""));
        MemberInfoClient noPlan = new MemberInfoClient() {
            @Override
            public MemberInfoResponse lookup(String memberId, LocalDate dateOfService, String correlationId) {
                return new MemberInfoResponse(null, List.of());
            }

            @Override
            public String kind() {
                return "TEST";
            }
        };
        return new ResolutionService(new RequestValidator(properties, CLOCK), new VendorRegistry(table, new Payer("Point32Health", "Point32Health")),
                mmi, mmiProperties, new MmiRecordMapper(), new MemberSelector(new CoverageEvaluator()), new VendorFormatter(), noPlan,
                memberId -> {
                    asked.add(memberId);
                    return rows;
                },
                new LineOfBusinessDeriver(), properties, CLOCK);
    }

    private static MemberResolutionRequest on(String dateOfService, String dateOfServiceEnd) {
        return new MemberResolutionRequest("ONYX", "EXT", "TEST-1", SENT, dateOfService, dateOfServiceEnd, null);
    }

    private static List<MmiCoverage> mmi(String eff, String end) {
        return List.of(new MmiCoverage(eff, end, "g", "N"));
    }

    /** What the service logged while the call ran. */
    private static List<String> logged(Runnable call) {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger serviceLog = (Logger) LoggerFactory.getLogger(ResolutionService.class);
        serviceLog.addAppender(logs);
        try {
            call.run();
        } finally {
            serviceLog.detachAppender(logs);
        }
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void theDatesAndTheIdAreTheRowsNotMmis() {
        MemberResolutionResponse r = service(mmi("01/01/2024", "12/31/2026"), List.of(row("2023-04-01", "3999-12-31")))
                .resolve(on("2026-10-15", null), "corr-1");
        assertThat(r.outcome()).as("the row covers the date").isEqualTo(Outcome.ACTIVE);
        assertThat(r.message()).isEqualTo("Member found; coverage active on 2026-10-15");
        assertThat(r.coverage().active()).isTrue();
        assertThat(r.coverage().effectiveDate()).as("the row's start, not MMI's 2024-01-01").isEqualTo(LocalDate.parse("2023-04-01"));
        assertThat(r.coverage().endDate()).as("the row's open end, not MMI's 2026-12-31").isEqualTo(LocalDate.parse("3999-12-31"));
        assertThat(r.coverage().coverageId()).as("the stored id without its spaces, then the row's two dates as yyyyMMdd")
                .isEqualTo("TESTMEMBER" + "20230401" + "39991231");
        assertThat(asked).as("asked once, with the id as MMI stores it, not as it was sent").containsExactly(STORED);
    }

    @Test
    void ofSeveralRowsCoveringTheDateTheOneEndingLastThenStartingLast() {
        List<MemberCoverageRow> overlapping = List.of(row("2020-01-01", "2026-12-31"), row("2025-01-01", "3999-12-31"), row("2026-01-01", "2026-11-30"));
        MemberResolutionResponse r = service(mmi("01/01/2020", null), overlapping).resolve(on("2026-10-15", null), "corr-1");
        assertThat(r.coverage().effectiveDate()).as("the open-ended row ends last").isEqualTo(LocalDate.parse("2025-01-01"));
        List<MemberCoverageRow> sameEnd = List.of(row("2020-01-01", "2026-12-31"), row("2026-01-01", "2026-12-31"));
        assertThat(service(mmi("01/01/2020", null), sameEnd).resolve(on("2026-10-15", null), "corr-1").coverage().effectiveDate())
                .as("the same end: the later start").isEqualTo(LocalDate.parse("2026-01-01"));
    }

    @Test
    void whenNoRowCoversTheDateTheMostRecentRowIsShown() {
        List<MemberCoverageRow> ended = List.of(row("2020-01-01", "2022-12-31"), row("2025-01-01", "2026-07-31"), row("2023-01-01", "2024-12-31"));
        MemberResolutionResponse r = service(mmi("01/01/2020", "07/31/2026"), ended).resolve(on("2026-10-15", null), "corr-1");
        assertThat(r.outcome()).isEqualTo(Outcome.INACTIVE);
        assertThat(r.coverage().active()).isFalse();
        assertThat(r.coverage().effectiveDate()).as("neither the first nor the last of the list").isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(r.coverage().endDate()).isEqualTo(LocalDate.parse("2026-07-31"));
        assertThat(r.coverage().coverageId()).isEqualTo("TESTMEMBER" + "20250101" + "20260731");
    }

    @Test
    void theRowsDecideActiveOrInactiveNotMmi() {
        // MMI has the coverage ended; the coverage view holds an open-ended row
        ResolutionService ended = service(mmi("01/01/2020", "07/31/2026"), List.of(row("2020-01-01", "3999-12-31")));
        List<String> log = logged(() -> {
            MemberResolutionResponse active = ended.resolve(on("2026-10-15", null), "corr-1");
            assertThat(active.outcome()).as("a row covers the date").isEqualTo(Outcome.ACTIVE);
            assertThat(active.message()).isEqualTo("Member found; coverage active on 2026-10-15");
            assertThat(active.coverage().active()).isTrue();
            assertThat(active.coverage().endDate()).isEqualTo(LocalDate.parse("3999-12-31"));
        });
        assertThat(log).as("the two sources disagree, and the log says so")
                .anySatisfy(m -> assertThat(m).contains("marker=COVERAGE_STATUS_DIFFERS", "memberId=" + STORED, "dos=2026-10-15"));
        // MMI has the member covered; the only row ended before the date
        ResolutionService covered = service(mmi("01/01/2020", null), List.of(row("2020-01-01", "2025-12-31")));
        log = logged(() -> {
            MemberResolutionResponse inactive = covered.resolve(on("2026-10-15", null), "corr-1");
            assertThat(inactive.outcome()).as("no row covers the date").isEqualTo(Outcome.INACTIVE);
            assertThat(inactive.message()).isEqualTo("Member found; coverage ended before 2026-10-15");
            assertThat(inactive.coverage().active()).isFalse();
            assertThat(inactive.coverage().endDate()).as("the most recent row").isEqualTo(LocalDate.parse("2025-12-31"));
        });
        assertThat(log).anySatisfy(m -> assertThat(m).contains("marker=COVERAGE_STATUS_DIFFERS"));
        // both say covered: nothing to report
        ResolutionService agreeing = service(mmi("01/01/2020", null), List.of(row("2020-01-01", "3999-12-31")));
        assertThat(logged(() -> agreeing.resolve(on("2026-10-15", null), "corr-1")))
                .noneSatisfy(m -> assertThat(m).contains("marker=COVERAGE_STATUS_DIFFERS"));
    }

    @Test
    void theReasonOfAnInactiveAnswerIsReadFromTheRows() {
        List<MmiCoverage> covered = mmi("01/01/2020", null);   // MMI has the member covered throughout
        assertThat(service(covered, List.of(row("2027-01-01", "3999-12-31"))).resolve(on("2026-10-15", null), "corr-1").message())
                .isEqualTo("Member found; coverage not yet effective on 2026-10-15");
        MemberResolutionResponse gap = service(covered, List.of(row("2024-01-01", "2026-06-30"), row("2027-01-01", "3999-12-31")))
                .resolve(on("2026-10-15", null), "corr-1");
        assertThat(gap.outcome()).isEqualTo(Outcome.INACTIVE);
        assertThat(gap.message()).isEqualTo("Member found; no coverage on 2026-10-15 (gap between coverage periods)");
        assertThat(gap.coverage().effectiveDate()).as("the most recent row: the one that ends last").isEqualTo(LocalDate.parse("2027-01-01"));
    }

    @Test
    void withoutARowTheMemberIsInactiveAndTheBlockCarriesTheFlagOnly() {
        ResolutionService service = service(mmi("01/01/2020", null), List.of());   // MMI has the member covered
        List<String> log = logged(() -> {
            MemberResolutionResponse r = service.resolve(on("2026-10-15", null), "corr-1");
            assertThat(r.outcome()).as("the view has no coverage for the member").isEqualTo(Outcome.INACTIVE);
            assertThat(r.message()).isEqualTo("Member found; no coverage on record");
            assertThat(r.coverage().active()).isFalse();
            assertThat(r.coverage().coverageId()).isNull();
            assertThat(r.coverage().effectiveDate()).isNull();
            assertThat(r.coverage().endDate()).isNull();
            assertThat(r.memberId().forVendors()).as("the ids are returned all the same").hasSize(1);
        });
        assertThat(log).as("the missing row is logged with its marker")
                .anySatisfy(m -> assertThat(m).contains("marker=COVERAGE_ROW_NOT_FOUND", "memberId=" + STORED));
    }

    @Test
    void aPeriodOfServiceTakesTheRowCoveringItsFirstDay() {
        List<MemberCoverageRow> rows = List.of(row("2026-01-01", "2026-10-17"), row("2026-10-18", "3999-12-31"));
        MemberResolutionResponse r = service(mmi("01/01/2026", null), rows).resolve(on("2026-10-15", "2026-10-20"), "corr-1");
        assertThat(r.message()).as("the two rows touch: one continuous coverage").isEqualTo("Member found; coverage active from 2026-10-15 to 2026-10-20");
        assertThat(r.coverage().endDate()).as("the row covering 2026-10-15").isEqualTo(LocalDate.parse("2026-10-17"));
        // the rows stop before the period does, whatever MMI has
        MemberResolutionResponse cut = service(mmi("01/01/2026", null), List.of(row("2026-01-01", "2026-10-17")))
                .resolve(on("2026-10-15", "2026-10-20"), "corr-1");
        assertThat(cut.outcome()).isEqualTo(Outcome.ACTIVE);
        assertThat(cut.message()).isEqualTo("Member found; coverage active on 2026-10-15; ends 2026-10-17, before 2026-10-20");
    }

    @Test
    void theViewIsNotReadWhenNoMemberWasIdentified() {
        MemberResolutionResponse r = service(null, List.of(row("2020-01-01", "3999-12-31"))).resolve(on("2026-10-15", null), "corr-1");
        assertThat(r.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(r.coverage()).isNull();
        assertThat(asked).isEmpty();
    }
}
