package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A 401 to a request that went out with no Authorization header while signing in is off: the next
 * step and the error panel say that, and only then, because a header that did go out makes the old
 * words, a credential that was not accepted, the true ones.
 */
class UnsignedNextStepTest {

    private static final NextStepView CREDENTIALS = new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials on the Config tab.",
            "The service answered HTTP 401.", Map.of(NextStepView.CARRY_PROCEDURE_CODES, List.of("E0470")));

    private static RunSupport support(boolean signingIn) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        return new RunSupport(EpaProperties.of(Map.of("mode", "live")), TrackingProperties.defaults(),
                OnyxProperties.of(Map.of("auth.enabled", String.valueOf(signingIn))), beans.getBeanProvider(RunLedger.class),
                Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC));
    }

    /** The same exchange as though its request had gone out with these headers, or had not gone out at all. */
    private static VendorExchange with(VendorExchange exchange, List<WireHeader> headers, boolean sent) {
        WireRequest request = exchange.request();
        WireRequest changed = new WireRequest(request.method(), request.url(), request.urlTemplate(), headers, request.body(), sent,
                request.note(), request.secrets());
        return new VendorExchange(changed, exchange.httpStatus(), exchange.responseHeaders(), exchange.responseBody(), exchange.responseBytes(),
                exchange.json(), exchange.parseError(), exchange.elapsedMillis(), exchange.failureType(), exchange.failureDetail(),
                exchange.signals(), exchange.scenarioId(), exchange.scenarioNote(), exchange.bodyNotes(), exchange.reached());
    }

    private static VendorExchange answered(int status) {
        return TestExchanges.crd(status, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"login\"}]}");
    }

    @Test
    void aRequestThatWentOutWithNoHeaderWhileSigningInIsOffIsToldToSwitchItOn() {
        VendorExchange bare = with(answered(401), List.of(), true);
        NextStepView step = support(false).unsignedNextStep(CREDENTIALS, bare);
        assertThat(step.headline()).isEqualTo("Next: switch signing in on, then send again.");
        assertThat(step.reason()).startsWith("This tool sent no Authorization header: signing in is off");
        assertThat(step.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(step.carry()).isEqualTo(CREDENTIALS.carry());
        assertThat(support(false).faultView(bare, List.of()).action()).startsWith("This tool sent no Authorization header: signing in is off "
                + "(onyx.auth.enabled is false)");
        // A 403 is the same refusal.
        assertThat(support(false).unsignedNextStep(CREDENTIALS, with(answered(403), List.of(), true)).headline())
                .isEqualTo("Next: switch signing in on, then send again.");
    }

    /**
     * This tool knows whether the request went out with an Authorization header, so a 401 or a 403
     * says which, in the verdict's next step and in the error panel alike, and never "or none were
     * sent" when one went out. Only whether a header went out is read, never its value.
     */
    @Test
    void aRefusalOfTheCredentialsSaysWhetherAnAuthorizationHeaderWentOut() {
        VendorExchange signed = with(answered(401), List.of(new WireHeader("Authorization", "Bearer [redacted]")), true);
        VendorExchange bare = with(answered(403), List.of(new WireHeader("Accept", "application/json")), true);
        assertThat(RunSupport.credentialsReason(401, signed)).isEqualTo("The service answered HTTP 401, which means it did not accept the "
                + "credentials this tool sent. The request itself may be fine.");
        assertThat(RunSupport.credentialsReason(403, bare)).isEqualTo("The service answered HTTP 403, and the request as sent carried no "
                + "Authorization header: no credentials were sent. The request itself may be fine.");
        // The CRD verdict, read from the answer as a run reads it, takes the same sentence; DTR, PAS and subscriptions call the same method.
        org.p32h.interop.epa.pave.service.crd.CrdResponseMapper mapper = new org.p32h.interop.epa.pave.service.crd.CrdResponseMapper();
        NextStepView step = org.p32h.interop.epa.pave.service.crd.CrdVerdicts.nextStep(mapper.read(signed), signed);
        assertThat(step.reason()).isEqualTo(RunSupport.credentialsReason(401, signed)).doesNotContain("or none").doesNotContain("Bearer");
        assertThat(support(true).faultView(signed, List.of()).action()).isEqualTo("This status means the service did not accept the "
                + "credentials this tool sent. Check the client id, the secret and the token url on the Config tab. The request that was "
                + "sent is in the wire view.");
        assertThat(support(true).faultView(bare, List.of()).action()).isEqualTo("This status means credentials were wanted, and the request "
                + "as sent carried no Authorization header. Check the sign-in settings on the Config tab. The request that was sent is in "
                + "the wire view.");
    }

    @Test
    void anyOtherCaseKeepsTheStepItWasGiven() {
        // A header of the request's own, in any letter case.
        VendorExchange ownHeader = with(answered(401), List.of(new WireHeader("authorization", "Bearer [redacted]")), true);
        assertThat(support(false).unsignedNextStep(CREDENTIALS, ownHeader)).isSameAs(CREDENTIALS);
        assertThat(support(false).faultView(ownHeader, List.of()).action()).startsWith("This status means the service did not accept the "
                + "credentials this tool sent. Check the client id");
        // Signing in is on.
        assertThat(support(true).unsignedNextStep(CREDENTIALS, with(answered(401), List.of(), true))).isSameAs(CREDENTIALS);
        // Another status.
        assertThat(support(false).unsignedNextStep(CREDENTIALS, with(answered(404), List.of(), true))).isSameAs(CREDENTIALS);
        // Nothing went out.
        assertThat(support(false).unsignedNextStep(CREDENTIALS, with(answered(401), List.of(), false))).isSameAs(CREDENTIALS);
        assertThat(support(false).unsignedNextStep(CREDENTIALS, TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN))).isSameAs(CREDENTIALS);
    }
}
