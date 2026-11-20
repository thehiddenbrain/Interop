package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a request or a response this tool built leaves out that the DTR 2.0.1 profiles require.
 *
 * <p>Nothing is invented to fill a gap: a fact about the member, the provider or the order comes
 * from the form, a lookup or a variable, and when it is missing the element is left out. The
 * request still goes, because a request that breaks a profile is a negative case a tester may
 * want. What this class does is make the gap visible: one note for each element left out, naming
 * the profile of the version that requires it and what would supply it, a first note with the
 * count, and one finding of kind {@link FindingKind#REQUEST_INCOMPLETE}, so that a refusal by the
 * service is not read as a fault of the service.
 *
 * <p>The rules are the ones read in the DTR 2.0.1 package and the CRD 2.0.1 package it builds on.
 * Each is checked on the JSON that is sent, so a gap closed by another builder closes here too.
 */
final class DtrProfileCheck {

    private static final String QR_PROFILE = "The DTR 2.0.1 QuestionnaireResponse profile (dtr-questionnaireresponse)";
    private static final String ADAPT_PROFILE = "The DTR 2.0.1 adaptive questionnaire profile (dtr-questionnaire-adapt)";

    /**
     * One element left out.
     *
     * @param element  the element, as a tester would search the profile for it
     * @param sentence the note: which profile of which version requires it, and what would supply it
     */
    record Gap(String element, String sentence) {
    }

    private DtrProfileCheck() {
    }

    /**
     * The gaps of a QuestionnaireResponse this tool built or added to.
     *
     * @param builtHere true when this tool started the response from nothing, so the reasons for a
     *                  gap are the ones it knows. False when it started from a response it was given
     * @param loop      true when the response goes to next-question
     */
    static List<Gap> response(JsonNode response, DtrRequestBuilder.Filler filler, boolean builtHere, boolean loop) {
        List<Gap> gaps = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        boolean laterCoverage = false;
        boolean intendedUse = false;
        for (JsonNode extension : JsonSafe.elements(response, "extension")) {
            String url = JsonSafe.presentText(extension, "url");
            if (OnyxDtrConventions.EXT_QR_CONTEXT.equals(url)) {
                contexts.add(Texts.orElse(JsonSafe.presentText(JsonSafe.child(extension, "valueReference"), "reference"), ""));
            } else if (OnyxDtrConventions.EXT_QR_COVERAGE.equals(url)) {
                laterCoverage = true;
            } else if (OnyxDtrConventions.EXT_INTENDED_USE.equals(url)) {
                intendedUse = true;
            }
        }
        if (contexts.size() < 2) {
            List<String> why = new ArrayList<>();
            boolean coverageNamed = contexts.stream().anyMatch(reference -> reference.startsWith("Coverage/"));
            boolean orderNamed = contexts.stream().anyMatch(reference -> !reference.startsWith("Coverage/"));
            if (!coverageNamed) {
                why.add(laterCoverage ? "the coverage is named in qr-coverage, the DTR 2.2.0-ballot form, which 2.0.1 does not define"
                        + (builtHere ? " (the variable " + DtrResponseShape.VAR_COVERAGE_EXTENSION + " asked for it)" : "")
                        : builtHere ? "the coverage is not named, because the request names no member"
                        : "the coverage is not named in the response this step started from");
            }
            if (!orderNamed) {
                why.add(builtHere ? "no order is named, because this step was handed no order and no procedure code"
                        : "no order is named in the response this step started from");
            }
            gaps.add(new Gap("QuestionnaireResponse.extension:context", QR_PROFILE + " requires at least two qr-context extensions, "
                    + "naming the coverage and the order. This response has " + contexts.size() + (why.isEmpty() ? "." : ": "
                    + String.join("; ", why) + ".")));
        }
        if (!intendedUse) {
            gaps.add(new Gap("QuestionnaireResponse.extension:intendedUse", QR_PROFILE + " requires at least one intendedUse extension, "
                    + "from the CRD 2.0.1 value set DocReason. None is stated: it comes from the variable "
                    + DtrResponseShape.VAR_INTENDED_USE + ", or from what CRD said the documentation is for (doc-purpose), and neither "
                    + "stated one. This tool does not assume one."));
        }
        if (!response.has("subject")) {
            gaps.add(new Gap("QuestionnaireResponse.subject", QR_PROFILE + " requires a subject. It names the member by the Patient of "
                    + "the case, and the request names no member."));
        }
        if (QuestionnaireJson.responseItems(response).isEmpty()) {
            gaps.add(new Gap("QuestionnaireResponse.item", QR_PROFILE + " requires at least one item, and this response has none"
                    + (loop ? ". SDC has the first next-question call carry no answers, so the two guides differ here."
                    : ", because no question was answered.")));
        }
        int unmarked = 0;
        int broken = 0;
        for (Map.Entry<String, List<JsonNode>> answers : QuestionnaireJson.answers(response).entrySet()) {
            for (JsonNode answer : answers.getValue()) {
                JsonNode origin = OnyxDtrConventions.origin(answer);
                if (origin == null) {
                    unmarked++;
                } else if (!dtrx1(origin)) {
                    broken++;
                }
            }
        }
        if (unmarked > 0) {
            gaps.add(new Gap("QuestionnaireResponse.item.answer.extension:origin", QR_PROFILE + " requires every answer to carry the "
                    + "information-origin extension. " + (unmarked == 1 ? "1 answer carries" : unmarked + " answers carry") + " none. They came with the response this step "
                    + "started from, or were posted as written, and this tool does not say where an answer came from that it did not see given."));
        }
        if (broken > 0) {
            gaps.add(new Gap("information-origin dtrx-1", (broken == 1 ? "1 answer carries" : broken + " answers carry") + " an information-origin extension that breaks "
                    + "the DTR 2.0.1 invariant dtrx-1: an answer from a person (manual or override) names its author, and an answer "
                    + "pre-populated by the payer (auto) names none. They are sent as they came."));
        }
        return gaps;
    }

    /** The gaps of the questionnaire a first next-question call carries inside its response. */
    static List<Gap> adaptiveCopy(JsonNode contained) {
        List<Gap> gaps = new ArrayList<>();
        if (contained == null) {
            return gaps;
        }
        if (JsonSafe.presentText(contained, "title") == null) {
            gaps.add(new Gap("Questionnaire.title", ADAPT_PROFILE + " requires a title on the questionnaire inside the response. The "
                    + "questionnaire the package returned has none, and this tool does not write one."));
        }
        if (!JsonSafe.child(contained, "text").isObject()) {
            gaps.add(new Gap("Questionnaire.text", ADAPT_PROFILE + " requires a narrative text on the questionnaire inside the response. "
                    + "The questionnaire the package returned has none, and this tool does not write one."));
        }
        if (JsonSafe.elements(contained, "derivedFrom").size() != 1) {
            gaps.add(new Gap("Questionnaire.derivedFrom", ADAPT_PROFILE + " requires the questionnaire inside the response to name "
                    + "exactly one questionnaire it is derived from. The questionnaire the package returned has no url, so there is "
                    + "nothing to name."));
        }
        return gaps;
    }

    /**
     * The gaps of a $questionnaire-package request: DTR 2.0.1 requires the coverage to follow the
     * CRD 2.0.1 Coverage profile and a ServiceRequest order to follow the CRD 2.0.1 ServiceRequest
     * profile.
     */
    static List<Gap> packageRequest(JsonNode parameters) {
        List<Gap> gaps = new ArrayList<>();
        String coverageProfile = "The CRD 2.0.1 Coverage profile (profile-coverage), which DTR 2.0.1 requires of the coverage parameter,";
        String orderProfile = "The CRD 2.0.1 ServiceRequest profile (profile-servicerequest), which DTR 2.0.1 requires of an order,";
        for (JsonNode parameter : JsonSafe.elements(parameters, "parameter")) {
            String name = JsonSafe.presentText(parameter, "name");
            JsonNode resource = JsonSafe.child(parameter, "resource");
            if (OnyxDtrConventions.PARAM_COVERAGE.equals(name)) {
                missing(gaps, resource, "Coverage.status", coverageProfile, "It was left out: it comes from the coverage status the member "
                        + "lookup returned, and none this tool can map to a FHIR code was supplied.");
                missing(gaps, resource, "Coverage.payor", coverageProfile, "It was left out: it comes from the variable "
                        + CaseResources.VAR_PAYER_ID + " or " + CaseResources.VAR_PAYER_NAME + ", and neither is set.");
                missing(gaps, resource, "Coverage.beneficiary", coverageProfile, "It was left out.");
            } else if (OnyxDtrConventions.PARAM_ORDER.equals(name) && QuestionnaireJson.isType(resource, "ServiceRequest")) {
                String id = Texts.orElse(JsonSafe.presentText(resource, "id"), "an order");
                for (String element : List.of("status", "intent", "code", "subject", "authoredOn", "requester")) {
                    missing(gaps, resource, "ServiceRequest." + element + " (" + id + ")", orderProfile,
                            "The order this tool builds was sent without it.");
                }
            }
        }
        return gaps;
    }

    private static void missing(List<Gap> gaps, JsonNode resource, String element, String profile, String why) {
        String property = element.substring(element.indexOf('.') + 1).split(" ")[0];
        JsonNode value = JsonSafe.child(resource, property);
        boolean present = value.isArray() ? !value.isEmpty() : value.isObject() ? !value.isEmpty() : JsonSafe.presentText(value) != null;
        if (!present) {
            gaps.add(new Gap(element, profile + " requires " + element + ". " + why));
        }
    }

    /** True when an information-origin extension meets dtrx-1: an author exactly when the source is not auto. */
    static boolean dtrx1(JsonNode origin) {
        boolean auto = false;
        boolean author = false;
        for (JsonNode part : JsonSafe.elements(origin, "extension")) {
            String url = JsonSafe.presentText(part, "url");
            if ("source".equals(url) && OnyxDtrConventions.ORIGIN_AUTO.equals(JsonSafe.presentText(part, "valueCode"))) {
                auto = true;
            } else if ("author".equals(url)) {
                author = true;
            }
        }
        return auto != author;
    }

    /**
     * The note that goes first: what was left out, counted, in one sentence.
     *
     * @param sent true for a request, false for a response put together inside this tool
     */
    static String summary(List<Gap> gaps, boolean sent) {
        return (sent ? "This request goes out without " : "This response leaves out ") + count(gaps.size(), "element")
                + " the DTR 2.0.1 profiles require (" + String.join(", ", gaps.stream().map(Gap::element).toList())
                + "). Each is explained below with what would supply it.";
    }

    /** The notes of a request or a response with gaps: the summary first, then one per gap. Empty when there are none. */
    static List<String> notes(List<Gap> gaps, boolean sent) {
        List<String> notes = new ArrayList<>();
        if (!gaps.isEmpty()) {
            notes.add(summary(gaps, sent));
            gaps.forEach(gap -> notes.add(gap.sentence()));
        }
        return notes;
    }

    /** The one finding a request or a response with gaps carries, or null when there are none. */
    static Finding finding(List<Gap> gaps, boolean sent) {
        if (gaps.isEmpty()) {
            return null;
        }
        String what = String.join(", ", gaps.stream().map(Gap::element).toList());
        return new Finding(FindingKind.REQUEST_INCOMPLETE,
                (sent ? "The request left out " : "The response left out ") + count(gaps.size(), "element") + " DTR 2.0.1 requires",
                (sent ? "This tool sent the request without " : "This tool put the response together without ") + what
                        + ", because nothing supplied " + (gaps.size() == 1 ? "it" : "them") + "." + (sent ? "" : " PAS sends the response as it is.")
                        + " A refusal by the service that names " + (gaps.size() == 1 ? "it" : "one of them")
                        + " is about what was sent, not a fault of the service. The notes " + (sent ? "beside the request" : "with the response")
                        + " say what would supply each.",
                "DTR 2.0.1", null, null);
    }

    private static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }
}
