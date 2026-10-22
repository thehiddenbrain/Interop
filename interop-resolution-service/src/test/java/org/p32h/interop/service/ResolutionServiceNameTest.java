package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.MemberResolutionResponse.HumanName;
import org.p32h.interop.api.MemberResolutionResponse.VendorMemberId;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.MemberName;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The member's name on every vendor entry, straight through the service: one FHIR HumanName built from the member
 * record's first, middle and last name; no entry and one warning when the record has no name; never a name in a log line.
 */
class ResolutionServiceNameTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));

    /** A service whose member lookup always returns one THP member with the given name, covered from 2020 on. */
    private static ResolutionService serviceFor(String first, String middle, String last) {
        Map<String, VendorConfig> table = new LinkedHashMap<>();
        table.put("EVICORE", new VendorConfig("eviCore", VendorIdFormat.COMPACT_11, null));
        table.put("MHK", new VendorConfig("MHK", VendorIdFormat.SPACED_14, null));
        MemberIdProperties properties = new MemberIdProperties(new MemberIdProperties.DateOfService(10, "America/New_York"), 125, table);
        MmiClient mmi = new MmiClient() {
            @Override
            public MmiResult search(String memberId, LocalDate dateOfService, String correlationId) {
                MmiMember member = new MmiMember("TESTMEMBER", null, null, null, first, middle, last, "THP", "MCR", null, null,
                        List.of(new MmiCoverage("01/01/2020", null, "g", "N")));
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
                new LineOfBusinessDeriver());
    }

    private static MemberResolutionResponse resolve(String first, String middle, String last) {
        return serviceFor(first, middle, last).resolve(new MemberResolutionRequest("ONYX", "EXT", "TEST-1", "TESTMEMBER", "2026-10-15", null, null), "corr-1");
    }

    private static ListAppender<ILoggingEvent> captureLog() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("org.p32h.interop")).addAppender(logs);
        return logs;
    }

    private static void release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger("org.p32h.interop")).detachAppender(logs);
    }

    @Test
    void everyEntryCarriesOneHumanNameAndNoLogLineDoes() {
        ListAppender<ILoggingEvent> logs = captureLog();
        try {
            MemberResolutionResponse r = resolve(" Taylor ", "A", "Morgan");
            assertThat(r.memberId().forVendors()).hasSize(2).allSatisfy(e ->
                    assertThat(e.name()).as(e.vendor()).containsExactly(new HumanName("Taylor A Morgan", "Morgan", List.of("Taylor", "A"))));
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(m -> assertThat(m).doesNotContain("Taylor", "Morgan", "MEMBER_NAME_MISSING"));
        } finally {
            release(logs);
        }
    }

    @Test
    void theHumanNameFollowsWhatIsOnRecord() {
        assertThat(HumanName.of(new MemberName("Taylor", null, "Morgan")))
                .as("no middle name: one given name").containsExactly(new HumanName("Taylor Morgan", "Morgan", List.of("Taylor")));
        assertThat(HumanName.of(new MemberName("Taylor", null, null)))
                .as("no last name: no family").containsExactly(new HumanName("Taylor", null, List.of("Taylor")));
        assertThat(HumanName.of(new MemberName(null, null, "Morgan")))
                .as("no first name: no given").containsExactly(new HumanName("Morgan", "Morgan", null));
        assertThat(HumanName.of(new MemberName(null, "A", "Morgan")))
                .as("a middle name without a first name is not a given name: left out of text too").containsExactly(new HumanName("Morgan", "Morgan", null));
        assertThat(resolve(null, "A", "Morgan").memberId().forVendors()).extracting(VendorMemberId::name)
                .allSatisfy(n -> assertThat(n).containsExactly(new HumanName("Morgan", "Morgan", null)));
        assertThat(HumanName.of(null)).isNull();
        assertThat(resolve("Taylor", null, "Morgan").memberId().forVendors()).extracting(VendorMemberId::name)
                .allSatisfy(n -> assertThat(n).containsExactly(new HumanName("Taylor Morgan", "Morgan", List.of("Taylor"))));
    }

    @Test
    void aRecordWithoutANameGivesEntriesWithoutOneAndAWarning() {
        ListAppender<ILoggingEvent> logs = captureLog();
        try {
            MemberResolutionResponse r = resolve(" ", null, "");
            assertThat(r.memberId().forVendors()).hasSize(2).allSatisfy(e -> assertThat(e.name()).as(e.vendor()).isNull());
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .filteredOn(m -> m.contains("MEMBER_NAME_MISSING"))
                    .as("one warning for the answer, naming the member id").singleElement()
                    .satisfies(m -> assertThat(m).contains("memberId=TESTMEMBER"));
        } finally {
            release(logs);
        }
    }
}
