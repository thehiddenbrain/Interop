package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

/**
 * I-34: a token url the active environment states that does not answer is named as that url, with
 * the place it is set, first; that the sign-in service may be down comes second. Look-3: a CDS
 * service's own refusal reaches the result as what the service said.
 */
class TokenUrlAndServiceSaidTest {

    @TempDir
    Path directory;

    private static RunSupport support(OperationGateway gateway) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        if (gateway != null) {
            beans.addBean("gateway", gateway);
        }
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "live")), TrackingProperties.defaults(),
                OnyxProperties.of(Map.of()), beans.getBeanProvider(RunLedger.class),
                Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC));
        support.gateway(beans.getBeanProvider(OperationGateway.class));
        return support;
    }

    private static RunRecord.Builder draft() {
        return RunRecord.builder().runId("run-placeholder-1").timestamp("2026-03-10T15:00:00Z")
                .protocol(org.p32h.interop.epa.pave.model.Protocol.CRD).operation(EpaOperation.CRD_ORDER_SIGN);
    }

    @Test
    void aTokenUrlTheEnvironmentStatesIsNamedFirstWhenItGivesNoToken() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("name", "Standin Sandbox");
        body.put("kind", "sandbox");
        body.put("acknowledgeNotProd", true);
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "http://127.0.0.1:8924/token");
        signIn.put("clientId", "placeholder-client");
        signIn.put("clientSecret", "placeholder-client-secret");
        body.putObject("addresses").put("CRD_ORDER_SIGN", "http://127.0.0.1:8924/cds-services/order-sign");
        assertThat(mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        fixture.service.activate("Standin Sandbox", null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        VendorExchange exchange = Exchanges.notSignedIn(TestExchanges.crd(200, "{}").request(), OnyxFailureType.TOKEN_ENDPOINT_FAULT,
                "No access token: the token endpoint could not be reached (unavailable). The request was not sent.");

        RunEnvelope<Object> envelope = support(fixture.gateway).notSignedIn(EpaOperation.CRD_ORDER_SIGN, call, exchange,
                draft(), org.p32h.interop.epa.pave.service.raw.RawRunRequest.of(null, "{}", Map.of(), null));
        assertThat(envelope.verdict().outcomeCode()).isEqualTo("NOT_SIGNED_IN");
        assertThat(envelope.nextStep().action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(envelope.nextStep().headline()).isEqualTo("Next: check the token url of the environment Standin Sandbox, set in "
                + "Configuration > Environments > Sign-in: http://127.0.0.1:8924/token. If the url is right, the sign-in service may be "
                + "down: try again later.");
        assertThat(envelope.error().action()).isEqualTo("Check the token url of the environment Standin Sandbox, set in Configuration > "
                + "Environments > Sign-in: http://127.0.0.1:8924/token. If the url is right, the sign-in service may be down: note the time "
                + "and the run id, report it to the people who look after the token endpoint, and try again later.");
        assertThat(envelope.toString() + envelope.nextStep() + envelope.error()).doesNotContain("placeholder-client-secret");
        assertThat(envelope.nextStep().headline() + envelope.nextStep().reason() + envelope.error().action()).doesNotContain("Config tab");
    }

    @Test
    void theApplicationYmlTokenUrlIsNamedAsPaveWasStarted() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("crd.url", "http://127.0.0.1:8924")
                .onyx("variables.crdOrderSignServiceId", "order-sign").build();
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        VendorExchange exchange = Exchanges.notSignedIn(TestExchanges.crd(200, "{}").request(), OnyxFailureType.TOKEN_ENDPOINT_FAULT,
                "No access token: the token endpoint answered HTTP 503. The request was not sent.");
        RunEnvelope<Object> envelope = support(fixture.gateway).notSignedIn(EpaOperation.CRD_ORDER_SIGN, call, exchange,
                draft(), org.p32h.interop.epa.pave.service.raw.RawRunRequest.of(null, "{}", Map.of(), null));
        assertThat(envelope.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(envelope.nextStep().reason()).isEqualTo("Nothing reached the service, so this says nothing about it, the member or the "
                + "code. The token endpoint is onyx.auth.token-url, as PAVE was started.");
    }

    @Test
    void aCdsServicesOwnRefusalIsWhatTheServiceSaid() {
        ErrorView both = support(null).faultView(TestExchanges.crd(400, "{\"summary\": \"invalid hook request\", \"detail\": "
                + "\"context.draftOrders.type is not allowed\"}"), List.of());
        assertThat(both.issues()).hasSize(1);
        assertThat(both.issues().get(0).diagnostics().value()).isEqualTo("invalid hook request: context.draftOrders.type is not allowed");
        assertThat(both.issues().get(0).severity().value()).isEqualTo("error");
        assertThat(both.issues().get(0).code().value()).isEqualTo("processing");
        ErrorView summaryOnly = support(null).faultView(TestExchanges.crd(500, "{\"summary\": \"order-sign is down\"}"), List.of());
        assertThat(summaryOnly.issues().get(0).diagnostics().value()).isEqualTo("order-sign is down");
        // An OperationOutcome, or a body without either, gives no issue of this kind.
        assertThat(support(null).faultView(TestExchanges.crd(400, "{\"resourceType\": \"OperationOutcome\", \"summary\": \"x\"}"),
                List.of()).issues()).isEmpty();
        assertThat(support(null).faultView(TestExchanges.crd(400, "{\"error\": \"x\"}"), List.of()).issues()).isEmpty();
    }
}
