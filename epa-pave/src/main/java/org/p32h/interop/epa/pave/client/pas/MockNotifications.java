package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import tools.jackson.databind.JsonNode;

/**
 * The notification a mock scenario holds, made into the notification a service would have sent
 * about one subscription and one submission.
 *
 * <p>Nothing calls the receiver on the mock profile, so a tester could never see a notification
 * arrive there. The scenario files therefore carry one in their {@code notify} step, and this
 * fills in the identifiers a real one would carry: the subscription it arrives on, and the claim
 * and ClaimResponse it is about. What comes out is handed to the same receiving code a real
 * notification reaches.
 *
 * <p>Every such notification is synthetic. Its shape is this tool's reading of the R4 subscriptions
 * backport and has not been observed from the live service.
 */
public final class MockNotifications {

    /** Where a scenario file writes this, the sample names the subscription the notification arrives on. */
    public static final String SUBSCRIPTION_ID_TOKEN = "{{subscriptionId}}";

    /** Where a scenario file writes this, the sample names the ClaimResponse the notification is about. */
    public static final String CLAIM_RESPONSE_ID_TOKEN = "{{claimResponseId}}";

    /**
     * The subscription a sample names when no run of this tool has created one. It says what it is,
     * so a sample delivered before any subscription exists reads as unmatched and never as a guess.
     */
    public static final String NO_SUBSCRIPTION = "subscription-nobody-created";

    private MockNotifications() {
    }

    /** The step of a scenario that holds its notification, or null when the scenario has none. */
    public static ScenarioStep step(Scenario scenario) {
        return scenario == null ? null : scenario.step(MockOnyxPasClient.STEP_NOTIFY);
    }

    /** True when a scenario's notification is about one submission, so a sample of it needs a run to be about. */
    public static boolean aboutASubmission(ScenarioStep notify) {
        String body = notify == null ? null : notify.body();
        return body != null && (body.contains(MockOnyxPasClient.CLAIM_ID_TOKEN) || body.contains(CLAIM_RESPONSE_ID_TOKEN));
    }

    /**
     * The id the mock gives the ClaimResponse that answers a claim. The scenario files write it this
     * way, so a sample about a submission whose answer carried no ClaimResponse still names the one
     * the mock would have returned.
     */
    public static String claimResponseIdFor(String claimId) {
        return "claimresponse-" + claimId;
    }

    /**
     * The body of the notification, with each identifier put where the file leaves room for it. A
     * body that is not JSON is returned as written: a scenario may hold one on purpose.
     *
     * @param subscriptionId  the subscription to name, or blank to name {@link #NO_SUBSCRIPTION}
     * @param claimId         the claim the notification is about, or null when it is about none
     * @param claimResponseId the ClaimResponse it is about, or null when it is about none
     */
    public static String body(ScenarioStep notify, String subscriptionId, String claimId, String claimResponseId) {
        String body = notify.body();
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (!parsed.ok() || !parsed.node().isContainer()) {
            return body;
        }
        // Done on the tree, so an identifier can hold any character without breaking the document.
        JsonNode copy = parsed.node().deepCopy();
        MockOnyxPasClient.replace(copy, SUBSCRIPTION_ID_TOKEN, Texts.orElse(subscriptionId, NO_SUBSCRIPTION));
        if (Texts.present(claimId)) {
            MockOnyxPasClient.replace(copy, MockOnyxPasClient.CLAIM_ID_TOKEN, claimId);
        }
        if (Texts.present(claimResponseId)) {
            MockOnyxPasClient.replace(copy, CLAIM_RESPONSE_ID_TOKEN, claimResponseId);
        }
        return JsonSafe.pretty(copy);
    }
}
