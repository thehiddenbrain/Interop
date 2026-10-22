package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.Outcome;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The recent-coverage window is the configured number of months back from today: the same member is INACTIVE or NOT_FOUND by it. */
class ResolutionServiceRecentCoverageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));

    /** A service whose member lookup returns one THP member covered 01/01/2020 to 07/31/2026, with the given window. */
    private static ResolutionService serviceWith(int recentCoverageMonths) {
        Map<String, VendorConfig> table = Map.of("MHK", new VendorConfig("MHK", VendorIdFormat.SPACED_14, null));
        MemberIdProperties properties = new MemberIdProperties(new MemberIdProperties.DateOfService(10, "America/New_York"), 125,
                recentCoverageMonths, table);
        MmiClient mmi = new MmiClient() {
            @Override
            public MmiResult search(String memberId, String correlationId) {
                MmiMember member = new MmiMember("TESTMEMBER", null, null, null, "Taylor", null, "Morgan", "THP", "MCR", null, null,
                        List.of(new MmiCoverage("01/01/2020", "07/31/2026", "g", "N")));
                return new MmiResult("INTEROP-TEST", 200, new MmiResponse("INTEROP", "INT", "INTEROP-TEST", null, List.of(member)));
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
                new LineOfBusinessDeriver(), properties, CLOCK);
    }

    private static MemberResolutionResponse resolve(int months) {
        return serviceWith(months).resolve(new MemberResolutionRequest("ONYX", "EXT", "TEST-1", "TESTMEMBER", "2026-10-15", null, null), "corr-1");
    }

    @Test
    void theWindowIsTheConfiguredMonthsBackFromToday() {
        MemberResolutionResponse wide = resolve(24);
        assertThat(wide.outcome()).as("coverage ended two months before today: inside 24 months").isEqualTo(Outcome.INACTIVE);
        assertThat(wide.coverage().endDate()).isEqualTo(LocalDate.parse("2026-07-31"));
        assertThat(resolve(3).outcome()).as("2026-07-31 is on or after 2026-07-03").isEqualTo(Outcome.INACTIVE);
        MemberResolutionResponse narrow = resolve(2);
        assertThat(narrow.outcome()).as("2026-07-31 is before 2026-08-03: nothing recent").isEqualTo(Outcome.NOT_FOUND);
        assertThat(narrow.memberId().resolved()).isNull();
        assertThat(narrow.coverage()).isNull();
    }
}
