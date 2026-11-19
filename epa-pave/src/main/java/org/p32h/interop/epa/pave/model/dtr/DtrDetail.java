package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.IssueView;
import java.util.List;

/**
 * Everything the tool understood in one DTR step, for the designed view.
 *
 * <p>The three steps share this one shape and fill different parts of it. A package fetch fills
 * the questionnaires, the resources that came with them and any response the package carried. A
 * step of the adaptive loop fills the one questionnaire as it now stands, the response in full and
 * the form state with what changed. A response put together in the tool fills the same, and says
 * that nothing was sent.
 *
 * @param responseKind           what kind of thing came back
 * @param responseNote           one plain sentence about it, or null when it needs none
 * @param kindRule               the rule by which a questionnaire is called adaptive or static, in
 *                               plain words. Null when the result holds no questionnaire
 * @param requested              the questionnaires the request asked for by canonical url. Empty
 *                               when it named none
 * @param notReturned            those of them the response does not hold
 * @param bundles                each bundle the package is made of. Empty when it arrived as bare resources
 * @param questionnaires         every questionnaire in the response. Nothing is reduced to the first
 * @param resources              the libraries, value sets and other resources that came with them
 * @param questionnaireResponses every QuestionnaireResponse in the response, in full
 * @param form                   where the questionnaire stands with the answers so far. Null for a package fetch
 * @param issues                 the issues of an OperationOutcome, when one came back
 * @param requestNotes           what the tool left out of, or worked out for, the request it built
 * @param bodyBuiltByTool        false when the tester's edited body was sent in place of a built one
 * @param sent                   false when the step made no call to the service at all
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DtrDetail(
        DtrResponseKind responseKind,
        String responseNote,
        String kindRule,
        List<String> requested,
        List<String> notReturned,
        List<PackageBundleView> bundles,
        List<QuestionnaireView> questionnaires,
        List<PackageResourceView> resources,
        List<QuestionnaireResponseView> questionnaireResponses,
        FormStateView form,
        List<IssueView> issues,
        List<String> requestNotes,
        boolean bodyBuiltByTool,
        boolean sent) {

    public DtrDetail {
        requested = requested == null ? List.of() : List.copyOf(requested);
        notReturned = notReturned == null ? List.of() : List.copyOf(notReturned);
        bundles = bundles == null ? List.of() : List.copyOf(bundles);
        questionnaires = questionnaires == null ? List.of() : List.copyOf(questionnaires);
        resources = resources == null ? List.of() : List.copyOf(resources);
        questionnaireResponses = questionnaireResponses == null ? List.of() : List.copyOf(questionnaireResponses);
        issues = issues == null ? List.of() : List.copyOf(issues);
        requestNotes = requestNotes == null ? List.of() : List.copyOf(requestNotes);
    }

    /** The same detail with what is known about the request that produced it. */
    public DtrDetail withRequest(List<String> notes, boolean builtByTool) {
        return new DtrDetail(responseKind, responseNote, kindRule, requested, notReturned, bundles, questionnaires, resources,
                questionnaireResponses, form, issues, notes, builtByTool, sent);
    }

    /** Prints what kind of response it describes and nothing of it: it holds answers and whole resources. */
    @Override
    public String toString() {
        return "DtrDetail[" + responseKind + ", questionnaires=" + questionnaires.size() + "]";
    }
}
