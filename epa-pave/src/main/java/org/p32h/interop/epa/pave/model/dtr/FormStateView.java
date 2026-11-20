package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Where a questionnaire stands with the answers given so far. Every step of the adaptive loop and
 * every response put together in the tool reports this, so the question count, the answer set and
 * the completion status are in front of the tester at each step.
 *
 * @param questionCount        how many questions the questionnaire holds so far
 * @param enabledQuestionCount how many of them the answers given enable. A question whose
 *                             condition could not be evaluated is counted, since it is still shown
 * @param answeredCount        how many enabled questions have at least one answer
 * @param status               the status of the QuestionnaireResponse: the completion status
 * @param complete             true only when the status says completed and every required question
 *                             that is enabled has an answer
 * @param asked                the questions now waiting for an answer
 * @param answers              the current answer set
 * @param requiredUnanswered   each required question that is enabled and has no answer
 * @param undetermined         each item with no answer that may be required and that this tool
 *                             cannot judge, because a rule it does not apply decides whether it is
 *                             shown, required or answered. Never among requiredUnanswered
 * @param changes              what changed since the previous step. Null outside the adaptive loop
 * @param notes                anything else a tester should know about how the state was worked out
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormStateView(int questionCount, int enabledQuestionCount, int answeredCount, DtrCode status, boolean complete,
                            List<QuestionRef> asked, List<AnswerView> answers, List<QuestionRef> requiredUnanswered,
                            List<QuestionRef> undetermined, ChangesView changes, List<String> notes) {

    public FormStateView {
        asked = asked == null ? List.of() : List.copyOf(asked);
        answers = answers == null ? List.of() : List.copyOf(answers);
        requiredUnanswered = requiredUnanswered == null ? List.of() : List.copyOf(requiredUnanswered);
        undetermined = undetermined == null ? List.of() : List.copyOf(undetermined);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public FormStateView withChanges(ChangesView newChanges) {
        return new FormStateView(questionCount, enabledQuestionCount, answeredCount, status, complete, asked, answers,
                requiredUnanswered, undetermined, newChanges, notes);
    }

    /** Prints the counts only. The answer set can be clinical. */
    @Override
    public String toString() {
        return "FormStateView[questions=" + questionCount + ", answered=" + answeredCount + ", complete=" + complete + "]";
    }
}
