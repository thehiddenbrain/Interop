package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the mapper made of one DTR step.
 *
 * @param additionalFields everything the response carried that the designed view did not use
 */
public record DtrReading(DtrDetail detail, DtrOutcome outcome, List<AdditionalField> additionalFields) {

    public DtrReading {
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /**
     * What was asked about, for the expectation check: each questionnaire a package fetch asked
     * for by canonical url. The check works out for itself which of these went unanswered, so a
     * questionnaire that was asked for and not returned can never satisfy a code assertion.
     */
    public List<String> asked() {
        return detail.requested();
    }

    /**
     * The answers a declared expectation is compared with. A package gives one for each
     * questionnaire it holds, so the codes of two questionnaires are never pooled. A step of the
     * loop, or a response put together in the tool, gives one for the form as it stands.
     */
    public List<Expectations.Answer> answers() {
        List<Expectations.Answer> out = new ArrayList<>();
        if (detail.form() != null) {
            out.add(Expectations.Answer.of(null, codes(detail.form())));
            return out;
        }
        for (QuestionnaireView questionnaire : detail.questionnaires()) {
            out.add(Expectations.Answer.of(subject(questionnaire), codes(questionnaire, detail.questionnaires().size())));
        }
        return out;
    }

    /**
     * How a questionnaire is named to the expectation check: by the canonical it was asked for
     * with, so that the check can see it was answered, else by its own url, else by its position.
     */
    public String subject(QuestionnaireView questionnaire) {
        for (String requested : detail.requested()) {
            if (namedBy(requested, questionnaire)) {
                return requested;
            }
        }
        return questionnaire.url().hasValue() ? questionnaire.url().value() : place(questionnaire);
    }

    /** True when a canonical that was asked for names this questionnaire: by its url alone, or by its url and version. */
    public static boolean namedBy(String requested, QuestionnaireView questionnaire) {
        if (requested == null || !questionnaire.url().hasValue()) {
            return false;
        }
        String wanted = requested.strip();
        return wanted.equals(questionnaire.url().value()) || wanted.equals(questionnaire.canonical());
    }

    /** A questionnaire named by where it stands among those returned. This is the tool's own label, never text from the response. */
    public static String place(QuestionnaireView questionnaire) {
        return "questionnaire[" + questionnaire.position() + "]";
    }

    /** The facts one questionnaire offers to an expectation and to the ledger. */
    public static Map<String, List<String>> codes(QuestionnaireView questionnaire, int howMany) {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        codes.put(DtrCodes.QUESTIONNAIRES, List.of(String.valueOf(howMany)));
        codes.put(DtrCodes.QUESTIONNAIRE_KIND, List.of(questionnaire.kind().code()));
        if (questionnaire.status().code() != null) {
            codes.put(DtrCodes.QUESTIONNAIRE_STATUS, List.of(questionnaire.status().code()));
        }
        codes.put(DtrCodes.QUESTIONS, List.of(String.valueOf(questionnaire.questionCount())));
        return codes;
    }

    /** The facts a form state offers to an expectation and to the ledger. */
    public static Map<String, List<String>> codes(FormStateView form) {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        if (form.status() != null && form.status().code() != null) {
            codes.put(DtrCodes.RESPONSE_STATUS, List.of(form.status().code()));
        }
        codes.put(DtrCodes.QUESTIONS, List.of(String.valueOf(form.questionCount())));
        codes.put(DtrCodes.ANSWERED, List.of(String.valueOf(form.answeredCount())));
        codes.put(DtrCodes.REQUIRED_UNANSWERED, List.of(String.valueOf(form.requiredUnanswered().size())));
        return codes;
    }

    /**
     * The codes for the ledger, as {@code name=code} pairs. They are counts and coded values only:
     * the kind, the statuses, how many questions and how many answers. A question, an answer and a
     * linkId are never written, because a linkId is text the payer chose and an answer can be
     * clinical. When a package holds several questionnaires each pair starts with the
     * questionnaire's position, so two questionnaires swapping kinds does not write the same line.
     */
    public List<String> ledgerCodes() {
        List<String> out = new ArrayList<>();
        if (detail.form() != null) {
            codes(detail.form()).forEach((name, values) -> values.forEach(value -> out.add(name + "=" + value)));
            return out;
        }
        List<QuestionnaireView> questionnaires = detail.questionnaires();
        if (questionnaires.isEmpty()) {
            return out;
        }
        out.add(DtrCodes.QUESTIONNAIRES + "=" + questionnaires.size());
        boolean named = questionnaires.size() > 1;
        for (QuestionnaireView questionnaire : questionnaires) {
            String prefix = named ? place(questionnaire) + ": " : "";
            codes(questionnaire, questionnaires.size()).forEach((name, values) -> {
                if (!DtrCodes.QUESTIONNAIRES.equals(name)) {
                    values.forEach(value -> out.add(prefix + name + "=" + value));
                }
            });
        }
        return out;
    }

    /** The canonical url of each questionnaire in the result that has one, in the order returned. */
    public List<String> canonicals() {
        List<String> out = new ArrayList<>();
        for (QuestionnaireView questionnaire : detail.questionnaires()) {
            if (questionnaire.canonical() != null && !out.contains(questionnaire.canonical())) {
                out.add(questionnaire.canonical());
            }
        }
        return out;
    }
}
