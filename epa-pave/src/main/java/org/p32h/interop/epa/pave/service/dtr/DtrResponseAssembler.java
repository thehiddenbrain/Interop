package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Progress;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Form;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Puts a QuestionnaireResponse together from what a response step was posted, and says where it
 * stands. No call is made: this is the one DTR step that happens entirely inside the tool.
 *
 * <p>Who can call a response complete depends on how its questionnaire is answered:
 * <ul>
 *   <li>a static questionnaire carries all its items, so this tool can see every required question
 *       and builds the response itself, completed only when each one that is enabled has an answer</li>
 *   <li>an adaptive questionnaire is asked one step at a time, and only the service knows whether
 *       more questions follow. Its response is the one the service last returned, used unchanged,
 *       and it is complete only when the service gave it a completion status</li>
 *   <li>a questionnaire whose kind cannot be told is not called complete by anyone</li>
 * </ul>
 */
final class DtrResponseAssembler {

    private DtrResponseAssembler() {
    }

    /**
     * @param response   the QuestionnaireResponse as it stands after this step
     * @param answeredAs how the questionnaire was taken to be answered
     */
    record Assembled(DtrReading reading, JsonNode response, QuestionnaireKind answeredAs, List<DtrProfileCheck.Gap> gaps) {

        Assembled {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        /** Redacted, because the response holds the tester's answers, which can be clinical. */
        @Override
        public String toString() {
            return "Assembled[" + answeredAs + "]";
        }
    }

    /** How the questionnaire a step was posted is answered: as its kind says, or as adaptive when its response came from the loop. */
    static QuestionnaireKind answeredAs(DtrAnswers.Posted posted) {
        return OnyxDtrConventions.fromTheLoop(posted.response()) ? QuestionnaireKind.ADAPTIVE
                : OnyxDtrConventions.kind(posted.questionnaire()).kind();
    }

    /** What stops the posted answers from being used for this questionnaire, beyond what reading them found. */
    static List<FieldError> problems(DtrAnswers.Posted posted) {
        List<FieldError> errors = new ArrayList<>();
        if (posted.questionnaire() != null && answeredAs(posted) == QuestionnaireKind.ADAPTIVE && !posted.given().isEmpty()) {
            errors.add(new FieldError("answers", "This questionnaire is adaptive, so its response is completed by the service and not by "
                    + "this tool. Send these answers through next-question, then post the response it returns here."));
        }
        return errors;
    }

    /**
     * @param posted what the request carried, already checked and free of errors
     */
    static Assembled assemble(DtrAnswers.Posted posted, DtrRequestBuilder.Filler filler, Clock clock, DtrRequestBuilder builder,
                              DtrResponseMapper mapper) {
        JsonNode questionnaire = posted.questionnaire();
        QuestionnaireKind kind = OnyxDtrConventions.kind(questionnaire).kind();
        QuestionnaireKind answeredAs = answeredAs(posted);
        List<String> notes = new ArrayList<>();
        if (answeredAs == QuestionnaireKind.ADAPTIVE) {
            JsonNode response = posted.response();
            boolean builtHere = response == null;
            if (builtHere) {
                response = builder.buildNextQuestion(posted, filler, clock, null).response();
                notes.add("No response from the adaptive loop was given, so there is nothing the service has completed. The response shown "
                        + "is the empty one a first next-question step would send.");
            } else {
                notes.add("This is the response the service last returned. This tool did not change it.");
                if (kind != QuestionnaireKind.ADAPTIVE) {
                    notes.add("The response carries its questionnaire inside itself, which is the shape of a response from the adaptive "
                            + "loop, so only the service can say it is complete.");
                }
            }
            Form evaluated = EnableWhenEvaluator.evaluate(questionnaire, QuestionnaireJson.answers(response));
            Progress progress = OnyxDtrConventions.progress(JsonSafe.presentText(response, "status"));
            List<DtrProfileCheck.Gap> gaps = DtrProfileCheck.response(response, filler, builtHere, false);
            notes.addAll(0, DtrProfileCheck.notes(gaps, false));
            return new Assembled(mapper.describeResponse(questionnaire, response, evaluated, QuestionnaireKind.ADAPTIVE, progress, builtHere,
                    notes), response, QuestionnaireKind.ADAPTIVE, gaps);
        }
        Form evaluated = EnableWhenEvaluator.evaluate(questionnaire, posted.merged());
        // An item a rule this tool does not apply decides may still need an answer, so the tool does not complete a response that has one.
        boolean complete = answeredAs == QuestionnaireKind.STATIC && evaluated.requiredUnanswered().isEmpty() && evaluated.undetermined().isEmpty();
        JsonNode response = builder.buildResponse(posted, evaluated, complete, filler, clock, notes);
        if (answeredAs == QuestionnaireKind.UNDETERMINED) {
            notes.add("This tool cannot tell whether this questionnaire is adaptive or static, so it does not call its response complete.");
        }
        List<DtrProfileCheck.Gap> gaps = DtrProfileCheck.response(response, filler, posted.response() == null, false);
        notes.addAll(0, DtrProfileCheck.notes(gaps, false));
        return new Assembled(mapper.describeResponse(questionnaire, response, evaluated, answeredAs,
                complete ? Progress.COMPLETED : Progress.IN_PROGRESS, true, notes), response, answeredAs, gaps);
    }
}
