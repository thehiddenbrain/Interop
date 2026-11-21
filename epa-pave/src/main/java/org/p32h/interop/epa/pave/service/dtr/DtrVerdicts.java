package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.service.run.PlayedTimeouts;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Says what a DTR result means in plain language, and what the tester should do next.
 *
 * <p>The headline is chosen by the outcome alone, so the answer at the top of the screen cannot
 * change because a payer reworded a question. The lines beneath it name questionnaires and
 * questions the way the payer named them, because that is how a tester finds them, and they never
 * quote an answer: an answer can be clinical, and the answer set has its own place on the result.
 *
 * <p>Every DTR result also says which operation it exercised and that no SMART launch was
 * involved ({@link #scope}). This tool drives the server-side operations behind a provider-facing
 * launch, and a tester must not come away believing they exercised the launch. The sentence closes
 * the notes on how the request or the response was made, not the verdict: the verdict keeps to the
 * answer (how many questions, what is asked, what is required and unanswered), and the notes on
 * information origin and authorship stay with the form and the findings, where an analyst reads them.
 */
public final class DtrVerdicts {

    /** First under every result of a step that was completed inside the tool: where the response came from is part of the answer. */
    static final String NOTHING_SENT = "Nothing was sent to the service for this step. The response was put together inside this tool.";

    private static final String NOT_A_LAUNCH = " operation behind a provider-facing SMART launch, not the launch itself, which this tool "
            + "does not host.";

    private DtrVerdicts() {
    }

    /**
     * The one sentence every DTR result carries about what was and was not exercised.
     *
     * @param operation the DTR step the result belongs to
     */
    public static String scope(EpaOperation operation) {
        return switch (operation) {
            case DTR_QUESTIONNAIRE_PACKAGE -> "This step exercised the $questionnaire-package" + NOT_A_LAUNCH;
            case DTR_NEXT_QUESTION -> "This step exercised the next-question" + NOT_A_LAUNCH;
            // The response step, which makes no call at all.
            default -> "This step was completed inside this tool and exercised neither a DTR operation nor a provider-facing SMART "
                    + "launch, which this tool does not host.";
        };
    }

    /**
     * @param operation the DTR step the result belongs to
     * @param exchange  the call that was made, or null for a step completed inside the tool
     */
    public static VerdictView verdict(EpaOperation operation, DtrReading reading, VendorExchange exchange) {
        DtrOutcome outcome = reading.outcome();
        DtrDetail detail = reading.detail();
        FormStateView form = detail.form();
        List<QuestionnaireView> questionnaires = detail.questionnaires();
        List<String> supporting = new ArrayList<>();
        // First, so a canned answer is never mistaken for an answer about the member and code on the form.
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        if (!detail.sent()) {
            supporting.add(NOTHING_SENT);
        }
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case PACKAGE_LOADED -> {
                questionnaires.forEach(questionnaire -> supporting.add(describe(questionnaire)));
                yield questionnaires.size() == 1 ? "The questionnaire package was loaded with one questionnaire."
                        : "The questionnaire package was loaded with " + questionnaires.size() + " questionnaires.";
            }
            case PACKAGE_INCOMPLETE -> {
                detail.notReturned().forEach(canonical -> supporting.add("Asked for and not returned: " + canonical + "."));
                questionnaires.forEach(questionnaire -> supporting.add(describe(questionnaire)));
                yield "The package does not hold every questionnaire that was asked for.";
            }
            case NO_QUESTIONNAIRE -> {
                detail.notReturned().forEach(canonical -> supporting.add("Asked for and not returned: " + canonical + "."));
                int bundles = detail.bundles().size();
                if (bundles > 0) {
                    supporting.add("The package holds " + count(bundles, "bundle") + " and no questionnaire in " + (bundles == 1 ? "it" : "them")
                            + ". DTR 2.0.1 does not provide for that: a package bundle starts with its questionnaire (dtrb-1).");
                } else if (detail.responseKind() == DtrResponseKind.PARAMETERS) {
                    supporting.add("The DTR guide, from STU 2.1.0 on, has a service return no package bundle when no questionnaire needs to "
                            + "be completed.");
                    supporting.add("Under DTR 2.0.1, the version this tool is held to, a package response holds at least one PackageBundle, "
                            + "so this answer follows a later version.");
                }
                supporting.add("This tool reports what arrived. It does not turn an empty package into an answer that no documentation is "
                        + "needed.");
                yield "The service answered but returned no questionnaire.";
            }
            case MALFORMED_QUESTIONNAIRE -> {
                for (QuestionnaireView questionnaire : questionnaires) {
                    questionnaire.problems().forEach(problem -> supporting.add(questionnaire.subject() + ": " + problem));
                }
                if (Texts.present(detail.responseNote())) {
                    supporting.add(detail.responseNote());
                }
                yield "The service answered, but what it sent cannot be used as a questionnaire.";
            }
            case NEXT_QUESTION -> form != null && form.asked().size() > 1
                    ? "The service returned the next " + form.asked().size() + " questions." : "The service returned the next question.";
            case NO_QUESTION_RETURNED -> {
                supporting.add("This is not the same as a completed response. The service has not said the questionnaire is finished.");
                yield "The service returned no question and did not report the response as complete.";
            }
            case UNRECOGNISED_STATUS -> {
                supporting.add("This tool acts on the statuses " + OnyxDtrConventions.STATUS_IN_PROGRESS + " and "
                        + OnyxDtrConventions.STATUS_COMPLETED + ". Any other status is shown and not interpreted.");
                yield "The service returned a response status this tool does not recognise.";
            }
            case RESPONSE_COMPLETED -> "The questionnaire response is complete.";
            case NOTHING_REQUIRED -> {
                supporting.add("That is an answer the service gave through the loop. A package that holds no questionnaire is a different "
                        + "result, and is reported as an empty package.");
                yield "The response is complete and no question was asked.";
            }
            case RESPONSE_NOT_COMPLETE -> "The questionnaire response is not complete.";
            case REQUIRED_UNANSWERED -> {
                if (form != null && form.status() != null && form.status().is(OnyxDtrConventions.STATUS_COMPLETED)) {
                    supporting.add("The response carries the status " + OnyxDtrConventions.STATUS_COMPLETED + " all the same. This tool "
                            + "does not report it as complete.");
                }
                yield form != null && form.requiredUnanswered().size() > 1
                        ? form.requiredUnanswered().size() + " required questions have no answer." : "A required question has no answer.";
            }
            case MEMBER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this member.";
            }
            case PROVIDER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this provider.";
            }
            case REJECTED -> "The service did not accept the request as sent" + status(exchange) + ".";
            case SERVICE_ERROR -> "The service failed" + status(exchange) + ".";
            case TIMEOUT -> "The service did not answer in time.";
            case UNREACHABLE -> "The service could not be reached.";
            case MALFORMED_RESPONSE -> detail.responseKind() == DtrResponseKind.NO_BODY
                    ? "The service answered with no body" + status(exchange) + "."
                    : "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        // A played timeout is described once, by the error panel beside the verdict.
        if (outcome.fault() && exchange != null && Texts.present(exchange.failureDetail()) && !exchange.playedTimeout()) {
            supporting.add(exchange.failureDetail());
        }
        if (form != null) {
            supporting.addAll(formLines(form));
        }
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
    }

    /**
     * The name of the action a next step asks for, as the action's own label gives it, which is
     * the name of the button on the DTR tab. A headline names the action by this and by nothing
     * else, so one action keeps one name from the next step to the button.
     */
    static String button(NextAction action) {
        String label = action.label().strip();
        return label.endsWith(".") ? label.substring(0, label.length() - 1) : label;
    }

    /** One questionnaire of a package: which kind it is and why, and how much it asks. */
    private static String describe(QuestionnaireView questionnaire) {
        StringBuilder line = new StringBuilder(questionnaire.subject()).append(": ").append(lower(questionnaire.kindBasis()));
        if (questionnaire.questionCount() == 0) {
            line.append(questionnaire.kind() == QuestionnaireKind.ADAPTIVE
                    ? " It holds no questions of its own: the service asks them one step at a time." : " It holds no questions.");
        } else {
            line.append(" It holds ").append(count(questionnaire.questionCount(), "question")).append(", ")
                    .append(questionnaire.requiredCount()).append(" of them required.");
            int arrived = prepopulated(questionnaire.items());
            if (arrived > 0) {
                line.append(" The package arrived with answers for ").append(arrived).append(" of them.");
            }
        }
        if (questionnaire.arrivedWith() != null && questionnaire.kind() == QuestionnaireKind.ADAPTIVE) {
            // The guides have the first next-question call send the response the package carries, so the tester is told there is one.
            line.append(" The package carries the response to start it from.");
        }
        return line.toString();
    }

    private static int prepopulated(List<ItemView> items) {
        int total = 0;
        for (ItemView item : items) {
            total += (item.prepopulated().isEmpty() ? 0 : 1) + prepopulated(item.items());
        }
        return total;
    }

    /** The question count, the completion status, what is now asked, what is missing and what changed, at every step. */
    private static List<String> formLines(FormStateView form) {
        List<String> lines = new ArrayList<>();
        lines.add("Question count so far: " + form.questionCount() + ". Answered: " + form.answeredCount() + ". Completion status: "
                + status(form.status()) + ".");
        if (!form.asked().isEmpty()) {
            lines.add("Now asked: " + named(form.asked()) + ".");
        }
        if (!form.requiredUnanswered().isEmpty()) {
            lines.add("Required and unanswered: " + named(form.requiredUnanswered()) + ".");
        }
        if (!form.undetermined().isEmpty()) {
            lines.add("Not judged, because a rule this tool does not apply decides whether they still need an answer: "
                    + named(form.undetermined()) + ".");
        }
        if (form.changes() != null) {
            lines.add(form.changes().summary());
        }
        // The notes (who is named as author, the information-origin marks, what the response leaves out) stay with the form in the
        // detail and in the findings, where an analyst reads them. The strip keeps to the answer.
        return lines;
    }

    private static String status(DtrCode status) {
        if (status == null || status.presence() == Presence.ABSENT) {
            return "not stated";
        }
        if (status.code() == null) {
            return status.presence() == Presence.NULL ? "stated as null" : "stated as empty";
        }
        return status.recognised() ? status.code() : lower(status.display());
    }

    /**
     * What the tester should do next, tied to what the step established, with what the next step
     * starts from. The chain uses this to offer the next step.
     *
     * @param exchange the call that was made, or null for a step completed inside the tool
     * @param handoff  what this step hands on
     */
    public static NextStepView nextStep(EpaOperation operation, DtrReading reading, VendorExchange exchange, DtrHandoff handoff) {
        DtrOutcome outcome = reading.outcome();
        DtrDetail detail = reading.detail();
        boolean complete = outcome == DtrOutcome.RESPONSE_COMPLETED || outcome == DtrOutcome.NOTHING_REQUIRED;
        // Only a response this tool reports as complete is handed to the prior authorization request.
        String completed = complete && !detail.questionnaireResponses().isEmpty() ? detail.questionnaireResponses().get(0).json() : null;
        // DTR 2.0.1 requires a QuestionnaireResponse to hold at least one item. One the service completed with none means its
        // questionnaire is done, so it is taken off what is still to answer and PAS is offered once the others are answered,
        // but it is not handed to PAS.
        boolean itemless = completed != null && itemless(completed);
        List<String> done = itemless ? handoff.named(completed) : List.of();
        Map<String, List<String>> carry = handoff.carry(itemless ? null : completed, done);
        // A package can hold several questionnaires. PAS is the next step only when each of them has a completed response.
        List<String> remaining = complete ? handoff.stillToAnswer(completed) : List.of();
        boolean local = operation == EpaOperation.DTR_RESPONSE;
        boolean adaptive = !local || answeredThroughTheLoop(detail);

        return switch (outcome) {
            case PACKAGE_LOADED -> afterPackage(detail.questionnaires(), carry);
            case PACKAGE_INCOMPLETE -> new NextStepView(NextAction.REVIEW, "Next: read which questionnaires were not returned, below.",
                    "The request asked for questionnaires by canonical url and the package does not hold all of them. The ones it does "
                            + "hold can still be answered.", carry);
            case NO_QUESTIONNAIRE -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below, and check it against what CRD said about documentation for this order.",
                    "The service returned no questionnaire. This tool does not take that as an answer that no documentation is needed, so "
                            + "it cannot say what should happen next.", carry);
            case MALFORMED_QUESTIONNAIRE -> new NextStepView(NextAction.REVIEW,
                    "Next: read the problems listed with this result and report them as a defect in the service.",
                    "What the service sent cannot be used as a questionnaire, so there is nothing to answer.", carry);
            case NEXT_QUESTION -> new NextStepView(NextAction.ANSWER_NEXT_QUESTION,
                    "Next: answer what is now asked, then choose " + button(NextAction.ANSWER_NEXT_QUESTION) + ".",
                    "The response is in progress and the service is waiting for an answer.", carry);
            case NO_QUESTION_RETURNED, UNRECOGNISED_STATUS -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    outcome == DtrOutcome.NO_QUESTION_RETURNED
                            ? "The service asked nothing more and did not report the response as complete, so the loop can neither continue "
                            + "nor end."
                            : "The status of the response is not one this tool acts on, so it cannot tell whether the loop continues.", carry);
            case RESPONSE_COMPLETED -> !remaining.isEmpty() ? anotherRemains(handoff, completed, remaining, carry, itemless, done)
                    : toPas(carry, itemless, done, adaptive ? "The service reported the response as completed, and every required question "
                    + "that is enabled has an answer." : "Every required question that is enabled has an answer.");
            case NOTHING_REQUIRED -> !remaining.isEmpty() ? anotherRemains(handoff, completed, remaining, carry, itemless, done)
                    : toPas(carry, itemless, done, itemless ? "The service completed this response without asking a question."
                    : "The response is complete without a question having been asked, so there is no documentation to collect first.");
            case RESPONSE_NOT_COMPLETE -> adaptive
                    ? new NextStepView(NextAction.ANSWER_NEXT_QUESTION, "Next: continue the adaptive questionnaire: choose " + button(NextAction.ANSWER_NEXT_QUESTION) + ".",
                    "Only the service can complete an adaptive response, and it has not reported this one as completed.", carry)
                    : notJudged(detail)
                    ? new NextStepView(NextAction.SUBMIT_RESPONSE,
                    "Next: answer the items this tool could not judge, named with this result, then choose " + button(NextAction.SUBMIT_RESPONSE)
                            + ".",
                    "A rule this tool does not apply decides whether an item still needs an answer, and that item has none, so this tool "
                            + "cannot say the response is complete.", carry)
                    : new NextStepView(NextAction.REVIEW, "Next: read how this questionnaire is meant to be answered, below.",
                    "This tool cannot tell whether the questionnaire is adaptive or static, so it cannot say its response is complete.", carry);
            case REQUIRED_UNANSWERED -> adaptive
                    ? new NextStepView(NextAction.ANSWER_NEXT_QUESTION,
                    "Next: answer the required questions named with this result, then choose " + button(NextAction.ANSWER_NEXT_QUESTION) + ".",
                    "A required question that is enabled has no answer, so the response is not complete, whatever status it carries.", carry)
                    : new NextStepView(NextAction.SUBMIT_RESPONSE,
                    "Next: answer the required questions named with this result, then choose " + button(NextAction.SUBMIT_RESPONSE) + ".",
                    "A required question that is enabled has no answer, so the response is not complete.", carry);
            case MEMBER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the member ID against the test data.",
                    "The service does not recognise the member. The environment answered normally.", carry);
            case PROVIDER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the NPI against the test data.",
                    "The service does not recognise the provider. The environment answered normally.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> PlayedTimeouts.rehearsed(exchange,
                    new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                            "No usable answer came back. This is a finding about the environment, not about the member or the questionnaire.",
                            carry),
                    PlayedTimeouts.aboutTheEnvironment("the member or the questionnaire"));
        };
    }

    /**
     * The step after a response that is complete while a questionnaire of the same package has no
     * completed response that names it. Each questionnaire is answered its own way and this step
     * does not know which way the others are, so it names them and leaves the choice of step to the
     * tester. It never offers PAS: the request would go without part of the documentation the
     * payer asked for.
     *
     * <p>A complete response that names none of the questionnaires of the package is a different
     * finding, and is worded as one: nothing more may need answering, but this tool cannot tell.
     */
    private static NextStepView anotherRemains(DtrHandoff handoff, String completed, List<String> remaining,
                                               Map<String, List<String>> carry, boolean itemless, List<String> done) {
        if (completed != null && !handoff.counts(completed)) {
            List<String> named = DtrHandoff.answeredBy(completed);
            String says = named.isEmpty() ? "it does not say which questionnaire it answers"
                    : "it says it answers " + named.get(0) + ", which is not a questionnaire of the package this step follows";
            return new NextStepView(NextAction.REVIEW, "Next: check which questionnaire this response answers before going on to PAS.",
                    "This response is complete, but " + says + ". The package holds: " + String.join(", ", handoff.questionnaires())
                            + ". This tool does not count a response towards a questionnaire it does not name, so it does not offer PAS.",
                    carry);
        }
        boolean one = remaining.size() == 1;
        return new NextStepView(NextAction.REVIEW,
                "Next: answer the other " + (one ? "questionnaire" : "questionnaires") + " of this package before going on to PAS: "
                        + String.join(", ", remaining) + ".",
                "This response is complete. The package holds " + handoff.questionnaires().size() + " questionnaires and "
                        + (one ? "one of them has" : remaining.size() + " of them have") + " no completed response yet."
                        + (itemless ? " " + NOT_CARRIED + done(done) : ""), carry);
    }

    /**
     * Why a complete response that holds no item is shown and not handed on. DTR 2.0.1 requires a
     * QuestionnaireResponse to hold at least one item, so PAS is not given one that holds none.
     */
    private static final String NOT_CARRIED = "This response holds no item, and DTR 2.0.1 requires a QuestionnaireResponse to hold at "
            + "least one (dtr-questionnaireresponse). This tool does not hand PAS a response that does not conform to it, so the response "
            + "is shown here and not carried.";

    /**
     * The step to PAS once every questionnaire of the package has a completed response. What goes
     * with it is said from the carry itself, so the headline cannot claim a response that is not
     * handed on.
     *
     * @param itemless true when the response this step completed holds no item and is therefore not carried
     * @param why      why the response is complete
     */
    private static NextStepView toPas(Map<String, List<String>> carry, boolean itemless, List<String> done, String why) {
        List<String> going = carry.getOrDefault(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of());
        String headline;
        if (!itemless) {
            headline = "Next: choose Continue in PAS, then Send PAS request. The completed questionnaire "
                    + (going.size() > 1 ? "responses go" : "response goes") + " with it.";
        } else if (going.isEmpty()) {
            headline = "Next: choose Continue in PAS, then Send PAS request. No questionnaire response goes with it.";
        } else {
            headline = "Next: choose Continue in PAS, then Send PAS request. The completed responses of the other questionnaires go with it, "
                    + "and this one does not.";
        }
        return new NextStepView(NextAction.START_PAS, headline, itemless ? why + " " + NOT_CARRIED + done(done) : why, carry);
    }

    /**
     * The sentence that names the questionnaires a response completed with no item, and says they
     * are done: taken off what this package still lists to answer, so no later step asks for them
     * again. Empty when the response names none of the package's questionnaires.
     */
    private static String done(List<String> done) {
        if (done.isEmpty()) {
            return "";
        }
        boolean one = done.size() == 1;
        return " The questionnaire" + (one ? " " : "s ") + String.join(", ", done) + (one ? " is" : " are") + " done all the same: "
                + (one ? "it is" : "they are") + " taken off what this package still lists to answer, so no later step asks for "
                + (one ? "it" : "them") + " again.";
    }

    /** True when a QuestionnaireResponse, as compact JSON, holds no item. */
    private static boolean itemless(String responseJson) {
        JsonSafe.Parsed parsed = JsonSafe.parse(responseJson);
        return parsed.ok() && JsonSafe.elements(parsed.node(), "item").isEmpty();
    }

    /** True when a static questionnaire's response is held back only by items this tool cannot judge. */
    private static boolean notJudged(DtrDetail detail) {
        return detail.form() != null && !detail.form().undetermined().isEmpty()
                && detail.questionnaires().stream().allMatch(questionnaire -> questionnaire.kind() == QuestionnaireKind.STATIC);
    }

    /**
     * True when the questionnaire of a step completed inside the tool is answered through the
     * adaptive loop: it is marked adaptive, or its response carries the questionnaire inside
     * itself, which is the shape of a response from the loop.
     */
    private static boolean answeredThroughTheLoop(DtrDetail detail) {
        boolean marked = detail.questionnaires().stream().anyMatch(questionnaire -> questionnaire.kind() == QuestionnaireKind.ADAPTIVE);
        boolean contained = detail.questionnaireResponses().stream()
                .anyMatch(response -> response.questionnaire().hasValue() && response.questionnaire().value().startsWith("#"));
        return marked || contained;
    }

    /**
     * The step after a package that loaded. Each kind of questionnaire is answered its own way, so
     * the step is named only when every questionnaire in the package is of one kind. Otherwise the
     * tester chooses, with the kind stated beside each questionnaire.
     */
    private static NextStepView afterPackage(List<QuestionnaireView> questionnaires, Map<String, List<String>> carry) {
        boolean several = questionnaires.size() > 1;
        if (!questionnaires.isEmpty() && questionnaires.stream().allMatch(questionnaire -> questionnaire.kind() == QuestionnaireKind.ADAPTIVE)) {
            return new NextStepView(NextAction.ANSWER_NEXT_QUESTION,
                    (several ? "Next: start each adaptive questionnaire: choose " : "Next: start the adaptive questionnaire: choose ")
                            + button(NextAction.ANSWER_NEXT_QUESTION) + ".",
                    "An adaptive questionnaire is asked one step at a time, so the first next-question call is sent with no answers and the "
                            + "service returns the first question.", carry);
        }
        if (!questionnaires.isEmpty() && questionnaires.stream().allMatch(questionnaire -> questionnaire.kind() == QuestionnaireKind.STATIC)) {
            return new NextStepView(NextAction.SUBMIT_RESPONSE,
                    (several ? "Next: answer each questionnaire, then choose " + button(NextAction.SUBMIT_RESPONSE) + " for each."
                            : "Next: answer the questionnaire, then choose " + button(NextAction.SUBMIT_RESPONSE) + "."),
                    "A static questionnaire carries all its items. Which of them are enabled is worked out each time the response is "
                            + "completed in this tool, without another call to the service.", carry);
        }
        return new NextStepView(NextAction.REVIEW, "Next: read how each questionnaire is meant to be answered, below. This tool cannot choose for you.",
                several ? "The package holds questionnaires of different kinds, or one whose kind this tool cannot tell."
                        : "This tool cannot tell whether the questionnaire is adaptive or static, and it does not pick one.", carry);
    }

    /**
     * The next step when the service answered with a 4xx status. The body is not always at fault:
     * the same outcome covers credentials that were not accepted and an address that does not
     * exist, and sending the tester to correct the request for either would waste their time.
     */
    private static NextStepView notAccepted(VendorExchange exchange, Map<String, List<String>> carry) {
        int status = exchange == null || exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        if (status == 401 || status == 403) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials on the Config tab.",
                    "The service answered HTTP " + status + ", which means it did not accept the credentials this tool sent, or none "
                            + "were sent. The request itself may be fine.", carry);
        }
        if (status == 404) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the DTR url on the Config tab.",
                    "The service answered HTTP 404 and did not say the member or the provider is unknown, so the address is the "
                            + "likely cause.", carry);
        }
        return new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                "The service did not accept the request as it was sent.", carry);
    }

    private static String named(List<QuestionRef> questions) {
        return String.join("; ", questions.stream().map(QuestionRef::named).toList());
    }

    private static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    private static String status(VendorExchange exchange) {
        return exchange == null || exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
    }

    private static String lower(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
