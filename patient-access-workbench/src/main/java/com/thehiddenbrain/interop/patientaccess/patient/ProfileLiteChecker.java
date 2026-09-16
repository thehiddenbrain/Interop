package com.thehiddenbrain.interop.patientaccess.patient;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks a resource against the element rules of a C4BB / PDex profile without a full validator:
 * required elements (min > 0) must be present when their parent is, fixed / pattern values must
 * match, and must-support elements are reported as not populated (informational: a server only
 * has to send them when it has the data). Slices are matched by their fixed discriminator values.
 */
@Component
public class ProfileLiteChecker {

    public record Issue(String severity, String path, String message) {
    }

    public record Report(String profile, String profileName, boolean declared, int errors, int warnings, int infos, List<Issue> issues) {

        public static Report none(String reason) {
            return new Report(null, null, false, 0, 0, 0, List.of(new Issue("info", "", reason)));
        }
    }

    private final IgCatalog catalog;

    public ProfileLiteChecker(IgCatalog catalog) {
        this.catalog = catalog;
    }

    /** Picks the profile from meta.profile (first one the catalog knows), else the default for the resource type. */
    public Report check(JsonNode resource) {
        Optional<IgCatalog.ProfileSpec> declared = Fhir.profiles(resource).stream()
                .map(catalog::profile).filter(Optional::isPresent).map(Optional::get)
                .filter(p -> p.type().equals(resource.path("resourceType").asString(""))).findFirst();
        if (declared.isPresent()) {
            return check(resource, declared.get(), true);
        }
        Optional<IgCatalog.ProfileSpec> fallback = defaultProfile(resource);
        return fallback.map(p -> check(resource, p, false))
                .orElseGet(() -> Report.none("no C4BB / PDex profile rules for " + resource.path("resourceType").asString("")
                        + " in this catalog (US Core profiles are checked by the full validator only)"));
    }

    public Report check(JsonNode resource, String profileUrl) {
        return catalog.profile(profileUrl).map(p -> check(resource, p, Fhir.profiles(resource).stream().anyMatch(x -> x.startsWith(p.url()))))
                .orElseGet(() -> Report.none("unknown profile " + profileUrl));
    }

    Optional<IgCatalog.ProfileSpec> defaultProfile(JsonNode resource) {
        String type = resource.path("resourceType").asString("");
        String url = switch (type) {
            case "Patient" -> "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient";
            case "Coverage" -> "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Coverage";
            case "Organization" -> "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Organization";
            case "Practitioner" -> "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Practitioner";
            case "RelatedPerson" -> "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-RelatedPerson";
            case "MedicationDispense" -> "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-medicationdispense";
            case "Provenance" -> "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-provenance";
            case "ExplanationOfBenefit" -> eobProfile(resource);
            default -> null;
        };
        return url == null ? Optional.empty() : catalog.profile(url);
    }

    static String eobProfile(JsonNode eob) {
        if (PriorAuthSummarizer.isPriorAuth(eob)) {
            return PriorAuthSummarizer.PDEX_PA_PROFILE;
        }
        String type = Fhir.code(eob.path("type"), null);
        String sub = Fhir.code(eob.path("subType"), null);
        String base = "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-ExplanationOfBenefit";
        if (type == null) {
            return base;
        }
        return switch (type) {
            case "institutional" -> "outpatient".equals(sub) ? base + "-Outpatient-Institutional" : "inpatient".equals(sub) ? base + "-Inpatient-Institutional" : base;
            case "professional", "vision" -> base + "-Professional-NonClinician";
            case "pharmacy" -> base + "-Pharmacy";
            case "oral" -> base + "-Oral";
            default -> base;
        };
    }

    Report check(JsonNode resource, IgCatalog.ProfileSpec profile, boolean declared) {
        List<Issue> issues = new ArrayList<>();
        if (!declared) {
            issues.add(new Issue("warning", "meta.profile", "meta.profile does not declare " + profile.url()
                    + " (C4BB and PDex require the profile to be declared); checked against it anyway"));
        }
        Map<String, IgCatalog.ProfileRule> byId = new java.util.LinkedHashMap<>();
        for (IgCatalog.ProfileRule r : profile.elements()) {
            byId.put(r.id(), r);
        }
        for (IgCatalog.ProfileRule rule : profile.elements()) {
            evaluate(resource, rule, byId, issues);
        }
        int errors = (int) issues.stream().filter(i -> i.severity().equals("error")).count();
        int warnings = (int) issues.stream().filter(i -> i.severity().equals("warning")).count();
        int infos = issues.size() - errors - warnings;
        return new Report(profile.url(), profile.name(), declared, errors, warnings, infos, issues);
    }

    private void evaluate(JsonNode resource, IgCatalog.ProfileRule rule, Map<String, IgCatalog.ProfileRule> byId, List<Issue> issues) {
        String id = rule.id();
        int depth = (int) id.chars().filter(ch -> ch == '.').count();
        if (depth > 4) {
            return; // deep nested rules are noise for a lite check
        }
        String parentId = id.substring(0, id.lastIndexOf('.'));
        List<JsonNode> parents = parentId.contains(".") ? matches(resource, parentId, byId) : List.of(resource);
        if (parents.isEmpty()) {
            return; // the parent is absent: a required child cannot be judged
        }
        String path = rule.path();
        String lastSegment = id.substring(id.lastIndexOf('.') + 1);
        int lacking = 0;
        int present = 0;
        boolean fixedMismatch = false;
        // cardinality is judged per parent repetition: one item without a required child is a defect even when others carry it
        for (JsonNode parent : parents) {
            List<JsonNode> values = step(List.of(parent), lastSegment, id, byId);
            if (values.isEmpty()) {
                lacking++;
                continue;
            }
            present++;
            if (rule.fixed() != null && rule.slice() == null) {
                for (JsonNode v : values) {
                    if (!matchesFixed(v, rule.fixed())) {
                        fixedMismatch = true;
                        break;
                    }
                }
            }
        }
        String where = parents.size() > 1 && lacking > 0 ? " in " + lacking + " of " + parents.size() + " repetitions of "
                + parentId.substring(parentId.lastIndexOf('.') + 1).replaceAll(":.*", "") : "";
        if (rule.min() > 0 && lacking > 0) {
            if (rule.slice() == null) {
                issues.add(new Issue("error", path, "required element (" + rule.min() + ".." + rule.max() + ") is missing" + where));
            } else {
                issues.add(new Issue("error", path, "required slice '" + rule.slice() + "' (" + rule.min() + ".." + rule.max() + ") not found" + where));
            }
        } else if (rule.mustSupport() && present == 0) {
            issues.add(new Issue("info", path, "must-support element" + (rule.slice() == null ? "" : " (slice " + rule.slice() + ")") + " not populated"));
        }
        if (fixedMismatch) {
            issues.add(new Issue("error", path, "value does not match the fixed/pattern value " + rule.fixed().toString()));
        }
    }

    /** All JSON nodes a rule id points at, honouring slices and choice types. */
    List<JsonNode> matches(JsonNode resource, String id, Map<String, IgCatalog.ProfileRule> byId) {
        String[] segments = id.split("\\.");
        List<JsonNode> current = List.of(resource);
        StringBuilder prefix = new StringBuilder(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            prefix.append('.').append(seg);
            current = step(current, seg, prefix.toString(), byId);
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    /** One path segment (element name, optional slice, choice type) applied to a set of nodes. */
    private List<JsonNode> step(List<JsonNode> current, String seg, String sliceId, Map<String, IgCatalog.ProfileRule> byId) {
        String name = seg.contains(":") ? seg.substring(0, seg.indexOf(':')) : seg;
        String slice = seg.contains(":") ? seg.substring(seg.indexOf(':') + 1) : null;
        boolean choice = name.endsWith("[x]");
        if (choice) {
            name = name.substring(0, name.length() - 3);
        }
        // a type slice of a choice element (value[x]:valueString) is named after the JSON property it selects
        boolean typeSlice = choice && slice != null && slice.startsWith(name);
        List<JsonNode> next = new ArrayList<>();
        for (JsonNode node : current) {
            if (typeSlice) {
                JsonNode child = node.get(slice);
                if (child != null) {
                    addAll(next, child);
                }
            } else if (choice) {
                Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getKey().startsWith(name) && e.getKey().length() > name.length() && Character.isUpperCase(e.getKey().charAt(name.length()))) {
                        addAll(next, e.getValue());
                    }
                }
            } else {
                JsonNode child = node.get(name);
                if (child != null) {
                    addAll(next, child);
                }
            }
        }
        if (slice != null && !typeSlice) {
            String elementName = name;
            next = next.stream().filter(n -> sliceMatches(n, sliceId, elementName, byId)).toList();
        }
        return next;
    }

    private static void addAll(List<JsonNode> out, JsonNode value) {
        if (value.isArray()) {
            value.forEach(out::add);
        } else if (!value.isNull()) {
            out.add(value);
        }
    }

    /**
     * A candidate belongs to a slice when every fixed discriminator of the slice (own or child rules) matches.
     * A slice without any fixed discriminator (discriminated by a value-set binding or by type, e.g. C4BB
     * adjudicationamounttype or timing[x]:timingPeriod) takes every candidate that no sibling slice with a
     * fixed discriminator claims: a lite check cannot tell such slices apart any further.
     */
    boolean sliceMatches(JsonNode candidate, String sliceId, String elementName, Map<String, IgCatalog.ProfileRule> byId) {
        IgCatalog.ProfileRule own = byId.get(sliceId);
        boolean anyDiscriminator = false;
        if (own != null && own.fixed() != null) {
            anyDiscriminator = true;
            if (!matchesFixed(candidate, own.fixed())) {
                return false;
            }
        }
        if ("extension".equals(elementName) || "modifierExtension".equals(elementName)) {
            IgCatalog.ProfileRule urlRule = byId.get(sliceId + ".url");
            String url = null;
            if (urlRule != null && urlRule.fixed() != null) {
                url = firstText(urlRule.fixed());
            }
            if (url == null && own != null && own.typeProfiles() != null && !own.typeProfiles().isEmpty()) {
                url = own.typeProfiles().get(0);
            }
            if (url != null) {
                return url.equals(candidate.path("url").asString(""));
            }
        }
        for (Map.Entry<String, IgCatalog.ProfileRule> e : byId.entrySet()) {
            String cid = e.getKey();
            if (!cid.startsWith(sliceId + ".") || e.getValue().fixed() == null) {
                continue;
            }
            String rel = cid.substring(sliceId.length() + 1);
            if (rel.contains(":")) {
                continue;
            }
            anyDiscriminator = true;
            JsonNode value = walk(candidate, rel);
            if (value == null || !matchesFixed(value, e.getValue().fixed())) {
                return false;
            }
        }
        if (!anyDiscriminator) {
            String base = sliceId.substring(0, sliceId.lastIndexOf(':'));
            java.util.Set<String> siblings = new java.util.LinkedHashSet<>();
            for (String key : byId.keySet()) {
                if (key.startsWith(base + ":")) {
                    int dot = key.indexOf('.', base.length());
                    siblings.add(dot < 0 ? key : key.substring(0, dot)); // slice roots may only exist through their child rules
                }
            }
            for (String sibling : siblings) {
                if (!sibling.equals(sliceId) && hasFixedDiscriminator(sibling, byId) && sliceMatches(candidate, sibling, elementName, byId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasFixedDiscriminator(String sliceId, Map<String, IgCatalog.ProfileRule> byId) {
        IgCatalog.ProfileRule own = byId.get(sliceId);
        if (own != null && own.fixed() != null) {
            return true;
        }
        for (Map.Entry<String, IgCatalog.ProfileRule> e : byId.entrySet()) {
            String cid = e.getKey();
            if (cid.startsWith(sliceId + ".") && e.getValue().fixed() != null && !cid.substring(sliceId.length() + 1).contains(":")) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode walk(JsonNode node, String relativePath) {
        JsonNode cur = node;
        for (String seg : relativePath.split("\\.")) {
            String name = seg.endsWith("[x]") ? seg.substring(0, seg.length() - 3) : seg;
            if (cur.isArray()) {
                cur = cur.size() > 0 ? cur.get(0) : null;
                if (cur == null) {
                    return null;
                }
            }
            JsonNode next = cur.get(name);
            if (next == null && seg.endsWith("[x]")) {
                Iterator<Map.Entry<String, JsonNode>> it = cur.properties().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getKey().startsWith(name) && e.getKey().length() > name.length()) {
                        next = e.getValue();
                        break;
                    }
                }
            }
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return cur;
    }

    /** fixed/pattern object from the catalog: {"patternCodeableConcept": {...}} etc. */
    static boolean matchesFixed(JsonNode value, JsonNode fixed) {
        Iterator<Map.Entry<String, JsonNode>> it = fixed.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String kind = e.getKey();
            JsonNode expected = e.getValue();
            if (kind.endsWith("CodeableConcept")) {
                for (JsonNode coding : expected.path("coding")) {
                    boolean found = false;
                    for (JsonNode actual : value.path("coding")) {
                        if (sameCoding(actual, coding)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return false;
                    }
                }
                return true;
            }
            if (kind.endsWith("Coding")) {
                return sameCoding(value, expected);
            }
            if (kind.endsWith("Identifier")) {
                return expected.path("system").asString("").equals(value.path("system").asString(""));
            }
            if (expected.isValueNode()) {
                String actual = value.isValueNode() ? value.asString("") : null;
                if (actual == null) {
                    return false;
                }
                if (kind.endsWith("Canonical") || kind.endsWith("Uri")) {
                    return actual.equals(expected.asString("")) || actual.startsWith(expected.asString("") + "|");
                }
                return actual.equals(expected.asString(""));
            }
            return true;
        }
        return true;
    }

    private static boolean sameCoding(JsonNode actual, JsonNode expected) {
        return (!expected.has("system") || expected.path("system").asString("").equals(actual.path("system").asString("")))
                && (!expected.has("code") || expected.path("code").asString("").equals(actual.path("code").asString("")));
    }

    private static String firstText(JsonNode fixed) {
        Iterator<JsonNode> it = fixed.values().iterator();
        return it.hasNext() ? it.next().asString("") : null;
    }
}
