package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
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
        SubscriptionOutcome outcome = reading.outcome();
        List<SubscriptionView> subscriptions = reading.detail().subscriptions();
        List<String> supporting = new ArrayList<>();
        // First, so a canned answer is never mistaken for an answer from the service.
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case CREATED -> {
                supporting.add(Texts.present(subscriptionId) ? "Its id is " + subscriptionId + "."
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
            case UNREACHABLE -> "The service could not be reached.";
            case MALFORMED_RESPONSE -> reading.detail().responseKind() == SubscriptionResponseKind.NO_BODY
                    ? "The service answered with no body" + status(exchange) + "."
                    : "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        if (outcome.fault()) {
            if (Texts.present(exchange.failureDetail())) {
                supporting.add(exchange.failureDetail());
            }
            if (mayHaveBeenApplied(outcome, exchange, operation)) {
                supporting.add("The request was sent. Whether the service applied it is not known.");
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
                if (view.endpoint().hasValue()) {
                    supporting.add(name + "It calls back " + view.endpoint().value() + ".");
                } else {
                    supporting.add(name + "It names no url to call back.");
                }
            }
        }
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
    }

    /**
     * What the tester should do next, tied to what the response said.
     *
     * @param subscriptionId the id the run is about, handed on so a later step does not retype it
     * @param followsRunId   the run a later step should be linked to. Null when that is not known
     * @param namesReceiver  false when a create or an update sent a subscription with no endpoint
     */
    public static NextStepView nextStep(SubscriptionReading reading, VendorExchange exchange, EpaOperation operation, String subscriptionId,
                                        String followsRunId, boolean namesReceiver) {
        SubscriptionOutcome outcome = reading.outcome();
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_SUBSCRIPTION_ID, Texts.present(subscriptionId) ? List.of(subscriptionId) : List.of());
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(followsRunId) ? List.of(followsRunId) : List.of());

        return switch (outcome) {
            case CREATED -> !namesReceiver
                    ? new NextStepView(NextAction.OPEN_CONFIG, "Next: set " + SubscriptionRequestBuilder.CALLBACK_PROPERTY + ", then update this "
                    + "subscription or create another. Until then, follow a request with an inquiry.",
                    "The subscription was created without a url to call back, so no notification can reach this tool.", carry)
                    : new NextStepView(NextAction.START_PAS, "Next: submit a prior authorization request. When its status changes, the "
                    + "service notifies the receiver this subscription names.",
                    "The subscription exists. A notification about a request arrives only after a request is submitted.", carry);
            case READ, LISTED -> new NextStepView(NextAction.FLOW_ENDS, "Nothing follows from a read. Record this result.",
                    "The call read what the service holds and changed nothing.", carry);
            case UPDATED -> new NextStepView(NextAction.FLOW_ENDS, "The update is done. Record this result.",
                    "The service accepted the new state of the subscription.", carry);
            case DELETED -> new NextStepView(NextAction.FLOW_ENDS, "The subscription is gone. Record this result.",
                    "The service accepted the delete, so there is nothing further to do with this subscription.", carry);
            case NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the subscription id. The list shows the ids the service knows.",
                    "The service does not know the id that was asked for. The environment answered normally.", carry);
            case UNRECOGNISED_RESPONSE -> new NextStepView(NextAction.REVIEW, "Next: read the response detail below. This tool cannot say "
                    + "what should happen next.", "The response is not something this tool can read as the answer to this call.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, MALFORMED_RESPONSE -> mayHaveBeenApplied(outcome, exchange, operation)
                    ? new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then list the subscriptions before sending again.",
                    "No usable answer came back, but the request was sent and may have been applied. Sending it again without checking "
                            + "could create a second subscription.", carry)
                    : new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the subscription.", carry);
            case UNREACHABLE -> new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "The service was not reached, so it received nothing. This is a finding about the environment, not about the "
                            + "subscription.", carry);
        };
    }

    /**
     * True when a create, an update or a delete really left this machine and no usable answer came
     * back. The service may have applied it all the same. Never true on the mock profile, where
     * nothing is sent, nor when the service was not reached.
     */
    private static boolean mayHaveBeenApplied(SubscriptionOutcome outcome, VendorExchange exchange, EpaOperation operation) {
        boolean noUsableAnswer = outcome == SubscriptionOutcome.TIMEOUT || outcome == SubscriptionOutcome.SERVICE_ERROR
                || outcome == SubscriptionOutcome.MALFORMED_RESPONSE;
        return operation.write() && noUsableAnswer && exchange.request() != null && exchange.request().sent();
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
