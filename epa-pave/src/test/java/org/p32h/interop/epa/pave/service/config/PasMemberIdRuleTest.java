package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * Submit under an environment whose member ID rule is not packed: the request, the wire view and
 * the preview carry the rule's form, and the ledger keeps the packed form so one member stays one
 * key (R-10). In this package because only here can a call be issued with an environment's rule.
 */
class PasMemberIdRuleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    private final InMemoryLedger ledger = new InMemoryLedger();

    /** A gateway whose every call is issued under one member ID rule, with nothing configured. */
    private static OperationGateway ruled(MemberIdRule rule) {
        return new OperationGateway() {
            @Override
            public PreparedCall prepare(CallIntent intent) {
                IssuedCall plain = (IssuedCall) TestPreparedCalls.unconfigured(intent.operation());
                return new IssuedCall(plain.operation(), plain.method(), plain.url(), plain.headers(), plain.environment(), null, false,
                        () -> 0L, ResolvedText::plain, null, null, null, new IssuedCall.Settings(null, rule, null, null));
            }

            @Override
            public ResolvedText resolve(String template, Map<String, String> runValues) {
                return ResolvedText.plain(template);
            }

            @Override
            public EnvironmentRef activeEnvironment() {
                return EnvironmentRef.none();
            }

            @Override
            public WriteAccess writeAccess(ProdUnlock unlock) {
                return null;
            }

            @Override
            public long epoch() {
                return 0L;
            }

            @Override
            public MemberIdRule memberIdRule() {
                return rule;
            }
        };
    }

    private PasService service(MemberIdRule rule) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        ObjectProvider<RunLedger> provider = beans.getBeanProvider(RunLedger.class);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        OnyxProperties onyx = OnyxProperties.of(Map.of());
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), onyx, provider, CLOCK);
        ScenarioRepository scenarios = new ScenarioRepository(APP);
        return new PasService(onyx, ruled(rule), new MockOnyxPasClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support,
                provider);
    }

    private static PasSubmitRequest submission() {
        return PasSubmitRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678 01").firstName("Given").lastName("Placeholder-Surname").build())
                .provider(ProviderDetail.builder().npi("1234567893").lastName("Provider").build())
                .procedureCodes(List.of(CodeInput.of("95810"))).dateOfService("2026-03-20").build();
    }

    private static String patientIdentifier(String body) {
        for (JsonNode entry : JsonSafe.parse(body).node().get("entry")) {
            if ("Patient".equals(entry.at("/resource/resourceType").asString())) {
                return entry.at("/resource/identifier/0/value").asString();
            }
        }
        return null;
    }

    @Test
    void theSpacedRuleWritesTheCardFormInTheRequestAndTheWireViewAndTheLedgerKeepsThePackedForm() {
        RunEnvelope<PasDetail> result = service(MemberIdRule.SPACED).runSubmit(submission(), RunOrigin.manual(), null);
        assertThat(result.wire().memberId().sent()).isEqualTo("S12345678   01");
        assertThat(result.wire().memberId().rule()).isEqualTo("spaced");
        assertThat(patientIdentifier(result.wire().request().body())).isEqualTo("S12345678   01");
        assertThat(ledger.only().memberId()).isEqualTo("S1234567801");

        RequestPreview preview = service(MemberIdRule.SPLIT).previewSubmit(submission(), null);
        assertThat(preview.memberId().sent()).isEqualTo("S12345678");
        assertThat(preview.memberId().rule()).isEqualTo("split");
        assertThat(patientIdentifier(preview.body())).isEqualTo("S12345678");
    }

    @Test
    void underThePackedRuleNothingChanges() {
        RunEnvelope<PasDetail> result = service(MemberIdRule.PACKED).runSubmit(submission(), RunOrigin.manual(), null);
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.wire().memberId().rule()).isEqualTo("packed");
        assertThat(patientIdentifier(result.wire().request().body())).isEqualTo("S1234567801");
    }
}
