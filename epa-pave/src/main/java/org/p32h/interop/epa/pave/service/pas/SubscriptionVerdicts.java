package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.service.run.PlayedFaults;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Reached;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Says what the result of a subscription call means in plain language, what the tester should do
 * next, and lays out the grid of its codes.
 *
 * <p>The sentences are built from the outcome, the HTTP status and the status code of the
 * subscription. Nothing the service wrote in a sentence of its own is quoted or interpreted here.
 */
public final class SubscriptionVerdicts {

    private SubscriptionVerdicts() {
    }

    /**
     * @param subscriptionId the id the run is about: the one asked for, or the one a create returned. Null when there is none
     */
    public static VerdictView verdict(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId) {
        return verdict(reading, exchange, operation, subscriptionId, false, null);
    }

    /**
     * @param sentSubscription true when the call sent a subscription (a create or an update), so the
     *                         address it named to call back can be set beside the one the service answered with
     * @param sentEndpoint     that address as it may be shown, or null when the request named none
     */
    public static VerdictView verdict(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId,
                                      boolean sentSubscription, String sentEndpoint) {
        SubscriptionOutcome outcome = reading.outcome();
        List<SubscriptionView> subscriptions = reading.detail().subscriptions();
        // What the request named is said and compared as masked as the answer is, so a held secret in it is shown by its mask.
        String sentAddress = exchange.maskedAsHeld(sentEndpoint);
        String aboutId = exchange.maskedAsHeld(subscriptionId);
        List<String> supporting = new ArrayList<>();
        // First, so a canned answer is never mistaken for an answer from the service.
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case CREATED -> {
                supporting.add(Texts.present(aboutId) ? "Its id is " + aboutId + "."
                        : "The response gave no id for the new subscription, so it cannot be read, updated or deleted from here by id.");
                yield "The subscription was created.";
            }
            case READ -> "The subscription was returned.";
            case LISTED -> subscriptions.isEmpty() ? "The service listed no subscriptions."
                    : subscriptions.size() == 1 ? "The service listed 1 subscription." : "The service listed " + subscriptions.size() + " subscriptions.";
            case UPDATED -> "The subscription was updated.";
            case DELETED -> {
                supporting.add("No further notification should arrive on it. One that does is recorded all the same.");
                yield "The subscription was deleted.";
            }
            case NOT_FOUND -> {
                supporting.add("This is a finding about the id that was asked for, not about the environment.");
                yield "The service does not know this subscription.";
            }
            case UNRECOGNISED_RESPONSE -> {
                supporting.add("The service answered with a success status" + status(exchange) + ", and what it sent is not what this call "
                        + "returns. Whether the call did anything is not known. Everything it sent is shown below.");
                yield "The service answered with something this tool could not read as a subscription.";
            }
            case REJECTED -> "The service did not accept the request as sent" + status(exchange) + ".";
            case SERVICE_ERROR -> "The service failed" + status(exchange) + ".";
            case TIMEOUT -> "The service did not answer in time.";
            // The same outcome covers a connection that never opened and one that broke after it
            // opened. Only the first is a service that could not be reached.
            case UNREACHABLE -> exchange.reached() == Reached.NO ? "The service could not be reached."
                    : "The connection to the service failed before an answer arrived.";
            case MALFORMED_RESPONSE -> reading.detail().responseKind() == SubscriptionResponseKind.NO_BODY
                    ? "The service answered with no body" + status(exchange) + "."
                    : "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        // A played fault is described once, by the error panel beside the verdict: the failure detail a
        // scenario writes reads as a real connection failure under a verdict that nothing was sent. What
        // a played timeout would mean for a write is said by the next step, as what it would mean.
        if (outcome.fault()) {
            if (Texts.present(exchange.failureDetail()) && !PlayedFaults.played(exchange, outcome)) {
                supporting.add(exchange.failureDetail());
            }
            if (operation.write() && noUsableAnswer(outcome) && !exchange.playedTimeout()) {
                supporting.add(exchange.reached() == Reached.NO ? "Nothing was changed: the request did not reach the service."
                        : "The service may have received the request and applied it.");
            }
        } else {
            if (Texts.present(reading.detail().responseNote())) {
                supporting.add(reading.detail().responseNote());
            }
            for (SubscriptionView view : subscriptions) {
                String name = reading.several() ? view.label() + ": " : "";
                if (view.status().code() != null) {
                    supporting.add(name + "Status: " + view.status().display() + ".");
                }
                supporting.add(name + endpointLine(view.endpoint().hasValue() ? view.endpoint().value() : null, sentSubscription, sentAddress));
            }
        }
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
    }

    /**
     * The address a subscription calls back, worded from both sides when the request named one or
     * was a subscription that could have: what the request named and what the service answered,
     * and that the two differ when they do. The answer alone would have the tool say the service
     * calls back an address nobody gave it.
     */
    static String endpointLine(String answered, boolean sentSubscription, String sent) {
        if (!sentSubscription) {
            return answered == null ? "It names no url to call back." : "It calls back " + answered + ".";
        }
        if (sent == null) {
            return answered == null ? "The request named no url to call back, and the service's answer names none."
                    : "The request named no url to call back, and the service answered with one the request did not name: " + answered + ".";
        }
        if (answered == null) {
            return "The request named " + sent + " to call back, and the service's answer names no url to call back.";
        }
        return sent.equals(answered) ? "It calls back " + answered + ", the address the request named."
                : "The request named " + sent + " to call back, and the service answered with another address: " + answered + ".";
    }

    /**
     * What the tester should do next, tied to what the response said.
     *
     * @param subscriptionId  the id the run is about, handed on so a later step does not retype it
     * @param followsRunId    the run a later step should be linked to. Null when that is not known
     * @param callbackProblem why a create or an update sent a subscription with no url to call
     *                        back, in plain words with what to do about it. Null when it named
     *                        one, and for a call that sends no subscription
     * @param ownBody         true when the body that was sent is the tester's own, so the setting
     *                        this tool reads its receiver from had no part in it
     */
    public static NextStepView nextStep(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId,
                                        String followsRunId, String callbackProblem, boolean ownBody) {
        return nextStep(reading, exchange, operation, subscriptionId, followsRunId, callbackProblem, ownBody, null);
    }

    /**
     * As above, naming this run where the tester is asked to record and report it.
     *
     * @param runId this run, or null when it is not known yet: the step then says "this run"
     */
    public static NextStepView nextStep(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId,
                                        String followsRunId, String callbackProblem, boolean ownBody, String runId) {
        return PlayedFaults.rehearsed(exchange, reading.outcome(),
                asIfReal(reading, exchange, operation, subscriptionId, followsRunId, callbackProblem, ownBody, runId), operation.write()
                        ? "Had this been a real service, the service might have received the request and applied it: list the "
                        + "subscriptions before sending it again."
                        : PlayedFaults.aboutTheEnvironment("the subscription"));
    }

    /** The next step as it would be after a real service gave this answer. */
    private static NextStepView asIfReal(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId,
                                         String followsRunId, String callbackProblem, boolean ownBody, String runId) {
        SubscriptionOutcome outcome = reading.outcome();
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_SUBSCRIPTION_ID, Texts.present(subscriptionId) ? List.of(subscriptionId) : List.of());
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(followsRunId) ? List.of(followsRunId) : List.of());

        return switch (outcome) {
            // Worded as what a subscription does, not as what this one names: a body of the tester's
            // own that could not be read may name no receiver, and that is then not known here.
            // On mock no service holds the subscription and none calls back, whatever address it names: a setting of this tool is
            // not the next step there. The way to see a notification read is the sample the Notifications call delivers.
            case CREATED -> exchange != null && Texts.present(exchange.scenarioId())
                    ? new NextStepView(NextAction.START_PAS, "Next: choose the Notifications call and Deliver a sample notification. On "
                    + "the mock profile no notification arrives by itself.", "A scenario answered this create, so no service holds the "
                    + "subscription and none will call back. A sample notification shows how this tool reads one; for one about a request, "
                    + "send a PAS request first.", carry)
                    : Texts.present(callbackProblem) ? noReceiver("created", callbackProblem, ownBody, carry)
                    : new NextStepView(NextAction.START_PAS, "Next: submit a prior authorization request. The service notifies a "
                    + "subscription's receiver when the status of a request changes.",
                    "The subscription exists. A notification about a request arrives only after a request is submitted.", carry);
            // After a read, a list, an update and a delete there is nothing to do. None of them is the end of
            // a case's journey, which is what FLOW_ENDS says, so none of them says that.
            case READ, LISTED -> new NextStepView(NextAction.NOTHING_FURTHER, "Nothing follows from a read. Record this result.",
                    "The call read what the service holds and changed nothing.", carry);
            case UPDATED -> Texts.present(callbackProblem) ? noReceiver("updated", callbackProblem, ownBody, carry)
                    : new NextStepView(NextAction.NOTHING_FURTHER, "The update is done. Record this result.",
                    "The service accepted the new state of the subscription.", carry);
            case DELETED -> new NextStepView(NextAction.NOTHING_FURTHER, "The subscription is gone. Record this result.",
                    "The service accepted the delete, so there is nothing further to do with this subscription.", carry);
            case NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the subscription id. The list shows the ids the service knows.",
                    "The service does not know the id that was asked for. The environment answered normally.", carry);
            case UNRECOGNISED_RESPONSE -> PasVerdicts.recordAndReport(runId, "response not recognised", "The response is not something "
                    + "this tool can read as the answer to this call, and this tool does not guess what it means. What came back is in "
                    + "the response detail below.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, MALFORMED_RESPONSE, UNREACHABLE -> afterNoUsableAnswer(outcome, exchange, operation, carry);
        };
    }

    /** The outcomes in which the call was made and nothing usable came back. A rejection is not one: the service said no. */
    private static boolean noUsableAnswer(SubscriptionOutcome outcome) {
        return outcome == SubscriptionOutcome.TIMEOUT || outcome == SubscriptionOutcome.UNREACHABLE
                || outcome == SubscriptionOutcome.SERVICE_ERROR || outcome == SubscriptionOutcome.MALFORMED_RESPONSE;
    }

    /**
     * The next step when the call was made and nothing usable came back.
     *
     * <p>A read changes nothing, so it is simply sent again. For a create, an update or a delete it
     * turns on whether the request can have reached the service, which the exchange says: one that
     * cannot have reached it changed nothing and can be sent again, and one that may have reached
     * it may have been applied, so the tester looks at what the service holds first. The same
     * holds on the mock profile, where the scenario says which of the two it models.
     */
    private static NextStepView afterNoUsableAnswer(SubscriptionOutcome outcome, VendorExchange exchange, EpaOperation operation,
                                                    Map<String, List<String>> carry) {
        if (!operation.write()) {
            return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    (outcome == SubscriptionOutcome.UNREACHABLE ? "No answer came back." : "No usable answer came back.")
                            + " This is a finding about the environment, not about the subscription.", carry);
        }
        if (exchange.reached() == Reached.NO) {
            return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "Nothing was changed, because the request did not reach the service. It is safe to send the same request again.", carry);
        }
        // Only a create can leave a second subscription behind. Saying so of an update or a delete would be false.
        String why = operation == EpaOperation.SUBSCRIPTION_CREATE ? "sending it again without checking could create a second subscription."
                : operation == EpaOperation.SUBSCRIPTION_DELETE ? "the list shows whether the subscription is gone."
                : "the list shows whether the change took effect.";
        return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then list the subscriptions before sending again.",
                "The service may have received the request and applied it. List the subscriptions first: " + why, carry);
    }

    /**
     * The step when a subscription now exists with no url to call back. Sending the tester on to
     * wait for a notification would be sending them to wait for something that cannot come. Where
     * the fix lies depends on who wrote the body: the setting this tool reads its receiver from,
     * or the body the tester supplied, which that setting never touches.
     *
     * @param done            "created" or "updated"
     * @param callbackProblem why no url was named and what to do about it, said in the reason
     */
    private static NextStepView noReceiver(String done, String callbackProblem, boolean ownBody, Map<String, List<String>> carry) {
        String reason = "The subscription was " + done + " without a url to call back, so no notification can reach this tool. "
                + callbackProblem;
        return ownBody
                ? new NextStepView(NextAction.FIX_REQUEST, "Next: add channel.endpoint to the body you supplied, then update this "
                + "subscription or create another. Until then, follow a request with an inquiry.", reason, carry)
                : new NextStepView(NextAction.OPEN_CONFIG, "Next: set " + SubscriptionRequestBuilder.CALLBACK_PROPERTY + " to an address the "
                + "service can reach, then update this subscription or create another. Until then, follow a request with an inquiry.",
                reason, carry);
    }

    /** The next step when the service answered with a 4xx status, which does not always mean the body is at fault. */
    private static NextStepView notAccepted(VendorExchange exchange, Map<String, List<String>> carry) {
        int status = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        if (status == 401 || status == 403) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials on the Config tab.",
                    "The service answered HTTP " + status + ", which means it did not accept the credentials this tool sent, or none "
                            + "were sent. The request itself may be fine.", carry);
        }
        if (status == 404) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the subscription url on the Config tab.",
                    "The service answered HTTP 404 and did not say that the subscription is unknown, so the address is the likely cause.",
                    carry);
        }
        return new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                "The service did not accept the request as it was sent.", carry);
    }

    /**
     * The grid: the status, the channel type and the payload content of each subscription that
     * came back. When none came back the status is still listed, as not returned, so an answer
     * without a subscription does not look like one with an empty status.
     */
    public static List<DeterminationRow> determination(SubscriptionReading reading) {
        List<DeterminationRow> rows = new ArrayList<>();
        List<SubscriptionView> subscriptions = reading.detail().subscriptions();
        if (subscriptions.isEmpty()) {
            rows.add(DeterminationRow.notReturned(null, "Subscription status", PasCodes.SUBSCRIPTION_STATUS));
            return rows;
        }
        boolean named = reading.several();
        for (SubscriptionView view : subscriptions) {
            String subject = named ? view.label() : null;
            rows.add(row(subject, "Subscription status", PasCodes.SUBSCRIPTION_STATUS, view.status()));
            rows.add(new DeterminationRow(subject, "Channel type", view.channelType().orNull(), null, PasCodes.CHANNEL_TYPE, true,
                    view.channelType().presence()));
            rows.add(row(subject, "Notification content", PasCodes.PAYLOAD_CONTENT, view.payloadContent()));
        }
        return rows;
    }

    private static DeterminationRow row(String subject, String label, String element, CodedValue value) {
        return new DeterminationRow(subject, label, value.code(), value.display(), element, value.recognised(),
                value.presence() == null ? Presence.ABSENT : value.presence());
    }

    private static String status(VendorExchange exchange) {
        return exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
    }
}
