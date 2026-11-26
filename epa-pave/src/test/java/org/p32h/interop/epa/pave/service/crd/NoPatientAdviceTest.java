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
 * After "No patient data provided" the next step points at the prefetch's arrangement (D-309): on
 * the first run against the vendor's UAT the coverage check was answered with one card, summary
 * "Error" and detail "No patient data provided", while PAVE sent the patient in patientBundle, a
 * name the service does not read. Read as the draftOrders refusal is read, by fixed words of the answer.
 */
class NoPatientAdviceTest {

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private static final String NAMED = "{\"hook\":\"order-sign\",\"hookInstance\":\"h-1\",\"context\":{},\"prefetch\":{\"patientBundle\":"
            + "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Patient\"}}]}}}";
    private static final String AS_SAMPLE = "{\"hook\":\"order-sign\",\"hookInstance\":\"h-1\",\"context\":{},\"prefetch\":{\"patient\":"
            + "{\"resourceType\":\"Patient\"}}}";
    /** As the vendor's UAT answered: HTTP 200 with one card. */
    private static final String CARD = "{\"cards\":[{\"uuid\":\"c-1\",\"summary\":\"Error\",\"detail\":\"No patient data provided\","
            + "\"indicator\":\"critical\",\"source\":{\"label\":\"Payer\"}}]}";

    private static VendorExchange sentAndAnswered(String sentBody, int status, String answer) {
        WireRequest request = Exchanges.wireRequest(TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN,
                "https://onyx.example.invalid/cds-services/order-sign"), ResolvedText.plain(sentBody), ExchangeOptions.json("application/json"),
                true, null);
        VendorExchange exchange = Exchanges.fromResponse(request, status, List.of(), answer, 12L, true);
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    private NextStepView next(VendorExchange exchange, boolean sampleWritesPrefetch) {
        return CrdVerdicts.nextStep(mapper.read(exchange), exchange, "run-1", "Vendor UAT", false, sampleWritesPrefetch);
    }

    @Test
    void aCardSayingNoPatientDataAfterTheNamedBundlesPointsAtTheSampleArrangement() {
        NextStepView next = next(sentAndAnswered(NAMED, 200, CARD), true);

        assertThat(next.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next.headline()).isEqualTo("The service found no patient in the request. PAVE sent the prefetch under its own names; set "
                + "Prefetch to As the collection's sample in Configuration > Environments > Edit > Request shapes.");
        assertThat(next.reason()).isEqualTo("The service's answer says no patient data was given, and this request carried the patient in "
                + "patientBundle, a name PAVE gives it. CDS Hooks fixes no names for the prefetch: each service names the keys it reads.");
    }

    @Test
    void withNoSampleThatWritesAPrefetchItPointsAtDiscovery() {
        assertThat(next(sentAndAnswered(NAMED, 200, CARD), false).headline()).isEqualTo("The service found no patient in the request. PAVE "
                + "sent the prefetch under its own names; the service names the prefetch keys it reads in its discovery answer, in "
                + "Configuration > Advanced > CDS discovery.");
    }

    @Test
    void anIssueOrAnotherStatusWithTheWordsGetsItToo() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"required\","
                + "\"diagnostics\":\"No member data provided in the prefetch\"}]}";
        assertThat(next(sentAndAnswered(NAMED, 400, outcome), true).action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(next(sentAndAnswered(NAMED, 422, outcome), true).headline()).startsWith("The service found no patient in the request.");
    }

    @Test
    void aRequestAlreadyInTheSamplesArrangementOrAnotherAnswerKeepsItsOwnNextStep() {
        NextStepView followed = next(sentAndAnswered(AS_SAMPLE, 200, CARD), true);
        assertThat(followed.headline()).doesNotStartWith("The service found no patient");
        String other = CARD.replace("No patient data provided", "Coverage could not be determined");
        assertThat(next(sentAndAnswered(NAMED, 200, other), true).headline()).doesNotStartWith("The service found no patient");
        assertThat(next(sentAndAnswered("not json", 200, CARD), true).headline()).doesNotStartWith("The service found no patient");
    }

    @Test
    void theSampleACallWasSentFromSaysWhetherItWritesAPrefetch() {
        assertThat(CrdVerdicts.writesPrefetch("CRD - Prior Auth Check", AS_SAMPLE)).isTrue();
        assertThat(CrdVerdicts.writesPrefetch("CRD - Prior Auth Check (error)", AS_SAMPLE)).isFalse();
        assertThat(CrdVerdicts.writesPrefetch("CRD - Prior Auth Check", "{\"hook\":\"order-sign\"}")).isFalse();
        assertThat(CrdVerdicts.writesPrefetch(null, null)).isFalse();
    }
}
