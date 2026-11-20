package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Progress;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import java.util.List;

/**
 * Decides the outcome of a DTR step from coded values, the structure of what came back and
 * transport facts. The wording of a question, an answer or an issue is never read here.
 *
 * <p>The rules that matter most:
 * <ul>
 *   <li>a response is complete only when its status says completed <b>and</b> every required
 *       question that is enabled has an answer. A status of completed never outranks a required
 *       question left unanswered</li>
 *   <li>an item that a rule this tool does not apply decides is never counted as a required
 *       question left unanswered. When the service completed the response, that is the service's
 *       judgement and stands. When this tool would complete it, it does not: it cannot tell</li>
 *   <li>"the service asked for nothing" needs a response that came back completed with no question
 *       in it. A package that simply holds no questionnaire is never read that way</li>
 *   <li>a questionnaire nobody can tie an answer to makes the whole result unusable, even when
 *       another questionnaire beside it is sound, because the outcome must not pass on the sound one</li>
 * </ul>
 */
public final class DtrOutcomeRules {

    private DtrOutcomeRules() {
    }

    /**
     * The outcome a call establishes before its content is looked at: a transport failure, a
     * not-found signal or an HTTP error. Null when the call answered with a success status and a
     * body that was read, so the content decides.
     */
    static DtrOutcome beforeContent(VendorExchange exchange) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.TIMEOUT) {
            return DtrOutcome.TIMEOUT;
        }
        if (failure == OnyxFailureType.UNAVAILABLE) {
            return DtrOutcome.UNREACHABLE;
        }
        if (failure == OnyxFailureType.MALFORMED_RESPONSE) {
            return DtrOutcome.MALFORMED_RESPONSE;
        }
        // Not found is a finding about the test data, so it is told apart from a fault even when it
        // arrives with an error status.
        if (exchange.has(VendorSignal.MEMBER_NOT_FOUND)) {
            return DtrOutcome.MEMBER_NOT_FOUND;
        }
        if (exchange.has(VendorSignal.PROVIDER_NOT_FOUND)) {
            return DtrOutcome.PROVIDER_NOT_FOUND;
        }
        if (failure == OnyxFailureType.SERVER_ERROR) {
            return DtrOutcome.SERVICE_ERROR;
        }
        if (failure == OnyxFailureType.BAD_REQUEST) {
            return DtrOutcome.REJECTED;
        }
        return null;
    }

    /**
     * The outcome of a package fetch.
     *
     * @param kind           what kind of body came back
     * @param questionnaires every questionnaire found in it
     * @param notReturned    the questionnaires that were asked for by canonical url and are not in it
     */
    public static DtrOutcome forPackage(VendorExchange exchange, DtrResponseKind kind, List<QuestionnaireView> questionnaires,
                                        List<String> notReturned) {
        DtrOutcome early = beforeContent(exchange);
        if (early != null) {
            return early;
        }
        if (questionnaires.stream().anyMatch(questionnaire -> !questionnaire.usable())) {
            return DtrOutcome.MALFORMED_QUESTIONNAIRE;
        }
        if (questionnaires.isEmpty()) {
            // A body in one of the shapes a package arrives in, with no questionnaire in it, is an
            // empty package and has its own outcome. A body in no such shape cannot be used at all.
            boolean packageShape = kind == DtrResponseKind.PARAMETERS || kind == DtrResponseKind.BUNDLE
                    || kind == DtrResponseKind.OPERATION_OUTCOME;
            return packageShape ? DtrOutcome.NO_QUESTIONNAIRE : DtrOutcome.MALFORMED_QUESTIONNAIRE;
        }
        return notReturned.isEmpty() ? DtrOutcome.PACKAGE_LOADED : DtrOutcome.PACKAGE_INCOMPLETE;
    }

    /**
     * The outcome of one step of the adaptive loop.
     *
     * @param responseFound true when the body held a QuestionnaireResponse
     * @param questionnaire the questionnaire that response carries inside itself, or null when it carries none
     * @param progress      where the status of the response puts it in the loop
     * @param form          the questionnaire as it stands with the answers in the response. Null
     *                      when there is no questionnaire to stand anywhere
     */
    public static DtrOutcome forNextQuestion(VendorExchange exchange, DtrResponseKind kind, boolean responseFound,
                                             QuestionnaireView questionnaire, Progress progress, FormStateView form) {
        DtrOutcome early = beforeContent(exchange);
        if (early != null) {
            return early;
        }
        if (!responseFound) {
            // An OperationOutcome with a success status is the service answering without a question.
            return kind == DtrResponseKind.OPERATION_OUTCOME ? DtrOutcome.NO_QUESTION_RETURNED : DtrOutcome.MALFORMED_QUESTIONNAIRE;
        }
        if (questionnaire == null || !questionnaire.usable() || form == null) {
            return DtrOutcome.MALFORMED_QUESTIONNAIRE;
        }
        return switch (progress) {
            case COMPLETED -> completed(form, true);
            case IN_PROGRESS -> form.asked().isEmpty() ? DtrOutcome.NO_QUESTION_RETURNED : DtrOutcome.NEXT_QUESTION;
            case NOT_UNDERSTOOD -> DtrOutcome.UNRECOGNISED_STATUS;
        };
    }

    /**
     * The outcome of a response put together inside the tool.
     *
     * @param kind     the kind of the questionnaire being answered
     * @param progress for an adaptive questionnaire, where the status the service last returned
     *                 puts the response. Not consulted for a static one, whose status this tool decides
     */
    public static DtrOutcome forResponse(QuestionnaireKind kind, Progress progress, FormStateView form) {
        return switch (kind) {
            // Only the service can say an adaptive response is finished, since only it knows whether more questions follow.
            case ADAPTIVE -> progress == Progress.COMPLETED ? completed(form, true) : DtrOutcome.RESPONSE_NOT_COMPLETE;
            case STATIC -> completed(form, false);
            // With nothing to say how the questionnaire is answered, nothing can say its response is finished.
            case UNDETERMINED -> DtrOutcome.RESPONSE_NOT_COMPLETE;
        };
    }

    /** @param byTheService true when the service gave the response its completion status, false when this tool would */
    private static DtrOutcome completed(FormStateView form, boolean byTheService) {
        if (!form.requiredUnanswered().isEmpty()) {
            return DtrOutcome.REQUIRED_UNANSWERED;
        }
        if (!byTheService && !form.undetermined().isEmpty()) {
            return DtrOutcome.RESPONSE_NOT_COMPLETE;
        }
        return form.questionCount() == 0 ? DtrOutcome.NOTHING_REQUIRED : DtrOutcome.RESPONSE_COMPLETED;
    }
}
