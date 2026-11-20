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
     * Why a notification was not matched to a subscription this tool created. The three are
     * different states, and what the tester should do differs with each, so they are worded apart.
     */
    public enum Unmatched {
        /** The notification names no subscription, so there is none to look up. */
        NAMES_NONE,
        /** The ledger was asked, and no run in it created the subscription the notification names. */
        NOT_IN_LEDGER,
        /** The ledger could not be asked, so whether this tool created the subscription is not known. */
        NOT_ASKED
    }

    /**
     * What arrived. A body that is not a notification is that before anything else. A notification
     * that names no subscription, or one no run of this tool created, is unmatched whatever its
     * type, because that is the fact a tester has to look at first. When the ledger could not be
     * asked, nothing is known about whose subscription it is, and the outcome says that instead of
     * calling it unmatched. Only then does the type code decide, and a type that is absent or
     * outside the value set is its own outcome: it is never taken for an event.
     *
     * @param unmatched why no run of this tool was found to have created the subscription the
     *                  notification names, or null when one was
     */
    public static NotificationOutcome outcome(NotificationReading reading, Unmatched unmatched) {
        if (!reading.notification()) {
            return NotificationOutcome.UNREADABLE_NOTIFICATION;
        }
        if (unmatched != null) {
            // A switch expression with no default: a reason added without an outcome here does not compile.
            return switch (unmatched) {
                case NAMES_NONE, NOT_IN_LEDGER -> NotificationOutcome.UNMATCHED_NOTIFICATION;
                case NOT_ASKED -> NotificationOutcome.UNCHECKED_NOTIFICATION;
            };
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
            case UNCHECKED_NOTIFICATION -> {
                supporting.add("It is recorded and shown all the same. It is not dropped.");
                supporting.add("This says nothing about who created the subscription. It is a finding about this installation of the tool.");
                if (type.code() != null) {
                    supporting.add("Its type: " + type.display() + ".");
                }
                yield "A notification arrived, and the ledger could not be asked which subscription it belongs to.";
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
     * @param unmatched      why it was not matched to a subscription this tool created. Read only
     *                       for the two outcomes that say it was not, and required for them
     */
    public static NextStepView nextStep(NotificationOutcome outcome, NotificationReading reading, String claimId, String submissionRun,
                                        String subscriptionId, Unmatched unmatched) {
        return nextStep(outcome, reading, claimId, submissionRun, subscriptionId, unmatched, false);
    }

    /** @param sample true when the notification is a mock scenario's sample, which the tester can deliver again */
    public static NextStepView nextStep(NotificationOutcome outcome, NotificationReading reading, String claimId, String submissionRun,
                                        String subscriptionId, Unmatched unmatched, boolean sample) {
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_CLAIM_ID, Texts.present(claimId) ? List.of(claimId) : List.of());
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(submissionRun) ? List.of(submissionRun) : List.of());
        carry.put(NextStepView.CARRY_SUBSCRIPTION_ID, Texts.present(subscriptionId) ? List.of(subscriptionId) : List.of());

        return switch (outcome) {
            case NOTIFICATION_RECEIVED, STATUS_RECEIVED -> aboutARequest(reading, claimId, carry);
            // Neither decides a request, so neither is the end of a case's journey. There is only nothing to do.
            case HANDSHAKE_RECEIVED -> new NextStepView(NextAction.NOTHING_FURTHER, "Nothing to do. The subscription is answering.",
                    "A handshake reports on the subscription itself, not on a request.", carry);
            case HEARTBEAT_RECEIVED -> new NextStepView(NextAction.NOTHING_FURTHER, "Nothing to do. The subscription is still there.",
                    "A heartbeat means there was no event to report.", carry);
            case UNRECOGNISED_TYPE -> new NextStepView(NextAction.REVIEW, "Next: read the notification below. This tool cannot say what "
                    + "should happen next.", "The notification does not state a type this tool knows.", carry);
            case UNMATCHED_NOTIFICATION, UNCHECKED_NOTIFICATION -> notMatched(unmatched, sample, carry);
            case UNREADABLE_NOTIFICATION -> new NextStepView(NextAction.REVIEW, "Next: read the payload below as it arrived, and find out "
                    + "what sent it.", "The body is not a subscription notification this tool can read.", carry);
        };
    }

    /**
     * The step for a notification that was not matched to a subscription this tool created. It is
     * worded by why, and never by the outcome alone, so that "nobody here created it" is said only
     * when the ledger was asked and answered. A switch expression with no default: a reason added
     * without wording it here does not compile.
     */
    private static NextStepView notMatched(Unmatched why, boolean sample, Map<String, List<String>> carry) {
        return switch (why) {
            case NAMES_NONE -> new NextStepView(NextAction.REVIEW, "Next: read the notification below and find out what sent it.",
                    "The notification names no subscription, so it cannot be matched to one.", carry);
            // The remedy first: on this tool the usual cause is that no subscription was created here
            // yet. A subscription made elsewhere is the other cause, and comes second. What the
            // ledger holds is already said beside the verdict, so the reason does not say it again.
            case NOT_IN_LEDGER -> new NextStepView(NextAction.REVIEW, sample
                    ? "Next: create a subscription with Create subscription on the PAS tab, then choose Deliver a sample notification again."
                    : "Next: create a subscription with Create subscription on the PAS tab, so that the next notification can be matched to it.",
                    "If a subscription was made another way (by another client, before this ledger began, or by another installation of "
                            + "this tool), the list of subscriptions shows what the service holds.", carry);
            case NOT_ASKED -> new NextStepView(NextAction.REVIEW, "Next: find out why the ledger could not be read. Until it can, this "
                    + "tool cannot say whether it created the subscription this notification names.",
                    "The ledger could not be asked which run created the subscription.", carry);
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
