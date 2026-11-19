package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Says what arrived at the notification receiver, in plain language, and what the tester should do
 * about it.
 *
 * <p>Everything here is built from the kind of body, the notification type code, the subscription
 * status code, the review action codes of a ClaimResponse the notification carries, and the runs
 * the ledger matched it to. No sentence the sender wrote is quoted or interpreted.
 */
public final class NotificationVerdicts {

    private NotificationVerdicts() {
    }

    /**
     * What arrived. A body that is not a notification is that before anything else. A notification
     * for a subscription no run of this tool created is unmatched whatever its type, because that
     * is the fact a tester has to look at first. Only then does the type code decide, and a type
     * that is absent or outside the value set is its own outcome: it is never taken for an event.
     *
     * @param matched true when a run of this tool created the subscription the notification names
     */
    public static NotificationOutcome outcome(NotificationReading reading, boolean matched) {
        if (!reading.notification()) {
            return NotificationOutcome.UNREADABLE_NOTIFICATION;
        }
        if (!matched) {
            return NotificationOutcome.UNMATCHED_NOTIFICATION;
        }
        String type = reading.status().type().code();
        if (type == null) {
            return NotificationOutcome.UNRECOGNISED_TYPE;
        }
        return switch (type) {
            case PasCodes.TYPE_HANDSHAKE -> NotificationOutcome.HANDSHAKE_RECEIVED;
            case PasCodes.TYPE_HEARTBEAT -> NotificationOutcome.HEARTBEAT_RECEIVED;
            case PasCodes.TYPE_EVENT, PasCodes.TYPE_QUERY_EVENT -> NotificationOutcome.NOTIFICATION_RECEIVED;
            case PasCodes.TYPE_QUERY_STATUS -> NotificationOutcome.STATUS_RECEIVED;
            default -> NotificationOutcome.UNRECOGNISED_TYPE;
        };
    }

    /**
     * @param correlationNotes how each link to a run was established, or why it was not
     * @param sampleScenarioId the mock scenario a sample was built from, or null for one that arrived
     * @param cut              what was lost when the body was longer than the receiver holds, or null
     */
    public static VerdictView verdict(NotificationOutcome outcome, NotificationReading reading, List<String> correlationNotes,
                                      String sampleScenarioId, String cut) {
        List<String> supporting = new ArrayList<>();
        // First, so a sample is never mistaken for something a service sent.
        if (sampleScenarioId != null) {
            supporting.add("This is a sample delivered on the mock profile from scenario " + sampleScenarioId + ". It did not come from a service.");
        }
        CodedValue type = reading.status().type();
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case NOTIFICATION_RECEIVED -> "An event notification arrived for a subscription this tool created.";
            case HANDSHAKE_RECEIVED -> {
                supporting.add("The service is checking that this receiver answers. No request has changed.");
                yield "A handshake arrived for a subscription this tool created.";
            }
            case HEARTBEAT_RECEIVED -> {
                supporting.add("Nothing has happened on the subscription. The service is saying that it is still there.");
                yield "A heartbeat arrived for a subscription this tool created.";
            }
            case STATUS_RECEIVED -> "A status report arrived for a subscription this tool created.";
            case UNRECOGNISED_TYPE -> {
                supporting.add(type.code() == null ? "It states no notification type." : type.display() + ".");
                yield "A notification arrived whose type this tool does not recognise.";
            }
            case UNMATCHED_NOTIFICATION -> {
                supporting.add("It is recorded and shown all the same. It is not dropped.");
                if (type.code() != null) {
                    supporting.add("Its type: " + type.display() + ".");
                }
                yield "A notification arrived that matches no subscription this tool created.";
            }
            case UNREADABLE_NOTIFICATION -> {
                if (Texts.present(reading.kindNote())) {
                    supporting.add(reading.kindNote());
                }
                supporting.add("It is recorded and shown all the same. It is not dropped.");
                yield "Something arrived at the receiver that is not a subscription notification.";
            }
        };
        if (reading.notification()) {
            CodedValue status = reading.status().subscriptionStatus();
            if (status.code() != null) {
                supporting.add("The subscription status it reports: " + status.display() + ".");
            }
            if (PasCodes.CONTENT_ID_ONLY.equals(reading.payloadContent())) {
                supporting.add("It carries ids only: it says that something changed and not what. An inquiry shows the decision.");
            }
        }
        for (ClaimResponseView response : reading.carried().claimResponses()) {
            List<String> codes = response.decisions().stream().flatMap(decision -> decision.codes().stream()).distinct().toList();
            supporting.add("It carries a ClaimResponse. What its codes establish: " + lower(response.decision().label())
                    + (codes.isEmpty() ? "" : " (review action " + String.join(", ", codes) + ")") + ".");
        }
        supporting.addAll(correlationNotes);
        if (cut != null) {
            supporting.add(cut);
        }
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
    }

    /**
     * What the tester should do next.
     *
     * @param claimId        the claim the notification is about, when that could be established
     * @param submissionRun  the submission it was matched to, or null
     * @param subscriptionId the subscription it names, or null
     */
    public static NextStepView nextStep(NotificationOutcome outcome, NotificationReading reading, String claimId, String submissionRun,
                                        String subscriptionId) {
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_CLAIM_ID, Texts.present(claimId) ? List.of(claimId) : List.of());
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(submissionRun) ? List.of(submissionRun) : List.of());
        carry.put(NextStepView.CARRY_SUBSCRIPTION_ID, Texts.present(subscriptionId) ? List.of(subscriptionId) : List.of());

        return switch (outcome) {
            case NOTIFICATION_RECEIVED, STATUS_RECEIVED -> aboutARequest(reading, claimId, carry);
            case HANDSHAKE_RECEIVED -> new NextStepView(NextAction.FLOW_ENDS, "Nothing to do. The subscription is answering.",
                    "A handshake reports on the subscription itself, not on a request.", carry);
            case HEARTBEAT_RECEIVED -> new NextStepView(NextAction.FLOW_ENDS, "Nothing to do. The subscription is still there.",
                    "A heartbeat means there was no event to report.", carry);
            case UNRECOGNISED_TYPE -> new NextStepView(NextAction.REVIEW, "Next: read the notification below. This tool cannot say what "
                    + "should happen next.", "The notification does not state a type this tool knows.", carry);
            case UNMATCHED_NOTIFICATION -> new NextStepView(NextAction.REVIEW, "Next: find out who created the subscription it names. The "
                    + "list of subscriptions shows what the service holds.",
                    "No run in the ledger created a subscription with the id this notification names.", carry);
            case UNREADABLE_NOTIFICATION -> new NextStepView(NextAction.REVIEW, "Next: read the payload below as it arrived, and find out "
                    + "what sent it.", "The body is not a subscription notification this tool can read.", carry);
        };
    }

    private static NextStepView aboutARequest(NotificationReading reading, String claimId, Map<String, List<String>> carry) {
        List<PasDecision> decisions = reading.carried().claimResponses().stream().flatMap(response -> response.decisions().stream()).toList();
        if (!decisions.isEmpty() && decisions.stream().allMatch(decision -> decision.outcome().finalDecision())) {
            return new NextStepView(NextAction.FLOW_ENDS, "The decision has arrived. Record this result.",
                    "The notification carries the ClaimResponse, and its review action is a final decision.", carry);
        }
        if (Texts.present(claimId)) {
            return new NextStepView(NextAction.CHECK_STATUS, "Next: ask for the status of the request with an inquiry.",
                    decisions.isEmpty() ? "The notification says that something changed on the request and does not carry the decision."
                            : "The ClaimResponse the notification carries does not state a final decision.", carry);
        }
        return new NextStepView(NextAction.REVIEW, "Next: read the notification below. This tool cannot tell which request it is about.",
                "The notification could not be tied to a claim this tool submitted, so there is nothing to ask the status of.", carry);
    }

    private static String lower(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
