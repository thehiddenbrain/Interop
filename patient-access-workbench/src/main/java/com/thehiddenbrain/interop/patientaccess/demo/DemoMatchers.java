package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares one search value with the JSON elements a parameter points at, per FHIR search type:
 * token ({@code system|code} against Identifier, Coding, CodeableConcept, code, boolean), reference
 * ({@code Patient/1}, {@code 1}, absolute URL), string (case- and accent-insensitive contains; {@code :exact}),
 * date (prefix semantics from {@link DemoDates}).
 */
final class DemoMatchers {

    private static final Set<String> STRING_SKIP = Set.of("use", "type", "period", "id", "extension", "assigner", "system");

    private DemoMatchers() {
    }

    static boolean matches(DemoParams.Kind kind, List<JsonNode> nodes, String value, String modifier) {
        return switch (kind) {
            case TOKEN -> token(nodes, value);
            case REFERENCE -> reference(nodes, value, modifier);
            case STRING -> string(nodes, value, modifier);
            case DATE -> date(nodes, value);
        };
    }

    static boolean token(List<JsonNode> nodes, String value) {
        String system = null;
        String code = value;
        boolean systemGiven = false;
        int bar = value.indexOf('|');
        if (bar >= 0) {
            systemGiven = true;
            system = value.substring(0, bar);
            code = value.substring(bar + 1);
        }
        for (JsonNode n : nodes) {
            if (n.isValueNode()) {
                if (!systemGiven && code.equals(n.asText())) {
                    return true;
                }
            } else if (n.has("coding")) {
                for (JsonNode c : n.get("coding")) {
                    if (codingMatches(c.path("system").asText(null), c.path("code").asText(null), system, code, systemGiven)) {
                        return true;
                    }
                }
            } else if (n.has("code")) {
                if (codingMatches(n.path("system").asText(null), n.path("code").asText(null), system, code, systemGiven)) {
                    return true;
                }
            } else if (n.has("value")) {
                if (codingMatches(n.path("system").asText(null), n.path("value").asText(null), system, code, systemGiven)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean codingMatches(String nodeSystem, String nodeCode, String system, String code, boolean systemGiven) {
        if (systemGiven) {
            boolean systemOk = system.isEmpty() ? nodeSystem == null || nodeSystem.isEmpty() : system.equals(nodeSystem);
            if (!systemOk) {
                return false;
            }
            return code.isEmpty() || code.equals(nodeCode);
        }
        return code.equals(nodeCode);
    }

    /** {@code modifier} may carry the target type ({@code patient:Patient=1}). */
    static boolean reference(List<JsonNode> nodes, String value, String modifier) {
        String want = value.trim();
        if (modifier != null && !modifier.isEmpty() && Character.isUpperCase(modifier.charAt(0)) && !want.contains("/")) {
            want = modifier + "/" + want;
        }
        String wantKey = DemoDataStore.referenceKey(want);
        for (JsonNode n : nodes) {
            String ref = n.isValueNode() ? n.asText() : n.path("reference").asText(null);
            String key = DemoDataStore.referenceKey(ref);
            if (key == null) {
                continue;
            }
            if (wantKey != null ? key.equals(wantKey) : key.endsWith("/" + want)) {
                return true;
            }
        }
        return false;
    }

    static boolean string(List<JsonNode> nodes, String value, String modifier) {
        boolean exact = "exact".equals(modifier);
        String want = exact ? value : fold(value);
        for (JsonNode n : nodes) {
            for (String text : texts(n)) {
                if (exact ? text.equals(want) : fold(text).contains(want)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every textual leaf of a string, HumanName, Address or similar element (skipping codes such as use/type). */
    static List<String> texts(JsonNode node) {
        List<String> out = new ArrayList<>();
        collectTexts(node, out);
        return out;
    }

    private static void collectTexts(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(n -> collectTexts(n, out));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (!STRING_SKIP.contains(e.getKey())) {
                    collectTexts(e.getValue(), out);
                }
            });
        }
    }

    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    /** Throws {@link DemoFhirException} (400 invalid) when the search value is not a date. */
    static boolean date(List<JsonNode> nodes, String value) {
        String[] parts = DemoDates.splitPrefix(value);
        DemoDates.Range param;
        try {
            param = DemoDates.parse(parts[1]);
        } catch (IllegalArgumentException e) {
            throw DemoFhirException.invalid("invalid date search value: " + e.getMessage());
        }
        for (JsonNode n : nodes) {
            DemoDates.Range target = DemoDates.of(n);
            if (target != null && DemoDates.matches(parts[0], param, target)) {
                return true;
            }
        }
        return false;
    }
}
