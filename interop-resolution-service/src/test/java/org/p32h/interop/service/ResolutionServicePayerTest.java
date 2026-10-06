package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.MemberResolutionResponse.VendorMemberId;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.config.MemberIdProperties.PayerConfig;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.MemberSelector;
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
 * The payer in each vendor entry, straight through the service, with an id and a name that differ so a swap would show:
 * the vendor's own for the member's company, the default otherwise, and a warning when a vendor that keys the payer on
 * the company has no entry for the member's.
 */
class ResolutionServicePayerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));
    private static final Payer DEFAULT = new Payer("DEFAULT-ID", "Default payer name");

    /** A service whose member lookup always returns one member of {@code company}, covered from 2020 on. */
    private static ResolutionService serviceFor(String company) {
        Map<String, VendorConfig> table = new LinkedHashMap<>();
        table.put("EVICORE", new VendorConfig("eviCore", VendorIdFormat.COMPACT_11,
                Map.of("THP", new PayerConfig("THP-ID", "THP payer name"))));
        table.put("MHK", new VendorConfig("MHK", VendorIdFormat.SPACED_14, null));
        MemberIdProperties properties = new MemberIdProperties(new MemberIdProperties.DateOfService(10, "America/New_York"), 125, table);
        MmiClient mmi = new MmiClient() {
            @Override
            public MmiResult search(String memberId, LocalDate dateOfService, String correlationId) {
                MmiMember member = new MmiMember("TESTMEMBER", null, null, null, company, "COM", null, null,
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
        return new ResolutionService(new RequestValidator(properties, CLOCK), new VendorRegistry(table, DEFAULT), mmi, mmiProperties,
                new MmiRecordMapper(), new MemberSelector(new CoverageEvaluator()), new VendorFormatter());
    }

    private static MemberResolutionResponse resolve(String company) {
        return serviceFor(company).resolve(new MemberResolutionRequest("ONYX", "EXT", "TEST-1", "TESTMEMBER", "2026-10-15", null, null), "corr-1");
    }

    private static VendorMemberId entry(MemberResolutionResponse r, String vendor) {
        return r.memberId().forVendors().stream().filter(v -> v.vendor().equals(vendor)).findFirst().orElseThrow();
    }

    private static ListAppender<ILoggingEvent> captureServiceLog() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ResolutionService.class)).addAppender(logs);
        return logs;
    }

    private static void release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(ResolutionService.class)).detachAppender(logs);
    }

    @Test
    void idAndNameLandInTheirOwnFields() {
        ListAppender<ILoggingEvent> logs = captureServiceLog();
        try {
            MemberResolutionResponse r = resolve("THP");
            assertThat(entry(r, "EVICORE")).extracting(VendorMemberId::payerId, VendorMemberId::payerName)
                    .as("the vendor's own payer for the member's company").containsExactly("THP-ID", "THP payer name");
            assertThat(entry(r, "MHK")).extracting(VendorMemberId::payerId, VendorMemberId::payerName)
                    .as("a vendor without its own payer gets the default").containsExactly("DEFAULT-ID", "Default payer name");
            assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("VENDOR_PAYER_DEFAULTED"));
        } finally {
            release(logs);
        }
    }

    @Test
    void aCompanyTheVendorHasNoEntryForGetsTheDefaultAndAWarning() {
        ListAppender<ILoggingEvent> logs = captureServiceLog();
        try {
            MemberResolutionResponse r = resolve("NEWCO");
            assertThat(entry(r, "EVICORE")).extracting(VendorMemberId::payerId, VendorMemberId::payerName)
                    .containsExactly("DEFAULT-ID", "Default payer name");
            assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("VENDOR_PAYER_DEFAULTED"))
                    .as("one warning, for the vendor that keys on the company; none for MHK")
                    .singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).contains("vendor=EVICORE").contains("company=NEWCO"));
        } finally {
            release(logs);
        }
    }
}
