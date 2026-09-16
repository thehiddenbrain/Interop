package com.thehiddenbrain.interop.patientaccess.catalog;

import com.thehiddenbrain.interop.patientaccess.common.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The implementation-guide facts the workbench relies on, generated from the HL7 packages by
 * {@code tools/generate-catalog.py}: per resource type the profiles, interactions, search parameters
 * (with SHALL/SHOULD/MAY expectation), search-parameter combinations, includes and operations; plus
 * element rules of the C4BB / PDex profiles and the code systems used to label prior-auth data.
 */
@Component
public class IgCatalog {

    /** Search parameters every FHIR server understands regardless of IG declarations. */
    public static final Set<String> COMMON_PARAMS = Set.of("_id", "_lastUpdated", "_count", "_sort", "_include", "_revinclude",
            "_summary", "_elements", "_total", "_format", "_pretty", "_profile", "_tag", "_security", "_has", "_page", "_getpages",
            "_getpagesoffset", "page", "ct");

    public record IgInfo(String key, String name, String canonical, String version) {
    }

    public record SearchParamSpec(String name, String type, String expectation, List<String> igs, String definition,
                                  String description, String expression) {
    }

    public record ComboSpec(List<String> params, String expectation, List<String> igs) {
    }

    public record ProfileRef(String url, String name, String ig) {
    }

    public record OperationSpec(String name, String definition, String ig) {
    }

    public record ResourceSpec(String type, List<ProfileRef> profiles, List<String> interactions, List<SearchParamSpec> searchParams,
                               List<ComboSpec> combos, List<String> includes, List<String> revIncludes, List<OperationSpec> operations) {

        public Optional<SearchParamSpec> param(String name) {
            return searchParams.stream().filter(p -> p.name().equals(name)).findFirst();
        }
    }

    public record Binding(String strength, String valueSet) {
    }

    public record ProfileRule(String id, String path, int min, String max, boolean mustSupport, String slice, JsonNode fixed,
                              List<String> types, List<String> typeProfiles, Binding binding, String shortDescription) {
    }

    public record ProfileSpec(String url, String name, String title, String ig, String version, String type, String base,
                              String description, List<ProfileRule> elements) {
    }

    public record EobProfile(String url, String name, String claimType, String subType, String use) {
    }

    private final Map<String, IgInfo> igs = new LinkedHashMap<>();
    private final Map<String, ResourceSpec> resources = new LinkedHashMap<>();
    private final Map<String, ProfileSpec> profiles = new LinkedHashMap<>();
    private final List<EobProfile> eobProfiles = new ArrayList<>();
    private final JsonNode codeSystems;
    private final JsonNode valueSets;

    public IgCatalog() {
        this(load());
    }

    public IgCatalog(JsonNode root) {
        root.path("igs").properties().forEach(e -> igs.put(e.getKey(), new IgInfo(e.getKey(), e.getValue().path("name").asString(""),
                e.getValue().path("canonical").asString(""), e.getValue().path("version").asString(null))));
        root.path("resources").properties().forEach(e -> resources.put(e.getKey(), resource(e.getKey(), e.getValue())));
        root.path("profiles").properties().forEach(e -> profiles.put(e.getKey(), profile(e.getKey(), e.getValue())));
        for (JsonNode p : root.path("eobProfiles")) {
            eobProfiles.add(new EobProfile(p.path("url").asString(""), p.path("name").asString(""), p.path("claimType").asString(null),
                    p.path("subType").asString(null), p.path("use").asString(null)));
        }
        codeSystems = root.path("codeSystems");
        valueSets = root.path("valueSets");
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("catalog/ig-catalog.json").getInputStream()) {
            return Json.MAPPER.readTree(in);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("cannot load catalog/ig-catalog.json", e);
        }
    }

    private static ResourceSpec resource(String type, JsonNode n) {
        List<ProfileRef> profiles = new ArrayList<>();
        for (JsonNode p : n.path("profiles")) {
            profiles.add(new ProfileRef(p.path("url").asString(""), p.path("name").asString(""), p.path("ig").asString("")));
        }
        List<SearchParamSpec> params = new ArrayList<>();
        for (JsonNode p : n.path("searchParams")) {
            params.add(new SearchParamSpec(p.path("name").asString(""), p.path("type").asString("string"), p.path("expectation").asString("MAY"),
                    strings(p.path("igs")), p.path("definition").asString(null), p.path("description").asString(""), p.path("expression").asString(null)));
        }
        List<ComboSpec> combos = new ArrayList<>();
        for (JsonNode c : n.path("combos")) {
            combos.add(new ComboSpec(strings(c.path("params")), c.path("expectation").asString("MAY"), strings(c.path("igs"))));
        }
        List<OperationSpec> ops = new ArrayList<>();
        for (JsonNode o : n.path("operations")) {
            ops.add(new OperationSpec(o.path("name").asString(""), o.path("definition").asString(null), o.path("ig").asString(null)));
        }
        return new ResourceSpec(type, profiles, strings(n.path("interactions")), params, combos, strings(n.path("includes")),
                strings(n.path("revIncludes")), ops);
    }

    private static ProfileSpec profile(String url, JsonNode n) {
        List<ProfileRule> rules = new ArrayList<>();
        for (JsonNode e : n.path("elements")) {
            Binding b = e.has("binding") ? new Binding(e.path("binding").path("strength").asString(""), e.path("binding").path("valueSet").asString("")) : null;
            rules.add(new ProfileRule(e.path("id").asString(""), e.path("path").asString(""), e.path("min").asInt(0), e.path("max").asString("*"),
                    e.path("mustSupport").asBoolean(false), e.path("slice").asString(null), e.get("fixed"), strings(e.path("types")),
                    strings(e.path("typeProfiles")), b, e.path("short").asString(null)));
        }
        return new ProfileSpec(url, n.path("name").asString(""), n.path("title").asString(null), n.path("ig").asString(""), n.path("version").asString(null),
                n.path("type").asString(""), n.path("base").asString(null), n.path("description").asString(null), rules);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            out.add(v.asString(""));
        }
        return out;
    }

    public Map<String, IgInfo> igs() {
        return Collections.unmodifiableMap(igs);
    }

    public Map<String, ResourceSpec> resources() {
        return Collections.unmodifiableMap(resources);
    }

    public Optional<ResourceSpec> resource(String type) {
        return Optional.ofNullable(resources.get(type));
    }

    public Map<String, ProfileSpec> profiles() {
        return Collections.unmodifiableMap(profiles);
    }

    public Optional<ProfileSpec> profile(String url) {
        String bare = url.contains("|") ? url.substring(0, url.indexOf('|')) : url;
        return Optional.ofNullable(profiles.get(bare));
    }

    public List<EobProfile> eobProfiles() {
        return Collections.unmodifiableList(eobProfiles);
    }

    public Optional<EobProfile> eobProfile(String url) {
        String bare = url.contains("|") ? url.substring(0, url.indexOf('|')) : url;
        return eobProfiles.stream().filter(p -> p.url().equals(bare)).findFirst();
    }

    public JsonNode codeSystems() {
        return codeSystems;
    }

    public JsonNode valueSets() {
        return valueSets;
    }

    /** Display text for a code of a catalog code system (e.g. {@code c4bbAdjudication}), or null. */
    public String display(String codeSystemKey, String code) {
        JsonNode cs = codeSystems.path(codeSystemKey).path("codes");
        return cs.hasNonNull(code) ? cs.get(code).asString("") : null;
    }

    /** Warnings for parameters the IGs do not declare for this resource type (the search is still sent). */
    public List<String> validateParams(String resourceType, Iterable<String> paramNames) {
        List<String> warnings = new ArrayList<>();
        Optional<ResourceSpec> spec = resource(resourceType);
        for (String raw : paramNames) {
            String name = raw.contains(":") ? raw.substring(0, raw.indexOf(':')) : raw;
            if (COMMON_PARAMS.contains(name)) {
                continue;
            }
            if (spec.isEmpty()) {
                warnings.add(resourceType + " is not part of the Patient Access API IGs in this catalog");
                break;
            }
            if (spec.get().param(name).isEmpty()) {
                warnings.add("search parameter '" + name + "' is not declared for " + resourceType + " by C4BB, PDex or US Core; the server may reject or ignore it");
            }
        }
        return warnings;
    }
}
