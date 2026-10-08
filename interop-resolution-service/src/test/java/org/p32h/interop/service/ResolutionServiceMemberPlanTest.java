package org.p32h.interop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.p32h.interop.api.ApiErrorResponse;
import org.p32h.interop.api.ApiExceptionHandler;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.MemberSelector;
import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoException;
import org.p32h.interop.memberinfo.MemberInfoMember;
import org.p32h.interop.memberinfo.MemberInfoResponse;
import org.p32h.interop.memberinfo.MemberPlan;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The member plan behind the line of business, straight through the service: asked for with the resolved id, the date of
 * service and the correlation id; until the rules arrive the member record's line of business is returned; a failure of
 * the member information service fails the answer with MMI's request id as traceId.
 */
class ResolutionServiceMemberPlanTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneId.of("America/New_York"));
    private static final String STORED = "TESTMEMBER";

    private record Asked(String memberId, LocalDate dateOfService, String correlationId) {
    }

    private final List<Asked> asked = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("org.p32h.interop.service")).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger("org.p32h.interop.service")).detachAppender(logs);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A plan with only the void flag and the hierarchy line of business set. */
    private static MemberPlan plan(String hierarchyLineOfBusiness) {
        return new MemberPlan(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "N", null, hierarchyLineOfBusiness, null, null, null);
    }

    /** A service whose MMI always returns one THP member with line of business MCR, and whose member plan lookup answers {@code plans}. */
    private ResolutionService service(Function<String, MemberInfoResponse> plans) {
        Map<String, VendorConfig> table = Map.of("MHK", new VendorConfig("MHK", VendorIdFormat.SPACED_14, null));
        MemberIdProperties properties = new MemberIdProperties(new MemberIdProperties.DateOfService(10, "America/New_York"), 125, table);
        MmiClient mmi = new MmiClient() {
            @Override
            public MmiResult search(String memberId, LocalDate dateOfService, String correlationId) {
                MmiMember member = new MmiMember(STORED, null, null, null, "THP", "MCR", null, null,
                        List.of(new MmiCoverage("01/01/2020", null, "g", "N")));
                return new MmiResult("INTEROP-TEST", 200, new MmiResponse("INTEROP", "INT", "INTEROP-TEST", null, List.of(member)));
            }

            @Override
            public String kind() {
                return "TEST";
            }
        };
        MemberInfoClient memberInfo = new MemberInfoClient() {
            @Override
            public MemberInfoResponse lookup(String memberId, LocalDate dateOfService, String correlationId) {
                asked.add(new Asked(memberId, dateOfService, correlationId));
                return plans.apply(memberId);
            }

            @Override
            public String kind() {
                return "TEST";
            }
        };
        MmiProperties mmiProperties = new MmiProperties("http://mmi.test", "/master/member/v1", "INTEROP", "INT", Duration.ofSeconds(2),
                Duration.ofSeconds(5), "N", List.of("ERROR"), false, new MmiProperties.Stub(false, ""));
        return new ResolutionService(new RequestValidator(properties, CLOCK), new VendorRegistry(table, new Payer("P", "P")), mmi,
                mmiProperties, new MmiRecordMapper(), new MemberSelector(new CoverageEvaluator()), new VendorFormatter(), memberInfo,
                new LineOfBusinessDeriver());
    }

    private static MemberResolutionResponse resolve(ResolutionService service) {
        return service.resolve(new MemberResolutionRequest("ONYX", "EXT", "TEST-1", STORED, "2026-10-15", "2026-10-20", null), "corr-1");
    }

    @Test
    void thePlanIsAskedForWithTheResolvedIdTheFirstDayAndTheCorrelationId() {
        MemberResolutionResponse r = resolve(service(id -> new MemberInfoResponse(null,
                List.of(new MemberInfoMember(null, "testmember", plan("MASB"))))));
        assertThat(asked).containsExactly(new Asked(STORED, LocalDate.parse("2026-10-15"), "corr-1"));
        assertThat(r.lineOfBusiness()).as("until the rules arrive, the member record's").isEqualTo("MCR");
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("marker=LOB_RULES_PENDING").contains("memberId=" + STORED)
                .contains("hierarchyLineOfBusiness=MASB").contains("lob=MCR"));
    }

    @Test
    void noPlanKeepsTheRecordsLineOfBusiness() {
        MemberResolutionResponse r = resolve(service(id -> new MemberInfoResponse(null, null)));
        assertThat(r.lineOfBusiness()).isEqualTo("MCR");
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("marker=LOB_RULES_PENDING").contains("plan=none"));
    }

    @Test
    void severalPlansUseTheFirstAndWarn() {
        resolve(service(id -> new MemberInfoResponse(null, List.of(
                new MemberInfoMember(null, STORED, plan("FIRST")), new MemberInfoMember(null, STORED, plan("SECOND"))))));
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("marker=MEMBER_PLAN_SEVERAL").contains("plans=2"));
        assertThat(messages()).anySatisfy(m -> assertThat(m).contains("hierarchyLineOfBusiness=FIRST"));
    }

    @Test
    void aFailureFailsTheAnswer503WithRetryAfterAndMmisRequestIdAsTraceId() {
        ResolutionService service = service(id -> {
            throw MemberInfoException.unavailable("READ_TIMEOUT", "The member plan lookup could not be reached: READ_TIMEOUT", null);
        });
        assertThatThrownBy(() -> resolve(service)).isInstanceOfSatisfying(MemberInfoException.class, e -> {
            assertThat(e.traceId()).isEqualTo("INTEROP-TEST");
            ResponseEntity<ApiErrorResponse> answer = new ApiExceptionHandler().memberInfo(e);
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("10");
            assertThat(answer.getBody().error().code()).isEqualTo("MEMBER_PLAN_UNAVAILABLE");
            assertThat(answer.getBody().error().details()).singleElement().satisfies(d -> assertThat(d.code()).isEqualTo("READ_TIMEOUT"));
            assertThat(answer.getBody().traceId()).isEqualTo("INTEROP-TEST");
        });
    }
}
