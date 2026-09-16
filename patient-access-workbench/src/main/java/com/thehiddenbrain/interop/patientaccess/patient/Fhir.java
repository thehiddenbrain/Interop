package com.thehiddenbrain.interop.patientaccess.patient;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Small JSON helpers for rendering FHIR data types as text. */
public final class Fhir {

    private Fhir() {
    }

    public static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString("");
    }

    /** CodeableConcept: text, else first coding's display, else "system|code". */
    public static String concept(JsonNode cc) {
        if (cc == null || cc.isMissingNode() || cc.isNull()) {
            return null;
        }
        if (cc.hasNonNull("text")) {
            return cc.get("text").asString("");
        }
        JsonNode coding = cc.path("coding");
        if (coding.isArray() && coding.size() > 0) {
            return coding(coding.get(0));
        }
        return null;
    }

    public static String coding(JsonNode c) {
        if (c == null || c.isMissingNode()) {
            return null;
        }
        String display = text(c.get("display"));
        String code = text(c.get("code"));
        if (display != null && code != null) {
            return display + " (" + code + ")";
        }
        return display != null ? display : code;
    }

    /** First code of a CodeableConcept, optionally restricted to a system. */
    public static String code(JsonNode cc, String system) {
        for (JsonNode c : cc.path("coding")) {
            if (system == null || system.equals(c.path("system").asString(""))) {
                return text(c.get("code"));
            }
        }
        return null;
    }

    public static boolean hasCoding(JsonNode cc, String system, String code) {
        for (JsonNode c : cc.path("coding")) {
            if ((system == null || system.equals(c.path("system").asString(""))) && code.equals(c.path("code").asString(""))) {
                return true;
            }
        }
        return false;
    }

    public static String reference(JsonNode ref) {
        if (ref == null || ref.isMissingNode() || ref.isNull()) {
            return null;
        }
        String display = text(ref.get("display"));
        String r = text(ref.get("reference"));
        if (display != null && r != null) {
            return display + " [" + r + "]";
        }
        if (display != null) {
            return display;
        }
        if (r != null) {
            return r;
        }
        JsonNode id = ref.get("identifier");
        return id == null ? null : text(id.get("value"));
    }

    public static String period(JsonNode p) {
        if (p == null || p.isMissingNode() || p.isNull()) {
            return null;
        }
        String start = text(p.get("start"));
        String end = text(p.get("end"));
        if (start == null && end == null) {
            return null;
        }
        return (start == null ? "" : start) + " – " + (end == null ? "" : end);
    }

    public static String money(JsonNode m) {
        if (m == null || m.isMissingNode() || m.isNull()) {
            return null;
        }
        String value = text(m.get("value"));
        String currency = text(m.get("currency"));
        return value == null ? null : value + (currency == null ? "" : " " + currency);
    }

    public static String quantity(JsonNode q) {
        if (q == null || q.isMissingNode() || q.isNull()) {
            return null;
        }
        String value = text(q.get("value"));
        String unit = text(q.get("unit"));
        if (unit == null) {
            unit = text(q.get("code"));
        }
        return value == null ? null : value + (unit == null ? "" : " " + unit);
    }

    /** The value of a choice element such as serviced[x] / effective[x] / value[x]. */
    public static JsonNode choice(JsonNode parent, String prefix) {
        if (parent == null) {
            return null;
        }
        var it = parent.properties().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix) && e.getKey().length() > prefix.length()
                    && Character.isUpperCase(e.getKey().charAt(prefix.length()))) {
                return e.getValue();
            }
        }
        return null;
    }

    public static String choiceText(JsonNode parent, String prefix) {
        JsonNode v = choice(parent, prefix);
        if (v == null) {
            return null;
        }
        if (v.isValueNode()) {
            return v.asString("");
        }
        if (v.has("start") || v.has("end")) {
            return period(v);
        }
        if (v.has("coding") || v.has("text")) {
            return concept(v);
        }
        // Quantity / Money / Identifier; a Quantity may carry nothing but its value (PDex PriorAuthorizationUtilization)
        if (v.has("value") && v.get("value").isValueNode()) {
            return v.has("currency") ? money(v) : quantity(v);
        }
        if (v.has("reference") || v.has("display")) {
            return reference(v);
        }
        if (v.has("numerator")) {
            return quantity(v.get("numerator")) + " / " + quantity(v.get("denominator"));
        }
        return v.toString();
    }

    public static List<String> profiles(JsonNode resource) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : resource.path("meta").path("profile")) {
            out.add(p.asString(""));
        }
        return out;
    }

    public static JsonNode extension(JsonNode element, String url) {
        if (element == null) {
            return null;
        }
        for (JsonNode ext : element.path("extension")) {
            if (url.equals(ext.path("url").asString(""))) {
                return ext;
            }
        }
        return null;
    }

    public static List<JsonNode> extensions(JsonNode element, String url) {
        List<JsonNode> out = new ArrayList<>();
        if (element == null) {
            return out;
        }
        for (JsonNode ext : element.path("extension")) {
            if (url.equals(ext.path("url").asString(""))) {
                out.add(ext);
            }
        }
        return out;
    }

    public static String extensionValue(JsonNode element, String url) {
        JsonNode ext = extension(element, url);
        return ext == null ? null : choiceText(ext, "value");
    }

    public static String idOf(JsonNode resource) {
        return text(resource.get("id"));
    }

    public static String lastUpdated(JsonNode resource) {
        return text(resource.path("meta").get("lastUpdated"));
    }

    public static String join(List<String> parts) {
        List<String> clean = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                clean.add(p);
            }
        }
        return clean.isEmpty() ? null : String.join("; ", clean);
    }
}
