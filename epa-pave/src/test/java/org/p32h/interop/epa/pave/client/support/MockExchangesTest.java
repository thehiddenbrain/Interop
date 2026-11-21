package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MockExchangesTest {

    private static final AppProperties NO_LATENCY = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final ExchangeOptions JSON = ExchangeOptions.json("application/json");
    private final MockExchanges exchanges = new MockExchanges(NO_LATENCY);

    private static ScenarioSelection scenario(ScenarioStep main, Map<String, ScenarioStep> steps) {
        return new ScenarioSelection(new Scenario("test-01", 1, "Test", "d", null, null, main, steps, null, "Synthetic.", null, null, "test.json"),
                "chosen in the scenario selector");
    }

    private static ScenarioStep respond(int status, String body) {
        return new ScenarioStep(ScenarioBehavior.RESPOND, status, 0L, body, null);
    }

    private VendorExchange answer(ScenarioStep step) {
        return exchanges.answer(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN), ResolvedText.plain("{\"hook\":\"order-sign\"}"), JSON,
                scenario(step, Map.of()), null);
    }

    @Test
    void aMockAnswerIsClassifiedExactlyAsTheSameStatusAndBodyWouldBeOnLive() {
        Object[][] cases = {
                {200, "{\"cards\":[]}"}, {200, "{\"cards\": ["}, {200, null}, {400, "{\"resourceType\":\"OperationOutcome\"}"},
                {404, null}, {500, "<html>oops</html>"}, {503, null}, {302, null}};
        for (Object[] c : cases) {
            int status = (Integer) c[0];
            String body = (String) c[1];
            VendorExchange mock = answer(respond(status, body));
            VendorExchange live = Exchanges.fromResponse(mock.request(), status, List.of(), body, 0L, true);
            assertThat(mock.failureType()).as(status + " " + body).isEqualTo(live.failureType());
            assertThat(mock.httpStatus()).isEqualTo(live.httpStatus());
            assertThat(mock.responseBody()).isEqualTo(live.responseBody());
            assertThat(mock.parseError()).isEqualTo(live.parseError());
            assertThat(mock.json() == null).isEqualTo(live.json() == null);
        }
    }

    @Test
    void theRequestIsDescribedAsLiveWouldSendItButMarkedAsNotSent() {
        PreparedCall call = TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "POST", "https://onyx.example.invalid/cds-services/x",
                List.of(new ResolvedHeader("X-Api-Key", "[secret apiKey]", "placeholder-key-value")), null);
        VendorExchange exchange = exchanges.answer(call, ResolvedText.plain("{\"hook\":\"order-sign\"}"), JSON.withBearerToken("unused"),
                scenario(respond(200, "{\"cards\":[]}"), Map.of()), null);
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.request().note()).isEqualTo("Mock profile. Answered from scenario test-01, chosen in the scenario selector. "
                + "Nothing left this machine.");
        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().url()).isEqualTo("https://onyx.example.invalid/cds-services/x");
        assertThat(exchange.request().body()).isEqualTo("{\"hook\":\"order-sign\"}");
        assertThat(exchange.request().headers()).contains(new WireHeader("Content-Type", "application/json"),
                new WireHeader("X-Api-Key", "[redacted]"), new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(exchange.scenarioId()).isEqualTo("test-01");
        // How the scenario came to answer travels on the exchange, so the result can say it and not only the wire view.
        assertThat(exchange.scenarioNote()).isEqualTo("chosen in the scenario selector");
    }

    @Test
    void withNothingConfiguredTheRequestHasNoUrlAndSaysSoByShowingNone() {
        VendorExchange exchange = answer(respond(200, "{\"cards\":[]}"));
        assertThat(exchange.request().url()).isNull();
        assertThat(exchange.request().urlTemplate()).isEmpty();
        assertThat(exchange.ok()).isTrue();
    }

    @Test
    void timeoutAndUnavailableHaveNoStatusAndNoBody() {
        VendorExchange timeout = answer(new ScenarioStep(ScenarioBehavior.TIMEOUT, null, 0L, null, null));
        assertThat(timeout.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();
        assertThat(timeout.responseBody()).isNull();
        assertThat(timeout.scenarioId()).isEqualTo("test-01");
        VendorExchange unavailable = answer(new ScenarioStep(ScenarioBehavior.UNAVAILABLE, null, 0L, null, null));
        assertThat(unavailable.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(unavailable.failureDetail()).isEqualTo("The connection was refused or could not be opened.");
    }

    @Test
    void aNamedStepAnswersItsOwnCallAndIsNamedInTheNote() {
        ScenarioSelection selection = scenario(respond(200, "{\"step\":\"main\"}"), Map.of("inquire", respond(202, "{\"step\":\"inquire\"}")));
        PreparedCall call = TestPreparedCalls.unconfigured(EpaOperation.PAS_INQUIRE);
        VendorExchange inquire = exchanges.answer(call, ResolvedText.plain("{}"), JSON, selection, "inquire");
        assertThat(inquire.httpStatus()).isEqualTo(202);
        assertThat(inquire.request().note()).contains("scenario test-01, step \"inquire\"");
        assertThat(exchanges.answer(call, ResolvedText.plain("{}"), JSON, selection, null).httpStatus()).isEqualTo(200);
    }

    @Test
    void aStepTheScenarioDoesNotDefineIsRefusedAndNeverAnsweredWithAnotherStep() {
        // Answering an inquiry with the submission's response would answer a question nobody asked.
        ScenarioSelection selection = scenario(respond(200, "{\"step\":\"main\"}"), Map.of("inquire", respond(202, "{}")));
        PreparedCall call = TestPreparedCalls.unconfigured(EpaOperation.PAS_INQUIRE);
        assertThatThrownBy(() -> exchanges.answer(call, ResolvedText.plain("{}"), JSON, selection, "unknown"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> {
                    ValidationException refused = (ValidationException) e;
                    assertThat(refused.fieldErrors()).singleElement().satisfies(error -> {
                        assertThat(error.field()).isEqualTo("scenarioId");
                        assertThat(error.message()).contains("test-01").contains("\"unknown\"");
                    });
                });
        ScenarioSelection stepsOnly = scenario(null, Map.of("inquire", respond(202, "{}")));
        assertThatThrownBy(() -> exchanges.answer(call, ResolvedText.plain("{}"), JSON, stepsOnly, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theScenarioDelayIsHonouredOnlyWhenMockLatencyIsEnabled() {
        ScenarioStep slow = new ScenarioStep(ScenarioBehavior.RESPOND, 200, 120L, "{\"cards\":[]}", null);
        assertThat(answer(slow).elapsedMillis()).isLessThan(100);
        MockExchanges withLatency = new MockExchanges(AppProperties.defaults());
        VendorExchange delayed = withLatency.answer(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN), ResolvedText.plain("{}"), JSON,
                scenario(slow, Map.of()), null);
        assertThat(delayed.elapsedMillis()).isGreaterThanOrEqualTo(110);
    }

    @Test
    void theMockRefusesWhatTheLivePathRefuses() {
        // An unresolved body.
        ResolvedText unresolved = new ResolvedText("{\"x\":\"{{missing}}\"}", null, null, List.of(), List.of("missing"));
        assertThatThrownBy(() -> exchanges.answer(TestPreparedCalls.unconfigured(EpaOperation.PAS_SUBMIT), unresolved, JSON,
                scenario(respond(200, "{}"), Map.of()), null)).isInstanceOf(UnresolvedVariablesException.class);

        // A call prepared under an earlier configuration.
        FakeGateway gateway = FakeGateway.unconfigured();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        MockExchanges guarded = new MockExchanges(NO_LATENCY, beans.getBeanProvider(OperationGateway.class));
        PreparedCall stale = gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT));
        gateway.changeConfiguration();
        assertThatThrownBy(() -> guarded.answer(stale, ResolvedText.plain("{}"), JSON, scenario(respond(200, "{}"), Map.of()), null))
                .isInstanceOf(ConfigurationChangedException.class);
        PreparedCall fresh = gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT));
        assertThat(guarded.answer(fresh, ResolvedText.plain("{}"), JSON, scenario(respond(200, "{}"), Map.of()), null).ok()).isTrue();
    }
}
