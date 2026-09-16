package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a FHIR search against the in-memory demo data: parameter validation (lenient or
 * {@code Prefer: handling=strict}), filtering, patient scoping for patient-bound tokens, paging with
 * {@code _count} / {@code page}, and {@code _include} / {@code _revinclude} resolution. Returns plain lists;
 * the controller turns them into a Bundle.
 */
@Component
@DemoEnabled
public class DemoSearchEngine {

    public static final int DEFAULT_COUNT = 20;
    public static final int MAX_COUNT = 200;

    /** One matched page plus the resources included for it. */
    public record Page(List<ObjectNode> matches, List<ObjectNode> included, int total, int page, int count, boolean hasNext, boolean hasPrevious) {
    }

    private record Filter(String name, String modifier, DemoParams.Def def, List<String> values) {
    }

    private final DemoDataStore store;
    private final IgCatalog catalog;
    private final Map<String, Set<String>> supportedParams = new ConcurrentHashMap<>();
    private final Set<String> supportedTypes;

    public DemoSearchEngine(DemoDataStore store, IgCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
        Set<String> types = new TreeSet<>(store.types());
        catalog.resources().forEach((type, spec) -> {
            if (spec.interactions().contains("read") || spec.interactions().contains("search-type")) {
                types.add(type);
            }
        });
        this.supportedTypes = Set.copyOf(types);
    }

    /** Resource types the server answers for: everything in the data plus the Patient Access types of the catalog. */
    public Set<String> supportedTypes() {
        return supportedTypes;
    }

    public boolean supports(String type) {
        return supportedTypes.contains(type);
    }

    public Set<String> supportedParams(String type) {
        return supportedParams.computeIfAbsent(type, t -> DemoParams.supported(t, catalog));
    }

    /**
     * @param rawParams    the request's parameters (names may carry modifiers such as {@code patient:Patient})
     * @param strict       reject unknown parameters instead of ignoring them
     * @param patientScope patient id of a patient-bound token, or null for full access
     */
    public Page search(String type, Map<String, String[]> rawParams, boolean strict, String patientScope) {
        List<Filter> filters = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        List<String> revIncludes = new ArrayList<>();
        int count = DEFAULT_COUNT;
        int page = 1;
        Set<String> supported = supportedParams(type);
        for (Map.Entry<String, String[]> e : rawParams.entrySet()) {
            String raw = e.getKey();
            int colon = raw.indexOf(':');
            String name = colon < 0 ? raw : raw.substring(0, colon);
            String modifier = colon < 0 ? null : raw.substring(colon + 1);
            // A repeated parameter (service-date=ge..&service-date=le..) is one filter per occurrence, AND-ed;
            // the comma alternatives inside one occurrence are OR-ed when the filter is evaluated.
            List<String> values = List.of(e.getValue());
            switch (name) {
                case "_count" -> count = Math.min(MAX_COUNT, parseInt(name, values.get(0), 0));
                case "page" -> page = parseInt(name, values.get(0), 1);
                case "_include" -> includes.addAll(values);
                case "_revinclude" -> revIncludes.addAll(values);
                case "_id", "_lastUpdated", "_profile" -> values.forEach(v -> filters.add(new Filter(name, modifier, DemoParams.def(name), List.of(v))));
                default -> {
                    if (DemoParams.COMMON.contains(name)) {
                        continue;
                    }
                    if (!supported.contains(name) || (modifier != null && !knownModifier(modifier))) {
                        if (strict) {
                            throw DemoFhirException.notSupported("search parameter '" + raw + "' is not supported for " + type);
                        }
                        continue;
                    }
                    values.forEach(v -> filters.add(new Filter(name, modifier, DemoParams.def(name), List.of(v))));
                }
            }
        }
        if (patientScope != null) {
            for (Filter f : filters) {
                if (f.def().kind() == DemoParams.Kind.REFERENCE && Set.of("patient", "subject", "beneficiary").contains(f.name())) {
                    for (String v : f.values()) {
                        for (String single : v.split(",")) {
                            String id = referencedId(single.trim());
                            if (!patientScope.equals(id)) {
                                throw DemoFhirException.forbidden("the token is bound to Patient/" + patientScope + " and cannot search data of " + single.trim());
                            }
                        }
                    }
                }
            }
        }
        List<ObjectNode> matches = new ArrayList<>();
        for (ObjectNode candidate : store.all(type)) {
            if (visible(candidate, patientScope) && matchesAll(candidate, filters)) {
                matches.add(candidate);
            }
        }
        int total = matches.size();
        int from = Math.min(total, (page - 1) * count);
        int to = Math.min(total, from + count);
        List<ObjectNode> slice = count == 0 ? List.of() : matches.subList(from, to);
        List<ObjectNode> included = includes(type, slice, includes, revIncludes, patientScope);
        return new Page(slice, included, total, page, count, count > 0 && to < total, page > 1);
    }

    /** A resource is visible to a patient-bound token when it belongs to that patient or to no patient at all. */
    public boolean visible(JsonNode resource, String patientScope) {
        if (patientScope == null) {
            return true;
        }
        Optional<String> owner = store.patientOf(resource);
        return owner.isEmpty() || owner.get().equals(patientScope);
    }

    private boolean matchesAll(ObjectNode resource, List<Filter> filters) {
        for (Filter f : filters) {
            List<JsonNode> nodes = DemoParams.values(f.def(), resource);
            boolean any = false;
            for (String value : f.values()) {
                if (matchesAny(f, nodes, value)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** Comma-separated alternatives of one parameter value are OR-ed. */
    private static boolean matchesAny(Filter f, List<JsonNode> nodes, String value) {
        for (String single : value.split(",")) {
            String v = single.trim();
            if (v.isEmpty()) {
                continue;
            }
            boolean hit = "_profile".equals(f.name()) ? profileMatches(nodes, v) : DemoMatchers.matches(f.def().kind(), nodes, v, f.modifier());
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** {@code _profile=url} matches any version of the canonical; {@code url|version} needs that version. */
    static boolean profileMatches(List<JsonNode> profiles, String value) {
        for (JsonNode p : profiles) {
            String declared = p.asText();
            if (declared.equals(value) || (!value.contains("|") && bare(declared).equals(value))) {
                return true;
            }
        }
        return false;
    }

    private static String bare(String canonical) {
        int bar = canonical.indexOf('|');
        return bar < 0 ? canonical : canonical.substring(0, bar);
    }

    private static boolean knownModifier(String modifier) {
        return modifier.equals("exact") || modifier.equals("contains") || (!modifier.isEmpty() && Character.isUpperCase(modifier.charAt(0)));
    }

    private static int parseInt(String name, String value, int min) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < min) {
                throw DemoFhirException.invalid(name + " must be at least " + min);
            }
            return n;
        } catch (NumberFormatException e) {
            throw DemoFhirException.invalid(name + " must be a whole number, got '" + value + "'");
        }
    }

    static String referencedId(String value) {
        String key = DemoDataStore.referenceKey(value);
        return key == null ? value : key.substring(key.indexOf('/') + 1);
    }

    /**
     * {@code _include=Type:param[:Target]} resolves the references of the page's resources ({@code Type:*} all of them);
     * {@code _revinclude=Type:param} adds the resources of Type whose {@code param} points at a page resource.
     * Included resources are unique and never duplicate a match.
     */
    private List<ObjectNode> includes(String type, List<ObjectNode> slice, List<String> includes, List<String> revIncludes, String scope) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        Set<String> matched = new LinkedHashSet<>();
        for (ObjectNode m : slice) {
            matched.add(type + "/" + m.get("id").asText());
        }
        for (String spec : includes) {
            String[] parts = spec.split(":");
            if (parts.length < 2 || (!parts[0].equals(type) && !parts[0].equals("*"))) {
                continue;
            }
            String field = parts[1];
            String targetType = parts.length > 2 ? parts[2] : null;
            for (ObjectNode m : slice) {
                List<String> refs = field.equals("*") ? DemoParams.allReferences(m) : DemoParams.references(m, field);
                for (String ref : refs) {
                    String key = DemoDataStore.referenceKey(ref);
                    if (key == null || matched.contains(key) || out.containsKey(key) || (targetType != null && !key.startsWith(targetType + "/"))) {
                        continue;
                    }
                    store.resolve(key).filter(r -> visible(r, scope)).ifPresent(r -> out.put(key, r));
                }
            }
        }
        for (String spec : revIncludes) {
            String[] parts = spec.split(":");
            if (parts.length < 2 || (parts.length > 2 && !parts[2].equals(type))) {
                continue;
            }
            for (ObjectNode candidate : store.all(parts[0])) {
                String key = parts[0] + "/" + candidate.get("id").asText();
                if (matched.contains(key) || out.containsKey(key) || !visible(candidate, scope)) {
                    continue;
                }
                for (String ref : DemoParams.references(candidate, parts[1])) {
                    String target = DemoDataStore.referenceKey(ref);
                    if (target != null && matched.contains(target)) {
                        out.put(key, candidate);
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }
}
