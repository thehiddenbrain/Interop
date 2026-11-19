package org.p32h.interop.epa.pave.client.dtr;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * ASSUMPTIONS ABOUT THE VENDOR, ALL IN ONE FILE.
 *
 * <p>The Da Vinci DTR guide and the SDC guide it builds on could not be fetched while this was
 * written, and nothing here has been observed from the live service. Every rule below therefore
 * comes from the builder's knowledge of those guides, or is an assumption about what the vendor
 * does where the guides leave it open. Each rule reads a coded value or the structure of a
 * resource, never wording, and each is deliberately narrow: when a response does not match a
 * rule, nothing is inferred and the response is shown in full.
 *
 * <p>When the real behaviour is known, this is the file to change, together with the mock scenario
 * files that say they model these cases.
 */
public final class OnyxDtrConventions {

    /** Both DTR operations are FHIR operations and speak FHIR JSON. */
    public static final String FHIR_JSON = "application/fhir+json";

    /**
     * ASSUMPTION: the input parameter names of {@code $questionnaire-package}, as the builder
     * recalls them from the DTR guide. The coverage and each order travel as resources, anything
     * an order refers to travels as {@code referenced}, a questionnaire is named by its canonical
     * url, and the coverage assertion id CRD returned travels as {@code context}.
     */
    public static final String PARAM_COVERAGE = "coverage";
    public static final String PARAM_ORDER = "order";
    public static final String PARAM_REFERENCED = "referenced";
    public static final String PARAM_QUESTIONNAIRE = "questionnaire";
    public static final String PARAM_CONTEXT = "context";

    /**
     * ASSUMPTION: the adaptive loop is finished when the QuestionnaireResponse comes back with this
     * status, and continues while it comes back with {@link #STATUS_IN_PROGRESS}. What the live
     * service names its completion status is not confirmed. Any other status, including the other
     * codes FHIR defines, is reported as one this tool cannot act on.
     */
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_IN_PROGRESS = "in-progress";

    /**
     * ASSUMPTION: the id given to the questionnaire a first QuestionnaireResponse carries inside
     * itself, when the questionnaire arrived with no id of its own. A contained resource is
     * referred to by its id, so it has to have one.
     */
    public static final String CONTAINED_QUESTIONNAIRE_ID = "adaptive-questionnaire";

    /**
     * The rule below in plain words, shown on every result that holds a questionnaire, so a tester
     * can see why a questionnaire was called what it was called.
     */
    public static final String KIND_RULE = "A questionnaire is adaptive when it carries the questionnaireAdaptive extension set to "
            + "true or to a url, or declares an adaptive questionnaire profile. It is static when a marker says it is not adaptive, or "
            + "when it carries no marker and holds items of its own. Anything else is undetermined, and this tool does not pick one.";

    private static final String ADAPTIVE_EXTENSION = "questionnaireadaptive";
    private static final String ADAPTIVE_PROFILE = "questionnaire-adapt";
    private static final String STATIC_PROFILE = "dtr-std-questionnaire";

    private static final List<String> MEMBER_WORDS = List.of("patient", "member", "coverage", "subscriber", "beneficiary");
    private static final List<String> PROVIDER_WORDS = List.of("practitioner", "provider", "organization", "requester",
            "performer", "npi");

    private OnyxDtrConventions() {
    }

    /** Where a returned QuestionnaireResponse stands in the adaptive loop. */
    public enum Progress {
        IN_PROGRESS,
        COMPLETED,
        /** Absent, null, empty, or a code other than the two this tool acts on. */
        NOT_UNDERSTOOD
    }

    /** Reads a QuestionnaireResponse status. Matched exactly: a code that differs only by case is not the standard code. */
    public static Progress progress(String status) {
        if (STATUS_COMPLETED.equals(status)) {
            return Progress.COMPLETED;
        }
        if (STATUS_IN_PROGRESS.equals(status)) {
            return Progress.IN_PROGRESS;
        }
        return Progress.NOT_UNDERSTOOD;
    }

    /**
     * ASSUMPTION: {@code next-question} takes the QuestionnaireResponse itself as its request body,
     * which FHIR allows for an operation whose one input is a resource. A service may instead
     * expect it wrapped in a Parameters resource under {@code questionnaire-response}. Which of the
     * two the live service takes is not confirmed. A tester who needs the other shape sends the
     * vendor's sample body from the collection. Either shape is read when it comes back.
     *
     * @param questionnaireResponse the QuestionnaireResponse so far
     * @return the body to send
     */
    public static JsonNode nextQuestionBody(JsonNode questionnaireResponse) {
        return questionnaireResponse;
    }

    /**
     * ASSUMPTION: a QuestionnaireResponse that carries its questionnaire inside itself is one from
     * the adaptive loop, whatever the questionnaire inside it is marked as. That is the shape the
     * SDC guide gives an adaptive response, and a response to a conventional form names its
     * questionnaire by canonical url. It matters because only the service can say that a response
     * from the loop is finished: this tool must not call one complete because the questions asked
     * so far happen to be answered.
     */
    public static boolean fromTheLoop(JsonNode questionnaireResponse) {
        return questionnaireResponse != null && QuestionnaireJson.containedQuestionnaire(questionnaireResponse) != null;
    }

    /**
     * What kind a questionnaire is, and what on it decided that.
     *
     * @param basis one plain sentence, shown beside the kind
     */
    public record Kind(QuestionnaireKind kind, String basis) {
    }

    /**
     * ASSUMPTION: an adaptive questionnaire is marked the way the SDC guide marks one, by the
     * {@code questionnaireAdaptive} extension holding true or the url of the next-question
     * endpoint, or by declaring an adaptive questionnaire profile in {@code meta.profile}. A
     * questionnaire with no such marker that holds items is a conventional form, which is what the
     * published profile says an unmarked questionnaire is. How the live service marks an adaptive
     * questionnaire has not been observed.
     *
     * <p>When the markers contradict each other, or there is no marker and no item, nothing settles
     * the kind and none is picked.
     */
    public static Kind kind(JsonNode questionnaire) {
        List<String> saysAdaptive = new ArrayList<>();
        List<String> saysStatic = new ArrayList<>();
        boolean unreadableMarker = false;
        for (JsonNode extension : JsonSafe.elements(questionnaire, "extension")) {
            String url = JsonSafe.presentText(extension, "url");
            if (url == null || !normalise(url).endsWith(ADAPTIVE_EXTENSION)) {
                continue;
            }
            Boolean flag = JsonSafe.child(extension, "valueBoolean").isBoolean() ? JsonSafe.bool(extension, "valueBoolean") : null;
            String endpoint = Texts.firstPresent(JsonSafe.presentText(extension, "valueUrl"), JsonSafe.presentText(extension, "valueUri"));
            if (Boolean.TRUE.equals(flag)) {
                saysAdaptive.add("it carries the questionnaireAdaptive extension set to true");
            } else if (Boolean.FALSE.equals(flag)) {
                saysStatic.add("its questionnaireAdaptive extension is set to false");
            } else if (endpoint != null) {
                saysAdaptive.add("it carries the questionnaireAdaptive extension set to a url");
            } else {
                unreadableMarker = true;
            }
        }
        for (JsonNode profile : JsonSafe.elements(JsonSafe.child(questionnaire, "meta"), "profile")) {
            String canonical = JsonSafe.presentText(profile);
            if (canonical == null) {
                continue;
            }
            String lower = canonical.toLowerCase(Locale.ROOT);
            if (lower.contains(ADAPTIVE_PROFILE)) {
                saysAdaptive.add("it declares the adaptive questionnaire profile " + canonical);
            } else if (lower.contains(STATIC_PROFILE)) {
                saysStatic.add("it declares the standard questionnaire profile " + canonical);
            }
        }
        int items = QuestionnaireJson.items(questionnaire).size();
        if (!saysAdaptive.isEmpty() && !saysStatic.isEmpty()) {
            return new Kind(QuestionnaireKind.UNDETERMINED, "Undetermined, because its markers contradict each other: "
                    + saysAdaptive.get(0) + ", and " + saysStatic.get(0) + ".");
        }
        if (!saysAdaptive.isEmpty()) {
            return new Kind(QuestionnaireKind.ADAPTIVE, "Adaptive, because " + saysAdaptive.get(0) + ".");
        }
        if (!saysStatic.isEmpty()) {
            return new Kind(QuestionnaireKind.STATIC, "Static, because " + saysStatic.get(0) + ".");
        }
        if (unreadableMarker) {
            return new Kind(QuestionnaireKind.UNDETERMINED, "Undetermined, because its questionnaireAdaptive extension holds a value "
                    + "that is neither true, false nor a url.");
        }
        if (items > 0) {
            return new Kind(QuestionnaireKind.STATIC, "Static, because it carries no adaptive marker and holds " + items
                    + (items == 1 ? " item" : " items") + " of its own at the top level.");
        }
        return new Kind(QuestionnaireKind.UNDETERMINED, "Undetermined, because it carries no adaptive marker and no items, so nothing "
                + "says how it is meant to be answered.");
    }

    /**
     * ASSUMPTION: an unknown member or provider arrives as a FHIR OperationOutcome whose issue code
     * is {@code not-found}, and the issue says which by naming the element in {@code expression},
     * {@code location} or a {@code details} coding. This is the rule the CRD client assumes too. It
     * is restated here, not shared, so that DTR can be corrected by itself when the real behaviour
     * of each operation is known. An issue that is not-found but names neither is not classified.
     */
    public static Set<VendorSignal> signals(VendorExchange exchange) {
        EnumSet<VendorSignal> signals = EnumSet.noneOf(VendorSignal.class);
        JsonNode body = exchange == null ? null : exchange.json();
        if (body == null || !OperationOutcomes.is(body)) {
            return signals;
        }
        for (JsonNode issue : JsonSafe.elements(body, "issue")) {
            if (!"not-found".equals(JsonSafe.text(issue, "code"))) {
                continue;
            }
            List<String> pointers = new ArrayList<>();
            JsonSafe.elements(issue, "expression").forEach(e -> pointers.add(JsonSafe.text(e)));
            JsonSafe.elements(issue, "location").forEach(e -> pointers.add(JsonSafe.text(e)));
            JsonSafe.elements(JsonSafe.child(issue, "details"), "coding").forEach(c -> pointers.add(JsonSafe.text(c, "code")));
            boolean member = mentions(pointers, MEMBER_WORDS);
            boolean provider = mentions(pointers, PROVIDER_WORDS);
            // An issue that names both is ambiguous, so it is left unclassified.
            if (member && !provider) {
                signals.add(VendorSignal.MEMBER_NOT_FOUND);
            } else if (provider && !member) {
                signals.add(VendorSignal.PROVIDER_NOT_FOUND);
            }
        }
        return signals;
    }

    private static boolean mentions(List<String> pointers, List<String> words) {
        for (String pointer : pointers) {
            if (Texts.present(pointer)) {
                String lower = pointer.toLowerCase(Locale.ROOT);
                if (words.stream().anyMatch(lower::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
