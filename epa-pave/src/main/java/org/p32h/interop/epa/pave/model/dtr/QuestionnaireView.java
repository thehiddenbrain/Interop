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
 * @param profiles      the profiles it declares in {@code meta.profile}
 * @param kind          adaptive, static or undetermined
 * @param kindBasis     one plain sentence saying what on the questionnaire decided the kind
 * @param questionCount how many of its items take an answer
 * @param requiredCount how many of those are marked required
 * @param contained     the resources it carries inside itself, such as a value set its questions use
 * @param problems      what makes it unusable as a questionnaire, one plain sentence each. Empty when it is usable
 * @param answersBasis  one plain sentence saying which answers the enableWhen conditions were evaluated against
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
        String answersBasis,
        String json) {

    public QuestionnaireView {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
        items = items == null ? List.of() : List.copyOf(items);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        contained = contained == null ? List.of() : List.copyOf(contained);
        problems = problems == null ? List.of() : List.copyOf(problems);
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
