package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.EpaOperation;
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
 * involved. This tool drives the server-side operations behind a provider-facing launch, and a
 * tester must not come away believing they exercised the launch.
 */
public final class DtrVerdicts {

    /** Under every result of a step that was completed inside the tool. */
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
                supporting.add("This is not the same as an answer that no documentation is needed. The service has said nothing either way.");
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
                supporting.add("That is an answer. It is not the same as a package that holds no questionnaire, where the service has "
                        + "said nothing.");
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
        if (outcome.fault() && exchange != null && Texts.present(exchange.failureDetail())) {
            supporting.add(exchange.failureDetail());
        }
        if (form != null) {
            supporting.addAll(formLines(form));
        }
        supporting.add(scope(operation));
        return VerdictView.of(outcome, headline, List.copyOf(new LinkedHashSet<>(supporting)));
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
        if (form.changes() != null) {
            lines.add(form.changes().summary());
        }
        lines.addAll(form.notes());
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
        Map<String, List<String>> carry = handoff.carry(completed);
        boolean local = operation == EpaOperation.DTR_RESPONSE;
        boolean adaptive = !local || answeredThroughTheLoop(detail);

        return switch (outcome) {
            case PACKAGE_LOADED -> afterPackage(detail.questionnaires(), carry);
            case PACKAGE_INCOMPLETE -> new NextStepView(NextAction.REVIEW, "Next: read which questionnaires were not returned, below.",
                    "The request asked for questionnaires by canonical url and the package does not hold all of them. The ones it does "
                            + "hold can still be answered.", carry);
            case NO_QUESTIONNAIRE -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    "The service returned no questionnaire. That is not an answer that no documentation is needed.", carry);
            case MALFORMED_QUESTIONNAIRE -> new NextStepView(NextAction.REVIEW,
                    "Next: read the problems listed with this result and report them as a defect in the service.",
                    "What the service sent cannot be used as a questionnaire, so there is nothing to answer.", carry);
            case NEXT_QUESTION -> new NextStepView(NextAction.ANSWER_NEXT_QUESTION,
                    "Next: answer what is now asked and send next-question again.",
                    "The response is in progress and the service is waiting for an answer.", carry);
            case NO_QUESTION_RETURNED, UNRECOGNISED_STATUS -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    outcome == DtrOutcome.NO_QUESTION_RETURNED
                            ? "The service asked nothing more and did not report the response as complete, so the loop can neither continue "
                            + "nor end."
                            : "The status of the response is not one this tool acts on, so it cannot tell whether the loop continues.", carry);
            case RESPONSE_COMPLETED -> new NextStepView(NextAction.START_PAS,
                    "Next: submit the prior authorization request in PAS. The completed questionnaire response goes with it.",
                    adaptive ? "The service reported the response as completed, and every required question that is enabled has an answer."
                            : "Every required question that is enabled has an answer.", carry);
            case NOTHING_REQUIRED -> new NextStepView(NextAction.START_PAS,
                    "Next: submit the prior authorization request in PAS. The completed questionnaire response goes with it.",
                    "The response is complete without a question having been asked, so there is no documentation to collect first.", carry);
            case RESPONSE_NOT_COMPLETE -> adaptive
                    ? new NextStepView(NextAction.ANSWER_NEXT_QUESTION, "Next: continue the adaptive questionnaire with next-question.",
                    "Only the service can complete an adaptive response, and it has not reported this one as completed.", carry)
                    : new NextStepView(NextAction.REVIEW, "Next: read how this questionnaire is meant to be answered, below.",
                    "This tool cannot tell whether the questionnaire is adaptive or static, so it cannot say its response is complete.", carry);
            case REQUIRED_UNANSWERED -> adaptive
                    ? new NextStepView(NextAction.ANSWER_NEXT_QUESTION,
                    "Next: answer the required questions named with this result and send next-question again.",
                    "A required question that is enabled has no answer, so the response is not complete, whatever status it carries.", carry)
                    : new NextStepView(NextAction.SUBMIT_RESPONSE,
                    "Next: answer the required questions named with this result and submit the response again.",
                    "A required question that is enabled has no answer, so the response is not complete.", carry);
            case MEMBER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the member ID against the test data.",
                    "The service does not recognise the member. The environment answered normally.", carry);
            case PROVIDER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the NPI against the test data.",
                    "The service does not recognise the provider. The environment answered normally.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> new NextStepView(NextAction.CHECK_ENVIRONMENT,
                    "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the member or the questionnaire.", carry);
        };
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
                    several ? "Next: start each adaptive questionnaire with next-question." : "Next: start the adaptive questionnaire with next-question.",
                    "An adaptive questionnaire is asked one step at a time, so the first next-question call is sent with no answers and the "
                            + "service returns the first question.", carry);
        }
        if (!questionnaires.isEmpty() && questionnaires.stream().allMatch(questionnaire -> questionnaire.kind() == QuestionnaireKind.STATIC)) {
            return new NextStepView(NextAction.SUBMIT_RESPONSE,
                    several ? "Next: answer each questionnaire and submit its response." : "Next: answer the questionnaire and submit the response.",
                    "A static questionnaire carries all its items. This tool shows and hides them as the answers change, and puts the "
                            + "response together without another call to the service.", carry);
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
