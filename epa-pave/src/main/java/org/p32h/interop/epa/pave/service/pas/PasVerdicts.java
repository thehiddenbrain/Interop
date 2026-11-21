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
import org.p32h.interop.epa.pave.model.pas.AttachmentRefView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.DecisionSource;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Says what a PAS result means in plain language, what the tester should do next, and lays out
 * the determination grid.
 *
 * <p>The sentences are built from the outcome and the review action codes. The payer's own
 * wording, the disposition above all, is shown elsewhere on the result and is never quoted or
 * interpreted here, so the answer at the top of the screen cannot change because a payer reworded
 * a sentence.
 */
public final class PasVerdicts {

    /**
     * What PAS 2.0.1 does after the payer asks for documentation, and that this tool does not do it:
     * specification.md, updating requests, and additionalinfo.md, $submit-attachment.
     */
    static final String GUIDE_ROUTE = "PAS 2.0.1 sends documentation after the payer asks for it in one of two ways: a claim update, which "
            + "is a new claim with its own identifier that replaces this one (Claim.related, relationship replaces), or the CDex "
            + "operation $submit-attachment. This tool builds neither.";

    private PasVerdicts() {
    }

    /**
     * The verdict, told apart for an inquiry: its headline says it is the answer to an inquiry, and a
     * line under the scenario line says which claim was asked about and which run sent it. Without
     * it an inquiry's result reads as its submission's, word for word.
     *
     * <p>The claim id may be drawn here: it is on screen already, in the inquiry form and in the wire
     * view of this run, and the verdict is not written to the ledger, so nothing more is kept.
     *
     * @param claimId      the claim the inquiry asked about, or null
     * @param followsRunId the run that sent the submission asked about, or null when it is not known
     */
    public static VerdictView verdict(PasReading reading, VendorExchange exchange, EpaOperation operation, String claimId,
                                      String followsRunId) {
        VerdictView verdict = verdict(reading, exchange, operation);
        if (operation != EpaOperation.PAS_INQUIRE) {
            return verdict;
        }
        List<String> supporting = new ArrayList<>(verdict.supporting());
        if (Texts.present(claimId)) {
            // Right after the scenario line, which every mock result keeps first.
            int at = RunSupport.scenarioLine(exchange) != null && !supporting.isEmpty() ? 1 : 0;
            supporting.add(at, "Asked about claim " + claimId.strip() + (Texts.present(followsRunId) ? ", sent by run " + followsRunId.strip()
                    : "") + ".");
        }
        return new VerdictView("Inquiry: " + lowered(verdict.headline()), verdict.outcomeCode(), verdict.outcomeLabel(), verdict.tone(),
                List.copyOf(supporting), verdict.maskedReading());
    }

    /** The first letter lowered, so a headline can follow "Inquiry: ". A word in capitals, such as an acronym, is kept as it is. */
    private static String lowered(String headline) {
        if (headline.length() > 1 && Character.isUpperCase(headline.charAt(0)) && Character.isLowerCase(headline.charAt(1))) {
            return Character.toLowerCase(headline.charAt(0)) + headline.substring(1);
        }
        return headline;
    }

    public static VerdictView verdict(PasReading reading, VendorExchange exchange, EpaOperation operation) {
        PasOutcome outcome = reading.outcome();
        List<ClaimResponseView> responses = reading.detail().claimResponses();
        List<PasDecision> decisions = reading.decisions();
        List<String> supporting = new ArrayList<>();
        // First, so a canned answer is never mistaken for an answer about the member and code on the form.
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case APPROVED -> "The prior authorization is approved.";
            case APPROVED_WITH_CHANGES -> {
                supporting.add("The payer approved the request and changed part of what was asked for. Read each item below.");
                yield "The prior authorization is approved with changes.";
            }
            case PARTIALLY_APPROVED -> {
                supporting.addAll(each(decisions));
                yield "The prior authorization is partially approved.";
            }
            case DENIED -> "The prior authorization is denied.";
            case PENDED -> {
                supporting.add("There is no final decision yet. This is not an approval and not a denial.");
                yield "The prior authorization is pended.";
            }
            case ATTACHMENT_REQUIRED -> {
                long open = responses.stream().flatMap(r -> r.attachments().stream()).filter(AttachmentRefView::open).count();
                supporting.add("The response refers to " + open + (open == 1 ? " request" : " requests") + " for documentation. What is "
                        + "asked for is shown below, in the payer's own words.");
                supporting.add(GUIDE_ROUTE);
                yield "The payer needs more documentation before it will decide.";
            }
            case CONTACT_PAYER -> "The payer asks to be contacted about this request.";
            case CANCELLED -> "The authorization request is cancelled.";
            case NO_ACTION_REQUIRED -> "The payer answered that no action is required for this request.";
            case MIXED -> {
                supporting.addAll(each(decisions));
                yield "The items in this request did not all receive the same decision.";
            }
            case UNRECOGNISED_CODE -> {
                for (PasDecision decision : decisions) {
                    for (CodedValue code : decision.reviewActions()) {
                        if (code.code() != null && !code.recognised()) {
                            supporting.add(prefix(decision, reading.several()) + code.display() + ".");
                        }
                    }
                }
                yield "The service returned a decision code this tool does not recognise.";
            }
            case REQUEST_ERRORS -> {
                long errors = responses.stream().mapToLong(r -> r.errors().size()).sum();
                supporting.add(errors == 0 ? "The response has the outcome code error and lists no error."
                        : "The response reports " + errors + (errors == 1 ? " error" : " errors") + ". Each is listed below with its code "
                        + "and the element it points at.");
                yield "The payer returned errors and no decision.";
            }
            case NO_DECISION -> {
                supporting.add("This is not an approval, and it is not a denial or a pended request either. "
                        + (responses.isEmpty() ? "The response carries no ClaimResponse at all."
                        : "The response carries no review action, so the payer has stated no decision."));
                if (responses.stream().anyMatch(r -> r.disposition().hasValue())) {
                    supporting.add("The response does carry wording from the payer. This tool reads codes and never wording: it is "
                            + "shown below as it came.");
                }
                yield responses.isEmpty() ? "The service answered without a ClaimResponse, so there is no decision."
                        : "The service answered but returned no decision.";
            }
            case MEMBER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this member.";
            }
            case PROVIDER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this provider.";
            }
            case AUTHORIZATION_NOT_FOUND -> {
                supporting.add("This is a finding about the claim id that was asked about, not about the environment.");
                yield "The service does not recognise this authorization request.";
            }
            case NO_MATCHING_AUTHORIZATION -> {
                supporting.add("PAS 2.0.1 answers an inquiry with one ClaimResponse for each prior authorization that matches it. This "
                        + "answer holds none, so the payer holds no authorization matching what was asked about.");
                supporting.add("This is an answer about the claim identifier and the items that were asked about. It is not a decision "
                        + "and not a fault of the environment.");
                yield "No prior authorization matches this inquiry.";
            }
            case REJECTED -> "The service did not accept the request as sent" + status(exchange) + ".";
            case SERVICE_ERROR -> "The service failed" + status(exchange) + ".";
            case TIMEOUT -> "The service did not answer in time.";
            // The same outcome covers a connection that never opened and one that broke after it
            // opened. Only the first is a service that could not be reached.
            case UNREACHABLE -> exchange.reached() == Reached.NO ? "The service could not be reached."
                    : "The connection to the service failed before an answer arrived.";
            case MALFORMED_RESPONSE -> reading.detail().responseKind() == PasResponseKind.NO_BODY
                    ? "The service answered with no body" + status(exchange) + "."
                    : "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        // A played fault is described once, by the error panel beside the verdict: the failure detail a
        // scenario writes reads as a real connection failure under a verdict that nothing was sent. What
        // a played timeout would mean for the submission is said by the next step, as what it would mean;
        // a played refused connection keeps its line, which is as true on mock as it would be on live.
        if (outcome.fault()) {
            if (Texts.present(exchange.failureDetail()) && !PlayedFaults.played(exchange, outcome)) {
                supporting.add(exchange.failureDetail());
            }
            if (operation.write() && noUsableAnswer(outcome) && !exchange.playedTimeout()) {
                supporting.add(exchange.reached() == Reached.NO ? "Nothing was submitted: the request did not reach the payer."
                        : "The payer may have received the submission and acted on it.");
            }
        } else {
            supporting.addAll(facets(reading));
        }
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
    }

    /** What the response says beyond the headline: who the decision covers, the authorization number and its period. */
    private static List<String> facets(PasReading reading) {
        List<String> out = new ArrayList<>();
        PasOutcome outcome = reading.outcome();
        List<PasDecision> decisions = reading.decisions();
        boolean listed = outcome == PasOutcome.PARTIALLY_APPROVED || outcome == PasOutcome.MIXED;
        if (reading.several() && !listed && decisions.stream().allMatch(d -> d.outcome() == outcome)) {
            out.add("This is the decision for each of: " + String.join(", ", decisions.stream().map(PasDecision::subject).distinct().toList()) + ".");
        }
        // Worth saying only where the result names items: a decision each of them shows was not stated for it alone.
        if (decisions.stream().anyMatch(d -> d.subject() != null) && decisions.stream().allMatch(d -> d.source() == DecisionSource.RESPONSE)) {
            out.add("The decision is stated once for the response as a whole, not item by item. PAS 2.0.1 places a review action on "
                    + "each item's adjudication, not on the ClaimResponse itself; it was read all the same.");
        } else if (reading.detail().claimResponses().stream().anyMatch(r -> !r.reviewActions().isEmpty())) {
            out.add("A review action sat on the ClaimResponse itself. PAS 2.0.1 places a review action on each item's adjudication only; "
                    + "it was read all the same.");
        }
        if (reading.detail().claimResponses().stream().flatMap(r -> r.items().stream()).anyMatch(i -> !i.reviewActions().isEmpty())) {
            out.add("A review action sat on an item itself, not on its adjudication, where PAS 2.0.1 places it. It was read all the same.");
        }
        // The authorization number and period of each item, from where PAS 2.0.1 puts them.
        boolean named = reading.several();
        for (PasDecision decision : decisions) {
            String who = named && decision.subject() != null ? decision.subject() + ": " : "";
            if (!decision.authorizationNumbers().isEmpty()) {
                out.add(who + "Authorization number: " + String.join(", ", decision.authorizationNumbers()) + ".");
            }
            String period = period(decision.authorizedFrom(), decision.authorizedTo());
            if (period != null) {
                out.add(who + period);
            }
        }
        for (ClaimResponseView response : reading.detail().claimResponses()) {
            String name = reading.detail().claimResponses().size() > 1 ? response.label() + ": " : "";
            // ClaimResponse.preAuthRef and preAuthPeriod are kept as further sources: no PAS 2.0.1
            // example uses them. Beside the items' own numbers and periods, a line with no subject
            // would read as one more item's, so it says it is the response's; when every decision
            // already states the same, it adds nothing and is left out.
            List<PasDecision> own = response.decisions();
            if (response.preAuthRef().hasValue()) {
                String number = response.preAuthRef().value();
                if (own.isEmpty() || !own.stream().allMatch(d -> d.authorizationNumbers().contains(number))) {
                    boolean beside = own.stream().anyMatch(d -> !d.authorizationNumbers().isEmpty());
                    out.add(name + (beside ? AS_A_WHOLE : "") + "Authorization number: " + number + ".");
                }
            }
            String from = response.preAuthPeriodStart().orNull();
            String to = response.preAuthPeriodEnd().orNull();
            String whole = period(from, to);
            if (whole != null && (own.isEmpty() || !own.stream().allMatch(d -> whole.equals(period(d.authorizedFrom(), d.authorizedTo()))))) {
                boolean beside = own.stream().anyMatch(d -> period(d.authorizedFrom(), d.authorizedTo()) != null);
                out.add(name + (beside ? AS_A_WHOLE : "") + whole);
            }
            if (response.documentationWanted() && outcome != PasOutcome.ATTACHMENT_REQUIRED) {
                out.add(name + "The response also refers to a request for documentation that is not shown as closed.");
            }
            if (outcome != PasOutcome.REQUEST_ERRORS && !response.errors().isEmpty()) {
                out.add(name + "The response also reports " + response.errors().size() + (response.errors().size() == 1 ? " error" : " errors")
                        + ", listed below.");
            }
        }
        return out;
    }

    /** What opens a line taken from the ClaimResponse itself when the items state numbers or periods of their own. */
    private static final String AS_A_WHOLE = "For the response as a whole: ";

    /** A period as a sentence, or null when neither end is stated. */
    private static String period(String from, String to) {
        if (Texts.present(from) && Texts.present(to)) {
            return "Authorized from " + from + " to " + to + ".";
        }
        if (Texts.present(from)) {
            return "Authorized from " + from + ".";
        }
        return Texts.present(to) ? "Authorized until " + to + "." : null;
    }

    /** One line for each decision, so no item's decision is read as another's. */
    private static List<String> each(List<PasDecision> decisions) {
        List<String> out = new ArrayList<>();
        for (PasDecision decision : decisions) {
            String codes = decision.codes().isEmpty() ? "" : " (review action " + String.join(", ", decision.codes()) + ")";
            out.add((decision.subject() == null ? "The request" : decision.subject()) + ": " + itemPhrase(decision.outcome()) + codes + ".");
        }
        return out;
    }

    /** What one item's decision is called in a line about that item. No default, so a new outcome must be worded. */
    private static String itemPhrase(PasOutcome outcome) {
        return switch (outcome) {
            case APPROVED -> "approved";
            case APPROVED_WITH_CHANGES -> "approved with changes";
            case PARTIALLY_APPROVED -> "partially approved";
            case DENIED -> "denied";
            case PENDED -> "pended, with no final decision yet";
            case CONTACT_PAYER -> "the payer asks to be contacted";
            case CANCELLED -> "cancelled";
            case NO_ACTION_REQUIRED -> "no action required";
            case UNRECOGNISED_CODE -> "a decision code this tool does not recognise";
            case MIXED -> "review actions that disagree with each other";
            case NO_DECISION -> "no decision was returned for this item. That is not an approval";
            case ATTACHMENT_REQUIRED, REQUEST_ERRORS, MEMBER_NOT_FOUND, PROVIDER_NOT_FOUND, AUTHORIZATION_NOT_FOUND, NO_MATCHING_AUTHORIZATION,
                    REJECTED,
                    SERVICE_ERROR, TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> lower(outcome.label());
        };
    }

    /**
     * What the tester should do next, tied to what the response said.
     *
     * @param followsRunId the run a later step should be linked to: this run for a submission, the
     *                     submission it asked about for an inquiry. Null when that is not known
     */
    public static NextStepView nextStep(PasReading reading, VendorExchange exchange, EpaOperation operation, String followsRunId) {
        return nextStep(reading, exchange, operation, followsRunId, null);
    }

    /**
     * What the tester should do next, tied to what the response said, naming this run where the
     * tester is asked to record and report it.
     *
     * @param followsRunId the run a later step should be linked to: this run for a submission, the
     *                     submission it asked about for an inquiry. Null when that is not known
     * @param runId        this run, or null when it is not known yet: the step then says "this run"
     */
    public static NextStepView nextStep(PasReading reading, VendorExchange exchange, EpaOperation operation, String followsRunId,
                                        String runId) {
        return PlayedFaults.rehearsed(exchange, reading.outcome(), asIfReal(reading, exchange, operation, followsRunId, runId),
                operation.write()
                        ? "Had this been a real service, the payer might have received the submission: choose Send inquiry before "
                        + "sending it again. Sending it again without checking could create a second request."
                        : PlayedFaults.aboutTheEnvironment("the member or the code"));
    }

    /** The next step as it would be after a real service gave this answer. */
    private static NextStepView asIfReal(PasReading reading, VendorExchange exchange, EpaOperation operation, String followsRunId,
                                         String runId) {
        PasOutcome outcome = reading.outcome();
        // What a status check or a resubmission starts from. Carried whatever the outcome, so a
        // tester can ask for the status of any submission that has a claim id.
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_CLAIM_ID, Texts.present(reading.sent().claimId()) ? List.of(reading.sent().claimId()) : List.of());
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(followsRunId) ? List.of(followsRunId) : List.of());
        carry.put(NextStepView.CARRY_PROCEDURE_CODES, reading.sent().codes());

        return switch (outcome) {
            case APPROVED, APPROVED_WITH_CHANGES -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer approved the request, so there is nothing further to send.", carry);
            case PARTIALLY_APPROVED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer gave a final decision on every item: part of the request is approved and part is not.", carry);
            case DENIED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer denied the request. That is a final decision, and a result to record whether or not it was expected.", carry);
            case CANCELLED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The request is cancelled, so there is no decision to wait for.", carry);
            case NO_ACTION_REQUIRED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer answered that no action is required for this request.", carry);
            case PENDED -> new NextStepView(NextAction.CHECK_STATUS, "Next: choose Send inquiry to ask for the status, or wait for a notification.",
                    "The request is pended: the payer has not decided yet.", carry);
            case ATTACHMENT_REQUIRED -> new NextStepView(NextAction.START_PAS,
                    "Next: send the documentation the payer asked for. Choosing Send PAS request again sends a new request with the same "
                            + "claim identifier, which is not the PAS 2.0.1 way.",
                    "The payer will not decide until it has the documentation it refers to. " + GUIDE_ROUTE, carry);
            case CONTACT_PAYER -> new NextStepView(NextAction.REVIEW, "Next: contact the payer about this request. Nothing more can be done "
                    + "from this tool.", "The payer answered with the review action that asks to be contacted.", carry);
            case MIXED -> reading.decisions().stream().anyMatch(d -> d.outcome() == PasOutcome.PENDED)
                    ? new NextStepView(NextAction.CHECK_STATUS, "Next: choose Send inquiry to ask for the status of the items that are still open.",
                    "At least one item is pended, so the request is not finished.", carry)
                    : recordAndReport(runId, "items decided differently", "The items did not all receive the same decision and none "
                    + "is pended, so there is no one next step for the request, and this tool does not pick one. The decision for each "
                    + "item is below.", carry);
            case UNRECOGNISED_CODE -> recordAndReport(runId, "decision code not recognised", "The response carries a decision code this "
                    + "tool does not know the meaning of, and this tool does not guess what it means. The code is in the response detail "
                    + "below.", carry);
            case NO_DECISION -> recordAndReport(runId, "no decision returned", "The response carries no review action, so it does not say "
                    + "whether the request is approved, pended or denied, and this tool does not guess. What came back is in the response "
                    + "detail below.", carry);
            case REQUEST_ERRORS -> new NextStepView(NextAction.FIX_REQUEST, "Next: read the errors below, correct the request and submit it again.",
                    "The payer could not process the request as it was sent.", carry);
            case MEMBER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the member ID against the test data.",
                    "The service does not recognise the member. The environment answered normally.", carry);
            case PROVIDER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the NPI against the test data.",
                    "The service does not recognise the provider. The environment answered normally.", carry);
            case AUTHORIZATION_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA,
                    "Next: check the claim id against the submission it should belong to.",
                    "The service does not recognise the request that was asked about. The environment answered normally.", carry);
            case NO_MATCHING_AUTHORIZATION -> new NextStepView(NextAction.FIX_TEST_DATA,
                    "Next: check the claim identifier and the items of the inquiry against the submission it asks about.",
                    "The payer holds no prior authorization matching what the inquiry asked about. The environment answered normally.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, MALFORMED_RESPONSE, UNREACHABLE -> afterNoUsableAnswer(outcome, exchange, operation, carry);
        };
    }

    /** The outcomes in which the call was made and nothing usable came back. A rejection is not one: the service said no. */
    private static boolean noUsableAnswer(PasOutcome outcome) {
        return outcome == PasOutcome.TIMEOUT || outcome == PasOutcome.UNREACHABLE || outcome == PasOutcome.SERVICE_ERROR
                || outcome == PasOutcome.MALFORMED_RESPONSE;
    }

    /**
     * The next step when the call was made and nothing usable came back.
     *
     * <p>An inquiry changes nothing, so it is simply sent again. For a submission it turns on
     * whether the request can have reached the payer, which the exchange says: one that cannot
     * have reached it was never submitted and can be sent again, and one that may have reached it
     * may already be a request the payer holds, so its status is asked for first. The same holds
     * on the mock profile, where the scenario says which of the two it models.
     */
    private static NextStepView afterNoUsableAnswer(PasOutcome outcome, VendorExchange exchange, EpaOperation operation,
                                                    Map<String, List<String>> carry) {
        if (!operation.write()) {
            return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    (outcome == PasOutcome.UNREACHABLE ? "No answer came back." : "No usable answer came back.")
                            + " This is a finding about the environment, not about the member or the code.", carry);
        }
        if (exchange.reached() == Reached.NO) {
            return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "Nothing was submitted, because the request did not reach the payer. It is safe to send the same request again.", carry);
        }
        return new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then choose Send inquiry before sending again.",
                "The payer may have received the submission and acted on it. Ask for its status with Send inquiry first: sending it "
                        + "again without checking could create a second request.", carry);
    }

    /**
     * The next step when the service answered with a 4xx status. The body is not always at fault:
     * the same outcome covers credentials that were not accepted and an address that does not exist.
     */
    /**
     * The next step when the service answered with nothing this tool can act on. The headline is for
     * the business tester, who can act on the result without reading the response: record the run
     * by its id as what came back, and report it. The reason keeps the way to the detail for whoever
     * looks into it. Neither says whose fault it is, nor guesses what the answer meant. The action
     * stays REVIEW, so the ledger, History and a chain read these runs as they did.
     *
     * @param recordAs what the run is recorded as, in a few plain words
     */
    static NextStepView recordAndReport(String runId, String recordAs, String reason, Map<String, List<String>> carry) {
        String which = Texts.present(runId) ? "run " + runId : "this run";
        return new NextStepView(NextAction.REVIEW, "Next: record " + which + " as \"" + recordAs + "\" and report it to the people who "
                + "look after the service.", reason, carry);
    }

    private static NextStepView notAccepted(VendorExchange exchange, Map<String, List<String>> carry) {
        int status = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        if (status == 401 || status == 403) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials on the Config tab.",
                    "The service answered HTTP " + status + ", which means it did not accept the credentials this tool sent, or none "
                            + "were sent. The request itself may be fine.", carry);
        }
        if (status == 404) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the PAS url on the Config tab.",
                    "The service answered HTTP 404 and did not say that the member, the provider or the request is unknown, so the "
                            + "address is the likely cause.", carry);
        }
        return new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                "The service did not accept the request as it was sent.", carry);
    }

    /**
     * The determination grid: the decision codes and what they mean, one group of rows for each
     * item. When the response carried no ClaimResponse the two elements that would have answered
     * are still listed, as not returned, so an empty answer looks different from a decision at a
     * glance. The same is done for each item that was asked about and has no review action.
     */
    public static List<DeterminationRow> determination(PasReading reading) {
        List<DeterminationRow> rows = new ArrayList<>();
        List<ClaimResponseView> responses = reading.detail().claimResponses();
        if (responses.isEmpty()) {
            rows.add(DeterminationRow.notReturned(null, "Decision", PasCodes.REVIEW_ACTION));
            rows.add(DeterminationRow.notReturned(null, "Processing status", PasCodes.CLAIM_OUTCOME));
            return rows;
        }
        boolean named = reading.several();
        for (ClaimResponseView response : responses) {
            String whole = responses.size() > 1 ? response.label() : null;
            for (PasDecision decision : response.decisions()) {
                String subject = named ? decision.subject() : null;
                if (decision.reviewActions().isEmpty()) {
                    rows.add(DeterminationRow.notReturned(subject, "Decision", PasCodes.REVIEW_ACTION));
                }
                for (CodedValue code : decision.reviewActions()) {
                    // A decision the item took from the response as a whole says so, so it is not shown as the item's own.
                    String display = code.display() == null || decision.source() != DecisionSource.RESPONSE || !named ? code.display()
                            : code.display() + " (stated for the whole response)";
                    rows.add(new DeterminationRow(subject, "Decision", code.code(), display, PasCodes.REVIEW_ACTION, code.recognised(),
                            code.presence()));
                }
                decision.reasonCodes().forEach(code -> rows.add(new DeterminationRow(subject, "Decision reason", code, null,
                        PasCodes.REVIEW_REASON, true, Presence.VALUE)));
                decision.errorCodes().forEach(code -> rows.add(new DeterminationRow(subject, "Error", code, null, PasCodes.ERROR_CODE, true,
                        Presence.VALUE)));
                // Where PAS 2.0.1 puts an item's authorization number and period.
                decision.authorizationNumbers().forEach(number -> rows.add(new DeterminationRow(subject, "Authorization number", number,
                        null, PasCodes.REVIEW_ACTION_NUMBER, true, Presence.VALUE)));
                String period = period(decision.authorizedFrom(), decision.authorizedTo());
                if (period != null) {
                    rows.add(new DeterminationRow(subject, "Authorized period", Texts.orElse(decision.authorizedFrom(), "") + "/"
                            + Texts.orElse(decision.authorizedTo(), ""), period, PasCodes.ITEM_PRE_AUTH_PERIOD, true, Presence.VALUE));
                }
            }
            CodedValue status = response.outcome();
            rows.add(new DeterminationRow(whole, "Processing status", status.code(), status.display(), PasCodes.CLAIM_OUTCOME,
                    status.recognised(), status.presence()));
            if (response.preAuthRef().presence() != Presence.ABSENT) {
                rows.add(new DeterminationRow(whole, "Authorization number", response.preAuthRef().orNull(), null, "preAuthRef", true,
                        response.preAuthRef().presence()));
            }
            for (AttachmentRefView attachment : response.attachments()) {
                rows.add(new DeterminationRow(whole, "Documentation request", attachment.status().orNull(),
                        attachment.open() ? "Not shown as closed" : "Closed", "communicationRequest", true, attachment.status().presence()));
            }
        }
        return rows;
    }

    private static String prefix(PasDecision decision, boolean named) {
        return named && decision.subject() != null ? decision.subject() + ": " : "";
    }

    private static String status(VendorExchange exchange) {
        return exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
    }

    private static String lower(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
