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
 * EVERYTHING THIS TOOL TAKES THE VENDOR TO DO IN DTR, ALL IN ONE FILE.
 *
 * <p>Nothing here has been observed from the live service, and which version of the Da Vinci DTR
 * guide it follows is not known. Each rule below says where it comes from. "READ" means it was
 * read in the guides themselves: the HL7 packages hl7.fhir.us.davinci-dtr 2.2.0-ballot and
 * hl7.fhir.uv.sdc 4.0.0-ballot, and the specification page of the DTR guide as published for
 * STU 2.0.1 and STU 2.1.0. "ASSUMPTION" means the guides leave it open, or differ between
 * versions, and this tool had to choose. Each rule reads a coded value or the structure of a
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
     * READ: the input parameter names of {@code $questionnaire-package}. The coverage and each
     * order travel as resources, a questionnaire is named by its canonical url as a
     * {@code valueCanonical}, and the coverage assertion id CRD returned travels as {@code context},
     * a {@code valueString}. The 2.2.0-ballot parameters profile requires the coverage and at least
     * one of order, questionnaire and context. The guide also defines {@code changedsince}, for a
     * client that kept an earlier response. This tool keeps none and does not send it.
     *
     * <p>{@code referenced}, for what an order refers to, is named by the specification page of
     * every version read. The 2.2.0-ballot parameters profile does not list it. ASSUMPTION: the
     * live service takes it, so no reference in an order is left pointing at nothing.
     */
    public static final String PARAM_COVERAGE = "coverage";
    public static final String PARAM_ORDER = "order";
    public static final String PARAM_REFERENCED = "referenced";
    public static final String PARAM_QUESTIONNAIRE = "questionnaire";
    public static final String PARAM_CONTEXT = "context";

    /**
     * READ: only the payer can say an adaptive response is complete, and it does so by returning
     * it with no further question and its status set to complete. The guides write "complete". The
     * FHIR code is {@code completed}, and that is what is matched. ASSUMPTION: the loop continues
     * while the response comes back {@link #STATUS_IN_PROGRESS}. Any other status, including the
     * other codes FHIR defines, is reported as one this tool cannot act on.
     */
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_IN_PROGRESS = "in-progress";

    /**
     * This tool's own choice: the id given to the questionnaire a first QuestionnaireResponse
     * carries inside itself, when the questionnaire arrived with no id of its own. A contained
     * resource is referred to by its id, so it has to have one.
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
     * READ: the guides do not agree on the body of {@code next-question}. SDC defines one input,
     * a QuestionnaireResponse named {@code questionnaire-response}, and one output named
     * {@code return}. DTR 2.0.1 uses that operation as it is. For an operation whose one input is
     * a resource, FHIR lets the resource be posted by itself. DTR 2.2.0-ballot defines a
     * Parameters resource in both directions instead, with the response under
     * {@code questionnaire-response-in} going out and {@code questionnaire-response-out} coming
     * back.
     *
     * <p>ASSUMPTION: the live service takes the QuestionnaireResponse by itself. Which shape it
     * takes is not confirmed. Any of these shapes is read when it comes back.
     *
     * @param questionnaireResponse the QuestionnaireResponse so far
     * @return the body to send
     */
    public static JsonNode nextQuestionBody(JsonNode questionnaireResponse) {
        return questionnaireResponse;
    }

    /**
     * READ: an adaptive questionnaire lives inside its QuestionnaireResponse and nowhere else, and
     * a response to a conventional form names its questionnaire by canonical url. ASSUMPTION: so a
     * response that carries its questionnaire inside itself is one from the adaptive loop, whatever
     * the questionnaire inside it is marked as. It matters because only the service can say that a
     * response from the loop is finished: this tool must not call one complete because the
     * questions asked so far happen to be answered.
     */
    public static boolean fromTheLoop(JsonNode questionnaireResponse) {
        return questionnaireResponse != null && QuestionnaireJson.containedQuestionnaire(questionnaireResponse) != null;
    }

    /**
     * What kind a questionnaire is, and what on it decided that.
     *
     * @param basis            one plain sentence, shown beside the kind
     * @param nextQuestionBase the url the questionnaireAdaptive extension holds, or null when it
     *                         holds none. READ: the guide makes it the base address next-question is
     *                         called on for this questionnaire
     */
    public record Kind(QuestionnaireKind kind, String basis, String nextQuestionBase) {
    }

    /**
     * READ: the address the guide gives next-question for a questionnaire whose adaptive marker
     * holds a url, as STU 2.1.0 and later write it. STU 2.0.1 writes it without the resource type,
     * and for a marker that is only true it gives the payer's own base address, which this tool
     * cannot know from the questionnaire.
     */
    public static String nextQuestionAddress(String base) {
        return base.replaceAll("/+$", "") + "/Questionnaire/$next-question";
    }

    /**
     * Every canonical url that names the questionnaire a QuestionnaireResponse answers, the one
     * that says most first. Empty when the response names none in any of these ways.
     *
     * <p>READ: a response from the adaptive loop carries its questionnaire inside itself, and that
     * questionnaire states in {@code derivedFrom} the canonical url of the questionnaire being
     * completed. The DTR guide says so of the response a package carries for an adaptive
     * questionnaire, and the SDC profile gives the questionnaire inside no url of its own at all.
     * So what the questionnaire inside is derived from comes first, then its own url when it has
     * one. A response that carries no questionnaire names one by canonical url.
     *
     * <p>ASSUMPTION: the live service does one of these. A service that ties the two only by giving
     * the questionnaire inside the url of the one in the package is matched by the second entry.
     */
    public static List<String> completes(JsonNode questionnaireResponse) {
        List<String> out = new ArrayList<>();
        if (!QuestionnaireJson.isType(questionnaireResponse, "QuestionnaireResponse")) {
            return out;
        }
        JsonNode inside = QuestionnaireJson.containedQuestionnaire(questionnaireResponse);
        if (inside != null) {
            out.addAll(QuestionnaireJson.derivedFrom(inside));
            String own = QuestionnaireJson.canonical(inside);
            if (own != null && !out.contains(own)) {
                out.add(own);
            }
            return out;
        }
        String named = JsonSafe.presentText(questionnaireResponse, "questionnaire");
        // A local reference such as #q1 names something inside the response and is not a canonical url.
        if (named != null && !named.startsWith("#")) {
            out.add(named);
        }
        return out;
    }

    /**
     * READ: an adaptive questionnaire carries the SDC {@code questionnaireAdaptive} extension,
     * holding true or the base url next-question is called on. DTR 2.0.1 speaks of both, its later
     * versions only of the url, and the 2.2.0-ballot profiles allow nothing else. In a package it
     * declares the profile dtr-questionnaire-adapt-search and holds no items, inside a response it
     * declares dtr-questionnaire-adapt, and a conventional form declares dtr-std-questionnaire.
     * ASSUMPTION: a questionnaire with no marker at all that holds items is a conventional form.
     * How the live service marks an adaptive questionnaire has not been observed.
     *
     * <p>When the markers contradict each other, or there is no marker and no item, nothing settles
     * the kind and none is picked.
     */
    public static Kind kind(JsonNode questionnaire) {
        List<String> saysAdaptive = new ArrayList<>();
        List<String> saysStatic = new ArrayList<>();
        boolean unreadableMarker = false;
        String base = null;
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
                base = Texts.orElse(base, endpoint);
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
                    + saysAdaptive.get(0) + ", and " + saysStatic.get(0) + ".", base);
        }
        if (!saysAdaptive.isEmpty()) {
            return new Kind(QuestionnaireKind.ADAPTIVE, "Adaptive, because " + saysAdaptive.get(0) + ".", base);
        }
        if (!saysStatic.isEmpty()) {
            return new Kind(QuestionnaireKind.STATIC, "Static, because " + saysStatic.get(0) + ".", null);
        }
        if (unreadableMarker) {
            return new Kind(QuestionnaireKind.UNDETERMINED, "Undetermined, because its questionnaireAdaptive extension holds a value "
                    + "that is neither true, false nor a url.", null);
        }
        if (items > 0) {
            return new Kind(QuestionnaireKind.STATIC, "Static, because it carries no adaptive marker and holds " + items
                    + (items == 1 ? " item" : " items") + " of its own at the top level.", null);
        }
        return new Kind(QuestionnaireKind.UNDETERMINED, "Undetermined, because it carries no adaptive marker and no items, so nothing "
                + "says how it is meant to be answered.", null);
    }

    /**
     * READ: when the package operation fails, the 2.2.0-ballot definition has the service answer
     * with an OperationOutcome and no Parameters resource. The draft of the guide that follows it
     * adds that a coverage or a context the service cannot find is answered with a 4xx status.
     * Neither says which issue code is used, or how the issue names what was not found.
     *
     * <p>ASSUMPTION: an unknown member or provider arrives as an issue whose code is
     * {@code not-found}, and the issue says which by naming the element in {@code expression},
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
