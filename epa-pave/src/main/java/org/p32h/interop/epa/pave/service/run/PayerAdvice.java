package org.p32h.interop.epa.pave.service.run;

import java.util.List;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.RequestValues;
import tools.jackson.databind.JsonNode;

/**
 * The next step after a service refused a coverage check or a questionnaire request for want of
 * the coverage's payer (D-320). The vendor's service answered one card, "Vendor Request Rejected",
 * naming payor, and the tester was told only to read the issues. Read as the advice for fhirServer
 * (D-308) and for "No patient data" (D-309) are read, by the text of the answer and the body as
 * sent; no general reader of refusals is built.
 */
public final class PayerAdvice {

    /** The next step's headline, in the words of D-320. */
    public static final String SET_PAYER = "The service asks for the payer of the coverage, and this request named none. Set Payer name or "
            + "Payer identifier in Configuration > Environments > Edit > Values.";

    /** Why the next step points at the environment's values. */
    public static final String REASON = "The service's answer names payor, and the Coverage this request sent carried none. FHIR requires "
            + "a payer on every Coverage. PAVE names one only when Payer name, Payer identifier or Payer reference is set, or the "
            + "collection's request the call is sent from states one.";

    private PayerAdvice() {
    }

    /**
     * The step, or in its place the advice to set the payer when the answer names payor and the
     * request as sent carried a Coverage with none. A refused sign-in keeps its own step.
     */
    public static NextStepView over(NextStepView step, VendorExchange exchange, PreparedCall call) {
        if (!refusedForPayer(exchange)) {
            return step;
        }
        // The rule that kept it out only where the request the call was sent from states a payer: one that states none has no rule to name.
        List<String> notTaken = call == null || !call.requestValues().payer() ? List.of() : call.requestValues().payerLines();
        String headline = notTaken.isEmpty() ? SET_PAYER : SET_PAYER + " " + String.join(" ", notTaken);
        return new NextStepView(NextAction.OPEN_CONFIG, headline, REASON, step == null ? null : step.carry(),
                step == null ? null : step.carryLabels());
    }

    /**
     * True when the answer names payor and the body as sent carried a Coverage with no payor: in a
     * CDS Hooks body the first Coverage of its prefetch, in a Parameters body the first among its
     * parameters. A request that sent a payor is not pointed at the values: the refusal is then
     * about something else.
     */
    public static boolean refusedForPayer(VendorExchange exchange) {
        if (exchange == null || exchange.responseBody() == null || !exchange.responseBody().contains("payor")) {
            return false;
        }
        Integer status = exchange.httpStatus();
        if (status != null && (status == 401 || status == 403)) {
            return false;
        }
        String sent = exchange.request() == null ? null : exchange.request().body();
        JsonSafe.Parsed parsed = sent == null ? null : JsonSafe.parse(sent);
        if (parsed == null || !parsed.ok() || !JsonSafe.isObject(parsed.node())) {
            return false;
        }
        JsonNode coverage = "Parameters".equals(JsonSafe.presentText(parsed.node(), "resourceType"))
                ? RequestValues.parametersCoverage(parsed.node()) : RequestValues.prefetchCoverage(parsed.node());
        return coverage != null && JsonSafe.elements(coverage, "payor").isEmpty();
    }
}
