package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The comparison as the service offers it: what it refuses, that it sends and records nothing,
 * and that it fills placeholders in as a run does.
 */
class CrdServiceCompareTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String NPI = Npi.withCheckDigit("198765432");

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final AtomicInteger calls = new AtomicInteger();

    /** A client that counts what reaches it. A comparison never reaches it. */
    private final OnyxCrdClient client = new OnyxCrdClient() {
        @Override
        public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
            calls.incrementAndGet();
            throw new AssertionError("a comparison sends nothing");
        }

        @Override
        public VendorExchange discover(PreparedCall call) {
            calls.incrementAndGet();
            throw new AssertionError("a comparison sends nothing");
        }
    };

    private CrdService service(String mode) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(epa, gateway, client, codeList, support);
    }

    private static String fixture(String name) {
        try (InputStream in = CrdServiceCompareTest.class.getResourceAsStream("/compare/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String VENDOR = fixture("vendor-shaped-order-sign.json");

    private void assertNothingWasSentOrRecorded() {
        assertThat(calls.get()).as("calls to the service").isZero();
        assertThat(ledger.records()).as("ledger lines").isEmpty();
        assertThat(gateway.intents()).as("calls the gateway was asked to prepare").isEmpty();
    }

    // ---- nothing is sent and nothing is recorded ----

    @Test
    void aComparisonSendsNothingRecordsNothingAndNeverAsksTheGatewayToPrepareACall() {
        RequestComparison comparison = service("mock").compare(CrdRunRequest.builder().rawBody(VENDOR).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.differences()).hasSize(34);
        assertNothingWasSentOrRecorded();
    }

    @Test
    void aComparisonWorksOnLiveWithNoUrlConfiguredBecauseNoCallIsPrepared() {
        // On live a run with nothing configured is refused when the gateway prepares the call. A comparison never gets that far.
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        RequestComparison comparison = service("live").compare(CrdRunRequest.builder().rawBody(VENDOR).build());
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.hook()).isEqualTo("order-sign");
        assertNothingWasSentOrRecorded();
        // An answer that the two cannot be compared is an answer too, and is not an error.
        assertThat(service("live").compare(CrdRunRequest.builder().rawBody("not json").build()).comparable()).isFalse();
        assertNothingWasSentOrRecorded();
    }

    // ---- what is refused ----

    private void assertRefused(CrdService service, CrdRunRequest request, String field) {
        assertThatThrownBy(() -> service.compare(request)).as("a refusal on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field));
        assertNothingWasSentOrRecorded();
    }

    @Test
    void aComparisonWithNothingPastedIsRefused() {
        for (String nothing : new String[] {null, "", "   \n"}) {
            assertRefused(service("mock"), CrdRunRequest.builder().rawBody(nothing).member(MemberDetail.builder().memberId("S123456789").build())
                    .provider(ProviderDetail.builder().npi(NPI).build()).procedureCodes(List.of(CodeInput.of("95810"))).build(), "rawBody");
        }
        assertThatThrownBy(() -> service("mock").compare(CrdRunRequest.builder().build())).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors().get(0).message()).isEqualTo("Paste the request to compare with the one this tool builds."));
    }

    @Test
    void aPastedTextLargerThanTwoMegabytesIsRefused() {
        String large = "{\"hook\":\"order-sign\",\"padding\":\"" + "x".repeat(CrdRequestComparer.MAX_BYTES) + "\"}";
        assertRefused(service("mock"), CrdRunRequest.builder().rawBody(large).build(), "rawBody");
        assertThatThrownBy(() -> service("mock").compare(CrdRunRequest.builder().rawBody(large).build())).isInstanceOfSatisfying(
                ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .isEqualTo("The pasted request is larger than 2 MB, which is more than this tool compares."));
    }

    @Test
    void whatTheFormHoldsIsCheckedAsItIsForARunWithItsOwnBody() {
        CrdService service = service("mock");
        assertRefused(service, CrdRunRequest.builder().rawBody(VENDOR).provider(ProviderDetail.builder().npi("1234567890").build()).build(), "provider.npi");
        assertRefused(service, CrdRunRequest.builder().rawBody(VENDOR).dateOfService("03/20/2026").build(), "dateOfService");
        assertRefused(service, CrdRunRequest.builder().rawBody(VENDOR).hook("order-dispatch").build(), "hook");
        assertRefused(service, CrdRunRequest.builder().rawBody(VENDOR).member(MemberDetail.builder().memberId("S123456789").dateOfBirth("2026-03-11")
                .build()).build(), "member.dateOfBirth");
        assertRefused(service, CrdRunRequest.builder().rawBody(VENDOR).expected("APPROVED").build(), "expected");
        // A scenario forces an answer on mock. On live it is refused here as it is for a run.
        assertRefused(service("live"), CrdRunRequest.builder().rawBody(VENDOR).scenarioId("crd-02-no-auth").build(), "scenarioId");
    }

    @Test
    void aFieldOfThePostedRequestThatThisToolDoesNotKnowIsRefusedByName() {
        CrdRunRequest misspelt = JsonSafe.mapper().readValue("{\"rawBody\":\"{}\",\"rawbody\":\"{}\"}", CrdRunRequest.class);
        assertRefused(service("mock"), misspelt, "rawbody");
    }

    @Test
    void theFormMayBeEmptyBecauseTheCaseIsReadFromThePastedRequest() {
        RequestComparison comparison = service("mock").compare(CrdRunRequest.builder().rawBody(VENDOR).build());
        assertThat(comparison.caseSource()).isNotEmpty().allSatisfy(value -> assertThat(value.source().name()).isEqualTo("YOUR_REQUEST"));
    }

    // ---- placeholders and variables, as a run has them ----

    @Test
    void placeholdersAreFilledFromTheActiveConfigurationAndAValueTypedForThisRunWins() {
        gateway.variable("npiOfTheSample", NPI).variable("sampleMemberId", "S000000001");
        String pasted = VENDOR.replace("\"" + NPI + "\"", "\"{{npiOfTheSample}}\"").replace("\"S123456789\"", "\"{{sampleMemberId}}\"");
        RequestComparison fromEnvironment = service("mock").compare(CrdRunRequest.builder().rawBody(pasted).build());
        assertThat(fromEnvironment.yourBody()).contains(NPI).contains("S000000001").doesNotContain("{{");
        assertThat(fromEnvironment.caseSource()).filteredOn(value -> value.field().equals("member.memberId")).singleElement()
                .satisfies(value -> assertThat(value.value().value()).isEqualTo("S000000001"));

        RequestComparison typed = service("mock").compare(CrdRunRequest.builder().rawBody(pasted).variables(Map.of("sampleMemberId", "S123456789")).build());
        assertThat(typed.yourBody()).contains("S123456789").doesNotContain("S000000001");
        assertNothingWasSentOrRecorded();
    }

    @Test
    void theToolsOwnRequestIsBuiltWithTheSameVariablesARunWouldUse() {
        gateway.variable("fhirServer", "https://ehr.example.invalid/fhir/r4").variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id");
        RequestComparison comparison = service("mock").compare(CrdRunRequest.builder().rawBody(VENDOR).build());
        assertThat(JsonSafe.parse(comparison.toolBody()).node().get("fhirServer").asString()).isEqualTo("https://ehr.example.invalid/fhir/r4");
        assertThat(comparison.differences()).extracting(Difference::path).doesNotContain("fhirServer")
                .contains("prefetch[Patient].identifier[https://example.org/p32h-placeholder/member-id]");
        // And a value typed for this run reaches the builder as it reaches the pasted text.
        RequestComparison typed = service("mock").compare(CrdRunRequest.builder().rawBody(VENDOR)
                .variables(Map.of("fhirServer", "https://other.example.invalid/fhir")).build());
        assertThat(typed.differences()).filteredOn(difference -> difference.path().equals("fhirServer")).singleElement().satisfies(difference -> {
            assertThat(difference.toolValue().value()).isEqualTo("https://other.example.invalid/fhir");
            assertThat(difference.explanation()).isEqualTo("The two requests hold different text here. This tool takes its value from the "
                    + "variable fhirServer.");
        });
    }

    // ---- member data and the log ----

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void nothingFromEitherRequestReachesTheLogWhateverTheComparisonEndsIn(CapturedOutput output) {
        CrdService service = service("mock");
        // Compared.
        service.compare(CrdRunRequest.builder().rawBody(VENDOR).member(MemberDetail.builder().memberId("S123456789").lastName("Form-Surname")
                .dateOfBirth("1970-01-31").build()).build());
        // Not JSON, with member data where parsing stops.
        service.compare(CrdRunRequest.builder().rawBody("{\"name\": Sample-Member born 1968-04-12}").build());
        // Nothing to build from.
        service.compare(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"note\":\"Sample-Member\"}").build());
        // Refused.
        assertThatThrownBy(() -> service.compare(CrdRunRequest.builder().rawBody(VENDOR).provider(ProviderDetail.builder().npi("1234567890").build())
                .build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.compare(CrdRunRequest.builder().rawBody(" ").member(MemberDetail.builder().memberId("S123456789").build())
                .build())).isInstanceOf(ValidationException.class);
        // A failure inside the tool while it builds its own request.
        codeList.failing(new IllegalStateException("code list exploded for Sample-Member"));
        assertThatThrownBy(() -> service.compare(CrdRunRequest.builder().rawBody(VENDOR.replace("\"system\": \"http://www.ama-assn.org/go/cpt\",", ""))
                .build())).isInstanceOf(IllegalStateException.class);

        assertThat(output.getAll()).doesNotContain("Sample-Member").doesNotContain("Form-Surname").doesNotContain("S123456789")
                .doesNotContain("1968-04-12").doesNotContain("1970-01-31").doesNotContain(NPI).doesNotContain("Robin").doesNotContain("pat-8842")
                .doesNotContain("hookInstance").doesNotContain("resourceType").doesNotContain("Placeholder Street");
        assertNothingWasSentOrRecorded();
    }
}
