package com.thehiddenbrain.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the FHIR data types that turn up across CRD, DTR and PAS responses into values for the
 * screen, through {@link TrackedJson} so that only the parts actually shown count as consumed.
 *
 * <p>Each reader consumes exactly the fields it puts into the text it returns. Anything else on
 * the same element, such as a version or a nested extension, stays unconsumed and so appears in
 * the additional fields.
 */
public final class FhirViews {

    private FhirViews() {
    }

    /** A Coding as "display (system|code)", using whichever parts are present. */
    public static Shown coding(TrackedJson.Node coding) {
        if (!coding.isObject()) {
            return coding.shown();
        }
        String system = coding.presentText("system");
        String code = coding.presentText("code");
        String display = coding.presentText("display");
        String coded = code == null ? null : (system == null ? code : system + "|" + code);
        String text = display != null && coded != null ? display + " (" + coded + ")" : Texts.firstPresent(display, coded);
        return text == null ? Shown.empty() : Shown.of(text);
    }

    /** A CodeableConcept as its text, followed by each coding in brackets. */
    public static Shown concept(TrackedJson.Node concept) {
        if (!concept.isObject()) {
            return concept.shown();
        }
        String text = concept.presentText("text");
        List<String> codings = new ArrayList<>();
        for (TrackedJson.Node coding : concept.children("coding")) {
            Shown shown = coding(coding);
            if (shown.hasValue()) {
                codings.add(shown.value());
            }
        }
        if (text == null && codings.isEmpty()) {
            return Shown.empty();
        }
        if (text == null) {
            return Shown.of(String.join("; ", codings));
        }
        return Shown.of(codings.isEmpty() ? text : text + " [" + String.join("; ", codings) + "]");
    }

    /** The code values a CodeableConcept carries, in order, without consuming anything. */
    public static List<String> conceptCodes(TrackedJson.Node concept) {
        List<String> codes = new ArrayList<>();
        for (tools.jackson.databind.JsonNode coding : JsonSafe.elements(concept.raw(), "coding")) {
            String code = JsonSafe.presentText(coding, "code");
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** A Reference as its reference, with the display in brackets when there is one. */
    public static Shown reference(TrackedJson.Node reference) {
        if (!reference.isObject()) {
            return reference.shown();
        }
        String ref = reference.presentText("reference");
        String display = reference.presentText("display");
        String text = ref != null && display != null ? ref + " (" + display + ")" : Texts.firstPresent(ref, display);
        return text == null ? Shown.empty() : Shown.of(text);
    }

    /** The {@code value[x]} property of an extension: the first property whose name starts with "value". */
    public static Map.Entry<String, TrackedJson.Node> extensionValue(TrackedJson.Node extension) {
        for (Map.Entry<String, TrackedJson.Node> property : extension.properties()) {
            if (property.getKey().startsWith("value")) {
                return property;
            }
        }
        return null;
    }

    /**
     * The value of an extension for display. A Coding, CodeableConcept or Reference is rendered as
     * such; a scalar as its text; anything else as its JSON. Absent when the extension has no value.
     */
    public static Shown extensionValueShown(TrackedJson.Node extension) {
        Map.Entry<String, TrackedJson.Node> value = extensionValue(extension);
        if (value == null) {
            return Shown.absent();
        }
        return switch (value.getKey()) {
            case "valueCoding" -> coding(value.getValue());
            case "valueCodeableConcept" -> concept(value.getValue());
            case "valueReference" -> reference(value.getValue());
            default -> value.getValue().shown();
        };
    }

    /**
     * The code an extension carries, for an element bound to a value set. Read from a code or
     * string value directly, or from the code of a Coding or the first coding of a CodeableConcept.
     * The returned presence says whether the value was absent, null, empty or present.
     */
    public static Shown extensionCode(TrackedJson.Node extension) {
        Map.Entry<String, TrackedJson.Node> value = extensionValue(extension);
        if (value == null) {
            return Shown.absent();
        }
        TrackedJson.Node node = value.getValue();
        if (node.isObject()) {
            if ("valueCodeableConcept".equals(value.getKey())) {
                List<TrackedJson.Node> codings = node.children("coding");
                return codings.isEmpty() ? node.shown("text") : codings.get(0).shown("code");
            }
            return node.shown("code");
        }
        return node.shown();
    }
}
