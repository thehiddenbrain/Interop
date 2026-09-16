package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The search parameters the demo server evaluates: for each name its FHIR type and the JSON elements the values
 * live in. Plain element paths (no FHIRPath engine) keep the mapping readable for a tester who wonders why a
 * search matched. Which names apply to a resource type comes from the IG catalog (C4BB / PDex / US Core / USDF
 * declarations) plus the common clinical parameters every type accepts.
 */
final class DemoParams {

    enum Kind { TOKEN, REFERENCE, DATE, STRING }

    /** {@code firstOnly}: use the first path that yields values (e.g. {@code date} means effective[x] for an Observation, period for an Encounter). */
    record Def(String name, Kind kind, boolean firstOnly, List<String[]> paths) {
    }

    static final String USDF = "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/";

    /** Parameters every FHIR server understands; they are handled by the engine and never reported as unknown. */
    static final Set<String> COMMON = Set.of("_id", "_lastUpdated", "_profile", "_count", "page", "_include", "_revinclude",
            "_format", "_pretty", "_sort", "_total", "_summary", "_elements");

    static final List<String> PATIENT = List.of("identifier", "name", "family", "given", "birthdate", "gender", "death-date");
    static final List<String> COVERAGE = List.of("patient", "beneficiary", "identifier", "status", "subscriber", "payor");
    static final List<String> EOB = List.of("patient", "identifier", "type", "use", "status", "service-date", "service-start-date",
            "billable-period-start", "created", "provider", "insurer", "coverage", "care-team");
    /** What every other (clinical) type accepts, on top of what the catalog declares for it. */
    static final List<String> CLINICAL = List.of("patient", "subject", "identifier", "category", "code", "clinical-status", "status",
            "intent", "date", "effective", "period", "onset-date", "recorded-date", "authoredon", "target-date", "type", "class",
            "encounter", "lifecycle-status", "description");

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        def("_id", Kind.TOKEN, p("id"));
        def("_lastUpdated", Kind.DATE, p("meta", "lastUpdated"));
        def("_profile", Kind.TOKEN, p("meta", "profile"));
        def("identifier", Kind.TOKEN, p("identifier"));
        def("patient", Kind.REFERENCE, p("patient"), p("subject"), p("beneficiary"));
        def("subject", Kind.REFERENCE, p("subject"), p("patient"));
        def("beneficiary", Kind.REFERENCE, p("beneficiary"));
        def("subscriber", Kind.REFERENCE, p("subscriber"));
        def("payor", Kind.REFERENCE, p("payor"));
        def("provider", Kind.REFERENCE, p("provider"));
        def("insurer", Kind.REFERENCE, p("insurer"));
        def("coverage", Kind.REFERENCE, p("insurance", "coverage"));
        def("care-team", Kind.REFERENCE, p("careTeam", "provider"));
        def("payee", Kind.REFERENCE, p("payee", "party"));
        def("encounter", Kind.REFERENCE, p("encounter"), p("context", "encounter"));
        def("location", Kind.REFERENCE, p("location", "location"), p("location"));
        def("target", Kind.REFERENCE, p("target"));
        def("performer", Kind.REFERENCE, p("performer"), p("performer", "actor"));
        def("medication", Kind.REFERENCE, p("medicationReference"));
        def("participant", Kind.REFERENCE, p("participant", "member"));
        def("author", Kind.REFERENCE, p("author"));
        def("requester", Kind.REFERENCE, p("requester"));
        def("practitioner", Kind.REFERENCE, p("practitioner"));
        def("organization", Kind.REFERENCE, p("organization"));
        def("formulary", Kind.REFERENCE, p("ext:" + USDF + "usdf-FormularyReference-extension", "valueReference"));
        def("formulary-coverage", Kind.REFERENCE, p("coverage", "ext:" + USDF + "usdf-FormularyReference-extension", "valueReference"));
        def("coverage-area", Kind.REFERENCE, p("coverageArea"));
        def("status", Kind.TOKEN, p("status"), p("ext:" + USDF + "usdf-AvailabilityStatus-extension", "valueCode"));
        def("use", Kind.TOKEN, p("use"));
        def("type", Kind.TOKEN, p("type"));
        def("class", Kind.TOKEN, p("class"));
        def("category", Kind.TOKEN, p("category"));
        def("code", Kind.TOKEN, p("code"), p("medicationCodeableConcept"), p("vaccineCode"));
        def("clinical-status", Kind.TOKEN, p("clinicalStatus"));
        def("verification-status", Kind.TOKEN, p("verificationStatus"));
        def("intent", Kind.TOKEN, p("intent"));
        def("lifecycle-status", Kind.TOKEN, p("lifecycleStatus"));
        def("description", Kind.TOKEN, p("description"));
        def("gender", Kind.TOKEN, p("gender"));
        def("role", Kind.TOKEN, p("participant", "role"));
        def("specialty", Kind.TOKEN, p("specialty"));
        def("characteristic", Kind.TOKEN, p("characteristic", "code"));
        def("drug-tier", Kind.TOKEN, p("ext:" + USDF + "usdf-DrugTierID-extension", "valueCodeableConcept"));
        def("pharmacy-benefit-type", Kind.TOKEN, p("ext:" + USDF + "usdf-PharmacyBenefitType-extension", "valueCodeableConcept"));
        def("coverage-type", Kind.TOKEN, p("coverage", "type"));
        def("doseform", Kind.TOKEN, p("doseForm"));
        def("discharge-disposition", Kind.TOKEN, p("hospitalization", "dischargeDisposition"));
        def("name", Kind.STRING, p("name"));
        def("family", Kind.STRING, p("name", "family"));
        def("given", Kind.STRING, p("name", "given"));
        def("address", Kind.STRING, p("address"));
        def("address-city", Kind.STRING, p("address", "city"));
        def("address-state", Kind.STRING, p("address", "state"));
        def("address-postalcode", Kind.STRING, p("address", "postalCode"));
        def("drug-name", Kind.STRING, p("code", "coding", "display"), p("code", "text"));
        def("birthdate", Kind.DATE, p("birthDate"));
        def("death-date", Kind.DATE, p("deceasedDateTime"));
        DEFS.put("date", new Def("date", Kind.DATE, true, List.of(p("effective[x]"), p("period"), p("date"), p("occurrence[x]"),
                p("performed[x]"), p("recorded"), p("issued"), p("created"))));
        def("effective", Kind.DATE, p("effective[x]"));
        def("period", Kind.DATE, p("period"), p("context", "period"), p("ext:" + USDF + "usdf-AvailabilityPeriod-extension", "valuePeriod"));
        def("onset-date", Kind.DATE, p("onset[x]"));
        def("abatement-date", Kind.DATE, p("abatement[x]"));
        def("recorded-date", Kind.DATE, p("recordedDate"));
        def("asserted-date", Kind.DATE, p("ext:http://hl7.org/fhir/StructureDefinition/condition-assertedDate", "valueDateTime"));
        def("authoredon", Kind.DATE, p("authoredOn"));
        def("authored", Kind.DATE, p("authored"), p("authoredOn"));
        def("target-date", Kind.DATE, p("target", "due[x]"));
        def("created", Kind.DATE, p("created"));
        def("service-date", Kind.DATE, p("billablePeriod"), p("item", "serviced[x]"));
        def("service-start-date", Kind.DATE, p("billablePeriod", "start"), p("item", "servicedDate"), p("item", "servicedPeriod", "start"));
        def("billable-period-start", Kind.DATE, p("billablePeriod", "start"));
    }

    private DemoParams() {
    }

    private static void def(String name, Kind kind, String[]... paths) {
        DEFS.put(name, new Def(name, kind, false, List.of(paths)));
    }

    private static String[] p(String... segments) {
        return segments;
    }

    static Def def(String name) {
        return DEFS.get(name);
    }

    /**
     * Searchable (non-common) parameters of a type: the fixed list for Patient / Coverage / EOB, the clinical list
     * for everything else, plus whatever the catalog declares for the type as long as this server can evaluate it.
     */
    static Set<String> supported(String type, IgCatalog catalog) {
        Set<String> out = new LinkedHashSet<>();
        List<String> base = switch (type) {
            case "Patient" -> PATIENT;
            case "Coverage" -> COVERAGE;
            case "ExplanationOfBenefit" -> EOB;
            default -> CLINICAL;
        };
        out.addAll(base);
        catalog.resource(type).ifPresent(spec -> spec.searchParams().forEach(sp -> {
            if (!COMMON.contains(sp.name()) && DEFS.containsKey(sp.name())) {
                out.add(sp.name());
            }
        }));
        return out;
    }

    /** The JSON nodes a parameter's values live in for one resource (empty when the element is absent). */
    static List<JsonNode> values(Def def, JsonNode resource) {
        List<JsonNode> out = new ArrayList<>();
        for (String[] path : def.paths()) {
            List<JsonNode> found = select(resource, path, 0);
            out.addAll(found);
            if (def.firstOnly() && !found.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /**
     * Walks a path through objects and arrays. A segment {@code name[x]} matches any choice key ({@code effectiveDateTime},
     * {@code effectivePeriod}); {@code ext:<url>} selects the extensions with that url.
     */
    static List<JsonNode> select(JsonNode node, String[] path, int index) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                out.addAll(select(child, path, index));
            }
            return out;
        }
        if (index == path.length) {
            out.add(node);
            return out;
        }
        String segment = path[index];
        if (segment.startsWith("ext:")) {
            String url = segment.substring(4);
            for (JsonNode ext : node.path("extension")) {
                if (url.equals(ext.path("url").asText())) {
                    out.addAll(select(ext, path, index + 1));
                }
            }
        } else if (segment.endsWith("[x]")) {
            String prefix = segment.substring(0, segment.length() - 3);
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (key.startsWith(prefix) && key.length() > prefix.length() && Character.isUpperCase(key.charAt(prefix.length()))) {
                    out.addAll(select(e.getValue(), path, index + 1));
                }
            });
        } else {
            out.addAll(select(node.get(segment), path, index + 1));
        }
        return out;
    }

    /** All literal references under an element named like a search parameter (used for _include / _revinclude). */
    static List<String> references(JsonNode resource, String field) {
        Def def = DEFS.get(field);
        List<JsonNode> nodes;
        if (def != null && def.kind() == Kind.REFERENCE) {
            nodes = values(def, resource);
        } else {
            String element = camel(field);
            nodes = new ArrayList<>();
            resource.fields().forEachRemaining(e -> {
                if (e.getKey().equals(element) || (e.getKey().startsWith(element) && e.getKey().length() > element.length()
                        && Character.isUpperCase(e.getKey().charAt(element.length())))) {
                    nodes.add(e.getValue());
                }
            });
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode n : nodes) {
            collectReferences(n, refs);
        }
        return refs;
    }

    static List<String> allReferences(JsonNode resource) {
        List<String> refs = new ArrayList<>();
        resource.fields().forEachRemaining(e -> {
            if (!"contained".equals(e.getKey())) {
                collectReferences(e.getValue(), refs);
            }
        });
        return refs;
    }

    static void collectReferences(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collectReferences(n, out));
        } else if (node.isObject()) {
            if (node.hasNonNull("reference") && node.get("reference").isTextual()) {
                out.add(node.get("reference").asText());
            }
            node.fields().forEachRemaining(e -> {
                if (!"reference".equals(e.getKey())) {
                    collectReferences(e.getValue(), out);
                }
            });
        }
    }

    /** {@code care-team} to {@code careTeam}. */
    static String camel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
