package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The demo server's data: every {@code classpath:demo/*.json} (HL7 IG example resources plus the synthetic
 * {@code paw-*} files), indexed by resource type and id. Loaded once at startup through the pattern resolver so
 * it works from the jar as well as from an IDE. Resources are never modified after loading, which is why the
 * JSON nodes can be handed to every request. Missing {@code meta.lastUpdated} / {@code meta.versionId} are
 * filled with fixed values so {@code _lastUpdated} searches, vread and ETags behave like on a real server.
 */
@Component
@DemoEnabled
public class DemoDataStore {

    /** Given to resources whose example file has no lastUpdated: plausible, fixed, and after every HL7 example date. */
    public static final String DEFAULT_LAST_UPDATED = "2026-09-01T10:00:00Z";
    public static final String DEFAULT_VERSION = "1";
    static final String LOCATION_PATTERN = "classpath*:demo/*.json";

    private static final Logger log = LoggerFactory.getLogger(DemoDataStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, ObjectNode>> byType = new TreeMap<>();
    /** "Type/id" to the file it came from, in load order (for diagnostics and the data-integrity test). */
    private final Map<String, String> sources = new LinkedHashMap<>();

    public DemoDataStore() {
        this(LOCATION_PATTERN);
    }

    public DemoDataStore(String locationPattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list demo resources " + locationPattern + ": " + e.getMessage(), e);
        }
        Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
        for (Resource r : resources) {
            ObjectNode resource = read(r);
            String key = resource.get("resourceType").asText() + "/" + resource.get("id").asText();
            if (sources.containsKey(key)) {
                throw new IllegalStateException("duplicate demo resource " + key + " in " + r.getFilename() + " and " + sources.get(key));
            }
            sources.put(key, String.valueOf(r.getFilename()));
            byType.computeIfAbsent(resource.get("resourceType").asText(), t -> new LinkedHashMap<>()).put(resource.get("id").asText(), resource);
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("no demo resources found at " + locationPattern);
        }
        log.info("demo data: {} resources of {} types from {}", sources.size(), byType.size(), locationPattern);
    }

    private static ObjectNode read(Resource r) {
        JsonNode node;
        try (InputStream in = r.getInputStream()) {
            node = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse demo resource " + r.getFilename() + ": " + e.getMessage(), e);
        }
        if (!(node instanceof ObjectNode obj) || !obj.hasNonNull("resourceType") || !obj.hasNonNull("id")) {
            throw new IllegalStateException(r.getFilename() + " is not a FHIR resource with resourceType and id");
        }
        return withMeta(obj);
    }

    /** Rebuilds the resource with resourceType, id and a completed meta first, the rest in file order. */
    static ObjectNode withMeta(ObjectNode obj) {
        JsonNode existing = obj.path("meta");
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("versionId", existing.hasNonNull("versionId") ? existing.get("versionId").asText() : DEFAULT_VERSION);
        meta.put("lastUpdated", existing.hasNonNull("lastUpdated") ? existing.get("lastUpdated").asText() : DEFAULT_LAST_UPDATED);
        if (existing.isObject()) {
            existing.fields().forEachRemaining(e -> {
                if (!meta.has(e.getKey())) {
                    meta.set(e.getKey(), e.getValue());
                }
            });
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("resourceType", obj.get("resourceType"));
        out.set("id", obj.get("id"));
        out.set("meta", meta);
        obj.fields().forEachRemaining(e -> {
            if (!out.has(e.getKey())) {
                out.set(e.getKey(), e.getValue());
            }
        });
        return out;
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(byType.keySet());
    }

    public List<ObjectNode> all(String type) {
        Map<String, ObjectNode> m = byType.get(type);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    public Optional<ObjectNode> find(String type, String id) {
        Map<String, ObjectNode> m = byType.get(type);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(id));
    }

    /** Resolves a relative or absolute literal reference ({@code Patient/1}, {@code http://host/demo/fhir/Patient/1}). */
    public Optional<ObjectNode> resolve(String reference) {
        String key = referenceKey(reference);
        if (key == null) {
            return Optional.empty();
        }
        int slash = key.indexOf('/');
        return find(key.substring(0, slash), key.substring(slash + 1));
    }

    public List<ObjectNode> patients() {
        return all("Patient");
    }

    public int size() {
        return sources.size();
    }

    public Map<String, String> sources() {
        return Collections.unmodifiableMap(sources);
    }

    /**
     * The patient a resource belongs to (its own id for a Patient, else the patient / subject / beneficiary
     * reference; a Provenance belongs to the patient of its targets). Empty for patient-independent resources
     * such as Organization, Practitioner, Location or the formulary.
     */
    public Optional<String> patientOf(JsonNode resource) {
        String type = resource.path("resourceType").asText();
        if ("Patient".equals(type)) {
            return Optional.ofNullable(resource.path("id").asText(null));
        }
        for (String field : List.of("patient", "subject", "beneficiary")) {
            String key = referenceKey(resource.path(field).path("reference").asText(null));
            if (key != null && key.startsWith("Patient/")) {
                return Optional.of(key.substring("Patient/".length()));
            }
        }
        if ("Provenance".equals(type)) {
            for (JsonNode t : resource.path("target")) {
                Optional<ObjectNode> target = resolve(t.path("reference").asText(null));
                if (target.isPresent() && !"Provenance".equals(target.get().path("resourceType").asText())) {
                    Optional<String> patient = patientOf(target.get());
                    if (patient.isPresent()) {
                        return patient;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** {@code Type/id} of a literal reference (relative, absolute, or with a {@code _history} suffix); null for anything else. */
    public static String referenceKey(String reference) {
        if (reference == null || reference.isBlank() || reference.startsWith("#") || reference.startsWith("urn:")) {
            return null;
        }
        String path = reference.trim();
        int scheme = path.indexOf("://");
        if (scheme > 0) {
            path = path.substring(scheme + 3);
        }
        String[] parts = path.split("/");
        int n = parts.length;
        if (n >= 4 && "_history".equals(parts[n - 2])) {
            n -= 2;
        }
        if (n < 2) {
            return null;
        }
        String type = parts[n - 2];
        String id = parts[n - 1];
        if (type.isEmpty() || id.isEmpty() || !Character.isUpperCase(type.charAt(0))) {
            return null;
        }
        return type + "/" + id;
    }
}
