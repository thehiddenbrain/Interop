package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;

/**
 * A refusal of the coverage check that names fhirServer points at the value that sends it
 * (D-308): on the first real run the vendor's service answered that fhirServer is required, and
 * the next step sent the tester to correct the request, which PAVE builds and the tester cannot.
 * Read as the refusal that names draftOrders.type is read, by the text of the answer.
 */
class FhirServerAdviceTest {

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private static final String WITHOUT = "{\"hook\":\"order-sign\",\"hookInstance\":\"h-1\",\"context\":{}}";
    private static final String WITH = "{\"hook\":\"order-sign\",\"hookInstance\":\"h-1\",\"fhirServer\":\"https://ehr-sandbox.example.org/fhir\","
            + "\"context\":{}}";
    /** As a CDS Hooks service words a refusal: a card's summary and detail. */
    private static final String REQUIRED = "{\"summary\":\"invalid hook request\",\"detail\":\"\\\"fhirServer\\\" is required\"}";

    private static VendorExchange sentAndAnswered(String sentBody, int status, String answer) {
        WireRequest request = Exchanges.wireRequest(TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN,
                "https://onyx.example.invalid/cds-services/order-sign"), ResolvedText.plain(sentBody), ExchangeOptions.json("application/json"),
                true, null);
        VendorExchange exchange = Exchanges.fromResponse(request, status, List.of(), answer, 12L, true);
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    private NextStepView next(VendorExchange exchange, boolean sampleStatesOne) {
        return CrdVerdicts.nextStep(mapper.read(exchange), exchange, "run-1", "Vendor UAT", sampleStatesOne);
    }

    @Test
    void aRefusalThatNamesFhirServerSaysWhereToSetItAndThatTheSampleStatesOne() {
        NextStepView next = next(sentAndAnswered(WITHOUT, 400, REQUIRED), true);
        assertThat(next.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next.headline()).isEqualTo("Set FHIR server in Configuration > Environments > Edit > Values. The vendor's sample request "
                + "states one.");
        assertThat(next.reason()).isEqualTo("The service refused the check and its answer names fhirServer, which this request did not send. "
                + "PAVE sends fhirServer only when the environment's value FHIR server is set.");

        // The second sentence only when the sample the call was sent from states one.
        assertThat(next(sentAndAnswered(WITHOUT, 400, REQUIRED), false).headline())
                .isEqualTo("Set FHIR server in Configuration > Environments > Edit > Values.");
        // In an issue's text, as a FHIR server words it, and with another refusal status.
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"required\","
                + "\"diagnostics\":\"fhirServer is required\"}]}";
        assertThat(next(sentAndAnswered(WITHOUT, 422, outcome), false).action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next(sentAndAnswered(WITHOUT, 500, outcome), false).headline())
                .isEqualTo("Set FHIR server in Configuration > Environments > Edit > Values.");
    }

    @Test
    void aRequestThatSentFhirServerOrARefusalOfSomethingElseKeepsItsOwnNextStep() {
        assertThat(next(sentAndAnswered(WITH, 400, REQUIRED), true).headline()).isEqualTo("Next: read the issues below and correct the request.");
        String other = "{\"summary\":\"invalid hook request\",\"detail\":\"prefetch.coverageBundle.entry must not be empty\"}";
        assertThat(next(sentAndAnswered(WITHOUT, 400, other), true).action()).isEqualTo(NextAction.FIX_REQUEST);
        // Credentials keep their own next step whatever the answer says.
        assertThat(next(sentAndAnswered(WITHOUT, 401, REQUIRED), true).headline())
                .isEqualTo("Next: check the credentials in Configuration > Environments > Sign-in.");
        // A body that is not JSON cannot be said to lack it.
        assertThat(next(sentAndAnswered("not json", 400, REQUIRED), true).action()).isEqualTo(NextAction.FIX_REQUEST);
        // Without word of the sample, as before D-308.
        assertThat(CrdVerdicts.nextStep(mapper.read(sentAndAnswered(WITHOUT, 400, REQUIRED)), sentAndAnswered(WITHOUT, 400, REQUIRED),
                "run-1", "Vendor UAT").headline()).isEqualTo("Set FHIR server in Configuration > Environments > Edit > Values.");
    }

    @Test
    void theSampleACallWasSentFromSaysWhetherItStatesOne() {
        assertThat(CrdVerdicts.statesFhirServer(null)).isFalse();
        assertThat(CrdVerdicts.statesFhirServer(WITHOUT)).isFalse();
        assertThat(CrdVerdicts.statesFhirServer(WITH)).isTrue();
        // A saved body writes placeholders outside strings, and is read all the same.
        assertThat(CrdVerdicts.statesFhirServer("{\"hook\":\"order-sign\",\"fhirServer\":\"{{ehrBase}}/fhir\",\"n\":{{count}}}")).isTrue();
        assertThat(CrdVerdicts.statesFhirServer("{\"hook\":\"order-sign\",\"fhirServer\":\"\"}")).isFalse();
    }
}
