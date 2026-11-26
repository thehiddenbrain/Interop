package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.service.run.PayerAdvice;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The next step after a refusal that names payor (D-320). The vendor's service answered the
 * operator's coverage check with one card, "Vendor Request Rejected", whose detail named the payor
 * of the first resource of the coverage Bundle, and the card told the tester only to read the
 * issues. Each service answers here through a stand-in client on the live profile.
 */
class PayerAdviceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);

    /** The answer of the operator's photo, its words as the card gave them. */
    static final String PHOTO = "{\"cards\": [{\"uuid\": \"card-1\", \"summary\": \"Vendor Request Rejected\", \"indicator\": \"critical\", "
            + "\"detail\": \"Element 'Bundle.entry[0].resource.payor' with minimum cardinality 1 must be present.\", "
            + "\"source\": {\"label\": \"Placeholder payer\"}}]}";
    static final String OUTCOME = "{\"resourceType\": \"OperationOutcome\", \"issue\": [{\"severity\": \"error\", \"code\": \"required\", "
            + "\"diagnostics\": \"Coverage.payor: minimum required = 1, but only found 0\"}]}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();

    /** The last coverage check as the stand-in client answered it, for a test that asks the advice itself. */
    private VendorExchange lastCheck;

    private RunSupport support() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", "live")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    private static VendorExchange answered(PreparedCall call, ResolvedText body, int status, String answer) {
        WireRequest request = Exchanges.wireRequest(call, body, ExchangeOptions.json("application/json"), true, null);
        return Exchanges.fromResponse(request, status, List.of(), answer, 12L, true);
    }

    private CrdService crd(ConfigFixture fixture, int status, String answer) {
        OnyxCrdClient client = new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                VendorExchange exchange = answered(call, body, status, answer);
                lastCheck = exchange.withSignals(OnyxCrdConventions.signals(exchange));
                return lastCheck;
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                throw new UnsupportedOperationException("not asked here");
            }
        };
        RunSupport support = support();
        return new CrdService(support.epa(), fixture.gateway, client, new FakeCodeList(), support);
    }

    private DtrService dtr(ConfigFixture fixture, int status, String answer) {
        OnyxDtrClient client = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                VendorExchange exchange = answered(call, body, status, answer);
                return exchange.withSignals(OnyxDtrConventions.signals(exchange));
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                throw new UnsupportedOperationException("not asked here");
            }
        };
        RunSupport support = support();
        return new DtrService(support.epa(), fixture.gateway, client, new FakeCodeList(), support);
    }

    /** An environment with no payer value, and a collection whose requests state the payer this way, or none. */
    private ConfigFixture live(String hookBody, String packageBody) {
        ConfigFixture fixture = ConfigFixture.live(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("CRD - Coverage check", "POST", "https://vendor.example.invalid/epa/cds-services/order-sign").body(hookBody),
                ConfigFixture.item("DTR - Fetch Questionnaire", "POST",
                        "https://vendor.example.invalid/r4/Questionnaire/$questionnaire-package").body(packageBody)));
        return fixture;
    }

    private static MemberDetail member() {
        return MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                .gender("female").coverageStatus("active").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static final List<CodeInput> CODES = List.of(new CodeInput("95810", null, "Sleep study, attended"));

    private static CrdRunRequest check() {
        return CrdRunRequest.builder().member(member()).provider(provider()).procedureCodes(CODES).dateOfService("2026-03-20").build();
    }

    private static DtrPackageRequest questionnaire() {
        return DtrPackageRequest.builder().member(member()).provider(provider()).procedureCodes(CODES).dateOfService("2026-03-20").build();
    }

    private static final String NO_PAYOR = PayerFromRequestTest.HOOK.replaceAll(", \"payor\": \\[.*\\]\\}\\}\\}$", "}}}");

    @Test
    void theCardOfThePhotoAfterACoverageCheckWithNoPayerPointsAtTheValues() {
        assertThat(NO_PAYOR).doesNotContain("payor");
        NextStepView next = crd(live(NO_PAYOR, PayerFromRequestTest.PACKAGE), 200, PHOTO).run(check(), RunOrigin.manual(), null).nextStep();
        assertThat(next.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next.headline()).isEqualTo("The service asks for the payer of the coverage, and this request named none. Set Payer name or "
                + "Payer identifier in Configuration > Environments > Edit > Values.");
        assertThat(next.reason()).isEqualTo(PayerAdvice.REASON);
    }

    @Test
    void aPayerTheRequestStatesAndThatWasNotTakenIsSaidWithItsRule() {
        String undefined = PayerFromRequestTest.HOOK.replace("\"Placeholder Payer\"", "\"{{vendorPayer}}\"")
                .replace("\"P32H-PAYER-1\"", "\"{{vendorPayerId}}\"");
        NextStepView next = crd(live(undefined, PayerFromRequestTest.PACKAGE), 200, PHOTO).run(check(), RunOrigin.manual(), null).nextStep();
        assertThat(next.headline()).startsWith(PayerAdvice.SET_PAYER + " ")
                .contains("The collection's request \"CRD - Coverage check\" writes Payer name with {{vendorPayer}}, which nothing defines, "
                        + "so it is not taken.")
                .contains("writes Payer identifier with {{vendorPayerId}}");
    }

    @Test
    void anOperationOutcomeAfterAQuestionnaireRequestWithNoPayerPointsAtTheValues() {
        String noPayor = PayerFromRequestTest.PACKAGE.replace(", \"payor\": [{\"reference\": \"Organization/org-payer-001\"}]", "");
        assertThat(noPayor).doesNotContain("payor");
        NextStepView next = dtr(live(NO_PAYOR, noPayor), 422, OUTCOME).runPackage(questionnaire(), RunOrigin.manual(), null).nextStep();
        assertThat(next.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next.headline()).isEqualTo(PayerAdvice.SET_PAYER);
    }

    @Test
    void anAnswerThatNamesPayorForARequestThatCarriedOneGetsNoSuchAdvice() {
        // The collection's requests state a payer, so the request carries it, and the refusal is about something else.
        NextStepView check = crd(live(PayerFromRequestTest.HOOK, PayerFromRequestTest.PACKAGE), 200, PHOTO).run(check(), RunOrigin.manual(), null)
                .nextStep();
        assertThat(check.headline()).doesNotContain("asks for the payer");
        NextStepView pack = dtr(live(PayerFromRequestTest.HOOK, PayerFromRequestTest.PACKAGE), 422, OUTCOME)
                .runPackage(questionnaire(), RunOrigin.manual(), null).nextStep();
        assertThat(pack.headline()).doesNotContain("asks for the payer");
        // A refused sign-in keeps its own next step whatever the answer says.
        NextStepView refused = dtr(live(NO_PAYOR, PayerFromRequestTest.PACKAGE.replace(", \"payor\": [{\"reference\": "
                + "\"Organization/org-payer-001\"}]", "")), 401, OUTCOME).runPackage(questionnaire(), RunOrigin.manual(), null).nextStep();
        assertThat(refused.headline()).doesNotContain("asks for the payer");
    }

    /**
     * An answer that decided was not refused. Should it hold the word payor, in a card's text or a resource it sends
     * back, the step it leads to stays: the advice is for an answer that leads nowhere.
     */
    @Test
    void anAnswerThatLeadsOnKeepsItsStepWhateverWordItHolds() {
        crd(live(NO_PAYOR, PayerFromRequestTest.PACKAGE), 200, PHOTO).run(check(), RunOrigin.manual(), null);
        assertThat(PayerAdvice.refusedForPayer(lastCheck)).isTrue();
        for (NextAction leadsOn : List.of(NextAction.START_DTR, NextAction.START_PAS, NextAction.FLOW_ENDS, NextAction.NOTHING_FURTHER)) {
            NextStepView step = new NextStepView(leadsOn, "Next: go on.", "Why.", Map.of(), Map.of());
            assertThat(PayerAdvice.over(step, lastCheck, null)).as(leadsOn.name()).isSameAs(step);
        }
        NextStepView stuck = new NextStepView(NextAction.REPORT_NO_DETERMINATION, "Next: report it.", "Why.", Map.of(), Map.of());
        assertThat(PayerAdvice.over(stuck, lastCheck, null).action()).isEqualTo(NextAction.OPEN_CONFIG);
    }

    @Test
    void anAnswerThatDoesNotNamePayorOrNoExchangeAtAllChangesNothing() {
        NextStepView step = new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.", "Why.", Map.of(),
                Map.of());
        assertThat(PayerAdvice.over(step, null, null)).isSameAs(step);
        assertThat(PayerAdvice.refusedForPayer(null)).isFalse();
    }
}
