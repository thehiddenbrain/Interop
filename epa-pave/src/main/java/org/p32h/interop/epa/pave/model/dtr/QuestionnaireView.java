package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One questionnaire as the service returned it.
 *
 * @param position      its place among the questionnaires of the result, counting from one
 * @param foundAt       where in the response it sat
 * @param subject       how it is named everywhere on the result: its title, else its url, else its position
 * @param canonical     its url, with its version after a bar when it states one. Null when it has no url
 * @param narrative     the narrative it carries in {@code text.div}, which DTR 2.0.1 requires
 * @param narrativeStatus the status of that narrative, {@code text.status}
 * @param subjectType   the kinds of subject it is about, which DTR 2.0.1 fixes to Patient
 * @param profiles      the profiles it declares in {@code meta.profile}
 * @param kind          adaptive, static or undetermined
 * @param kindBasis     one plain sentence saying what on the questionnaire decided the kind
 * @param questionCount how many of its items take an answer
 * @param requiredCount how many of those are marked required
 * @param contained     the resources it carries inside itself, such as a value set its questions use
 * @param problems      what makes it unusable as a questionnaire, one plain sentence each. Empty when it is usable
 * @param notApplied    each rule the questionnaire carries that this tool does not apply, one plain
 *                      sentence each saying what it does and what not applying it means. Empty
 *                      when it carries none. This tool evaluates enableWhen and nothing else
 * @param answersBasis  one plain sentence saying which answers the enableWhen conditions were evaluated against
 * @param arrivedWith   where in the response the QuestionnaireResponse sat that the package carries
 *                      for this questionnaire, which is that response's {@code foundAt}. Null when
 *                      the package carries none for it. Answering starts from that response: for an
 *                      adaptive questionnaire it is what the first next-question step sends
 * @param json          the resource exactly as returned, as compact JSON. The next step posts it back,
 *                      because the tool keeps no session between steps
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionnaireView(
        int position,
        String foundAt,
        String subject,
        Shown id,
        Shown url,
        Shown version,
        String canonical,
        Shown name,
        Shown title,
        DtrCode status,
        Shown date,
        Shown publisher,
        Shown description,
        Shown narrative,
        Shown narrativeStatus,
        List<Shown> subjectType,
        List<Shown> profiles,
        List<Shown> derivedFrom,
        QuestionnaireKind kind,
        String kindBasis,
        int questionCount,
        int requiredCount,
        List<ItemView> items,
        List<ExtensionView> extensions,
        List<PackageResourceView> contained,
        List<String> problems,
        List<String> notApplied,
        String answersBasis,
        String arrivedWith,
        String json) {

    public QuestionnaireView {
        subjectType = subjectType == null ? List.of() : List.copyOf(subjectType);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
        items = items == null ? List.of() : List.copyOf(items);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        contained = contained == null ? List.of() : List.copyOf(contained);
        problems = problems == null ? List.of() : List.copyOf(problems);
        notApplied = notApplied == null ? List.of() : List.copyOf(notApplied);
    }

    /** True when nothing stops the questionnaire from being answered. */
    public boolean usable() {
        return problems.isEmpty();
    }

    /** Prints where it sat and its kind. The resource itself can hold pre-populated answers about a member. */
    @Override
    public String toString() {
        return "QuestionnaireView[" + foundAt + ", " + kind + "]";
    }
}
