package com.thehiddenbrain.interop.patientaccess.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A small but coherent Patient Access API served through WireMock: the HL7 example resources from
 * {@code demo/*.json} (Patient1's claims, coverages, a PDex prior authorization approved and one denied,
 * encounters, provenance), a CapabilityStatement generated from the IG catalog, a SMART well-known
 * document, searches with the IG parameters, paging, _include/_revinclude, OperationOutcome errors and
 * a bearer-token guard under the {@code /secure} prefix. Faults switch on non-conformant behaviour so
 * tests can assert the negative outcomes of checks.
 */
final class FakePatientAccessApi implements ResponseDefinitionTransformerV2 {

    static final String NAME = "fake-patient-access-api";
    static final String TOKEN = "good-token";
    static final String OPEN_PREFIX = "/fhir";
    static final String SECURE_PREFIX = "/secure";

    /** Non-conformant behaviours a test can switch on. */
    enum Fault {
        /** The secure prefix answers everything without a token. */
        OPEN_AUTH,
        /** The use search parameter is ignored (claims come back for use=preauthorization). */
        IGNORE_USE_PARAM,
        /** ExplanationOfBenefit?patient= also returns another patient's claim. */
        LEAK_OTHER_PATIENT,
        /** 404s carry a text body instead of an OperationOutcome. */
        PLAIN_404,
        /** Searchset bundles never carry a next link (all results on one page). */
        NO_NEXT_LINK,
        /** The CapabilityStatement has no rest.security. */
        NO_SMART_SECURITY,
        /** smart-configuration lacks code_challenge_methods_supported. */
        NO_PKCE,
        /** Unknown parameters are accepted even under Prefer: handling=strict. */
        LENIENT_ONLY
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FHIR_JSON = "application/fhir+json";
    private static final Set<String> KNOWN_PARAMS = Set.of("_id", "_lastUpdated", "_count", "_offset", "_include", "_revinclude", "_format",
            "_total", "_sort", "patient", "beneficiary", "subject", "identifier", "name", "family", "given", "gender", "birthdate", "use",
            "type", "service-date", "service-start-date", "billable-period-start", "status", "class", "category", "code", "clinical-status",
            "intent", "date", "payor", "target");

    private final IgCatalog catalog;
    private final Map<String, Map<String, ObjectNode>> store = new LinkedHashMap<>();
    private final Set<Fault> faults = new HashSet<>();

    FakePatientAccessApi(IgCatalog catalog) {
        this.catalog = catalog;
        load();
    }

    @Override
    public String getName() {
        return NAME;
    }

    void fault(Fault... f) {
        Collections.addAll(faults, f);
    }

    /** The stored resource (mutable: tests break it on purpose), or null. */
    ObjectNode resource(String type, String id) {
        return store.getOrDefault(type, Map.of()).get(id);
    }

    /** Reloads the fixtures and clears the faults. */
    void load() {
        store.clear();
        faults.clear();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:demo/*.json")) {
                String file = r.getFilename() == null ? "" : r.getFilename();
                if (file.startsWith("pdex-Patient-") || file.startsWith("pdex-Coverage-")) {
                    continue; // PDex's own patient and coverage would compete with the C4BB Patient1 data
                }
                try (InputStream in = r.getInputStream()) {
                    ObjectNode node = (ObjectNode) MAPPER.readTree(in);
                    node.remove("text");
                    rewriteReferences(node, "Patient/1", "Patient/Patient1");
                    ((ObjectNode) node.withObject("/meta")).put("versionId", "1");
                    put(node);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ObjectNode provenance = resource("Provenance", "1000001");
        ((ObjectNode) provenance.path("target").get(0)).put("reference", "Patient/Patient1");
        put(deniedPriorAuth(resource("ExplanationOfBenefit", "PDexPriorAuth1")));
    }

    private void put(ObjectNode resource) {
        store.computeIfAbsent(resource.path("resourceType").asText(), k -> new LinkedHashMap<>()).put(resource.path("id").asText(), resource);
    }

    /** A copy of the approved example turned into a denial with an X12 CARC denial reason. */
    static ObjectNode deniedPriorAuth(ObjectNode approved) {
        ObjectNode denied = approved.deepCopy();
        denied.put("id", "PDexPriorAuthDenied");
        denied.put("outcome", "complete");
        ((ObjectNode) denied.path("identifier").get(0)).put("value", "PA-DENIED-0001");
        ((ObjectNode) denied.path("meta")).put("lastUpdated", "2024-07-24T09:14:11+00:00");
        ObjectNode adjudication = (ObjectNode) denied.path("item").get(0).path("adjudication").get(0);
        for (JsonNode ext : adjudication.path("extension")) {
            for (JsonNode inner : ext.path("extension")) {
                if (inner.path("url").asText().endsWith("extension-reviewActionCode")) {
                    ObjectNode coding = (ObjectNode) inner.path("valueCodeableConcept").path("coding").get(0);
                    coding.put("code", "A3").put("display", "Not certified");
                }
            }
        }
        ObjectNode reason = MAPPER.createObjectNode();
        reason.putObject("category").putArray("coding").addObject()
                .put("system", "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PDexAdjudicationDiscriminator").put("code", "denialreason");
        reason.putObject("reason").putArray("coding").addObject()
                .put("system", "https://x12.org/codes/claim-adjustment-reason-codes").put("code", "197")
                .put("display", "Precertification/authorization/notification/pre-treatment absent");
        ((ArrayNode) denied.path("item").get(0).path("adjudication")).add(reason);
        denied.remove("total");
        return denied;
    }

    static void rewriteReferences(JsonNode node, String from, String to) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            if (o.has("reference") && from.equals(o.get("reference").asText())) {
                o.put("reference", to);
            }
            o.fields().forEachRemaining(e -> rewriteReferences(e.getValue(), from, to));
        } else if (node.isArray()) {
            node.forEach(n -> rewriteReferences(n, from, to));
        }
    }

    // ------------------------------------------------------------------ request handling

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Request req = serveEvent.getRequest();
        String url = req.getUrl();
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        Map<String, List<String>> query = parseQuery(url.contains("?") ? url.substring(url.indexOf('?') + 1) : "");
        String serverBase = req.getAbsoluteUrl().substring(0, req.getAbsoluteUrl().length() - url.length());
        String prefix;
        boolean secured;
        if (path.startsWith(SECURE_PREFIX + "/")) {
            prefix = SECURE_PREFIX;
            secured = true;
        } else if (path.startsWith(OPEN_PREFIX + "/")) {
            prefix = OPEN_PREFIX;
            secured = false;
        } else {
            return outcome(404, "not-found", "unknown path " + path);
        }
        String base = serverBase + prefix;
        String rest = path.substring(prefix.length());
        if (rest.equals("/metadata")) {
            return json(200, capabilityStatement(base), FHIR_JSON);
        }
        if (rest.equals("/.well-known/smart-configuration")) {
            return json(200, smartConfiguration(base), "application/json");
        }
        if (secured && !faults.contains(Fault.OPEN_AUTH) && !("Bearer " + TOKEN).equals(req.getHeader("Authorization"))) {
            return ResponseDefinitionBuilder.like(outcome(401, "login", "a valid bearer token is required"))
                    .withHeader("WWW-Authenticate", "Bearer realm=\"paw\", error=\"invalid_token\"").build();
        }
        String[] segments = rest.substring(1).split("/");
        String type = segments[0];
        if (!catalog.resources().containsKey(type) || type.equals("InsurancePlan") || type.equals("Basic") || type.equals("MedicationKnowledge")) {
            return faults.contains(Fault.PLAIN_404) ? text(404, "No such resource type: " + type)
                    : outcome(404, "not-supported", "resource type " + type + " is not part of this API");
        }
        if (segments.length == 1) {
            return search(req, base, type, query);
        }
        ObjectNode found = resource(type, segments[1]);
        if (found == null) {
            return faults.contains(Fault.PLAIN_404) ? text(404, "Not found") : outcome(404, "not-found", type + "/" + segments[1] + " is not known");
        }
        if (segments.length == 4 && segments[2].equals("_history")) {
            if (!segments[3].equals(found.path("meta").path("versionId").asText())) {
                return outcome(404, "not-found", "no version " + segments[3] + " of " + type + "/" + segments[1]);
            }
        }
        return json(200, found, FHIR_JSON);
    }

    private ResponseDefinition search(Request req, String base, String type, Map<String, List<String>> query) {
        boolean strict = String.valueOf(req.getHeader("Prefer")).contains("handling=strict") && !faults.contains(Fault.LENIENT_ONLY);
        for (String p : query.keySet()) {
            String name = p.contains(":") ? p.substring(0, p.indexOf(':')) : p;
            if (strict && !KNOWN_PARAMS.contains(name)) {
                return outcome(400, "invalid", "unknown search parameter '" + name + "' (handling=strict)");
            }
        }
        for (String v : query.getOrDefault("_lastUpdated", List.of())) {
            if (!stripPrefix(v).matches("\\d{4}.*")) {
                return outcome(400, "invalid", "_lastUpdated value '" + v + "' is not a date");
            }
        }
        List<ObjectNode> matches = new ArrayList<>();
        for (ObjectNode r : store.getOrDefault(type, Map.of()).values()) {
            if (matches(r, query)) {
                matches.add(r);
            }
        }
        if (faults.contains(Fault.LEAK_OTHER_PATIENT) && type.equals("ExplanationOfBenefit") && query.containsKey("patient")) {
            matches.add(resource("ExplanationOfBenefit", "EOBInpatient1"));
        }
        int count = Integer.parseInt(query.getOrDefault("_count", List.of("50")).get(0));
        int offset = Integer.parseInt(query.getOrDefault("_offset", List.of("0")).get(0));
        List<ObjectNode> page = matches.subList(Math.min(offset, matches.size()), Math.min(offset + count, matches.size()));

        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("resourceType", "Bundle").put("id", "search-" + System.nanoTime()).put("type", "searchset").put("total", matches.size());
        ArrayNode links = bundle.putArray("link");
        String self = base + "/" + type + (query.isEmpty() ? "" : "?" + rebuild(query, null));
        links.addObject().put("relation", "self").put("url", self);
        if (!faults.contains(Fault.NO_NEXT_LINK) && offset + count < matches.size()) {
            links.addObject().put("relation", "next").put("url", base + "/" + type + "?" + rebuild(query, offset + count));
        }
        ArrayNode entries = bundle.putArray("entry");
        for (ObjectNode r : page) {
            entry(entries, base, r, "match");
        }
        Set<String> seen = new HashSet<>();
        for (String include : query.getOrDefault("_include", List.of())) {
            for (ObjectNode r : page) {
                for (String ref : references(r, include)) {
                    ObjectNode target = byReference(ref);
                    if (target != null && seen.add(ref)) {
                        entry(entries, base, target, "include");
                    }
                }
            }
        }
        if (query.getOrDefault("_revinclude", List.of()).contains("Provenance:target")) {
            for (ObjectNode prov : store.getOrDefault("Provenance", Map.of()).values()) {
                for (JsonNode t : prov.path("target")) {
                    for (ObjectNode r : page) {
                        String key = r.path("resourceType").asText() + "/" + r.path("id").asText();
                        if (key.equals(t.path("reference").asText()) && seen.add("Provenance/" + prov.path("id").asText())) {
                            entry(entries, base, prov, "include");
                        }
                    }
                }
            }
        }
        return json(200, bundle, FHIR_JSON);
    }

    private boolean matches(ObjectNode r, Map<String, List<String>> query) {
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            String param = e.getKey().contains(":") ? e.getKey().substring(0, e.getKey().indexOf(':')) : e.getKey();
            for (String value : e.getValue()) {
                if (!matchesOne(r, param, value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean matchesOne(ObjectNode r, String param, String value) {
        switch (param) {
            case "_id":
                return List.of(value.split(",")).contains(r.path("id").asText());
            case "patient":
            case "beneficiary":
            case "subject":
                return referenceMatches(r.get("patient"), value) || referenceMatches(r.get("beneficiary"), value) || referenceMatches(r.get("subject"), value);
            case "payor":
                for (JsonNode p : r.path("payor")) {
                    if (referenceMatches(p, value)) {
                        return true;
                    }
                }
                return false;
            case "identifier":
                for (JsonNode i : r.path("identifier")) {
                    String token = i.path("system").asText("") + "|" + i.path("value").asText("");
                    if (token.equals(value) || i.path("value").asText("").equals(value)) {
                        return true;
                    }
                }
                return false;
            case "name":
            case "family":
            case "given":
                for (JsonNode n : r.path("name")) {
                    String hay = (param.equals("given") ? "" : n.path("family").asText("")) + " "
                            + (param.equals("family") ? "" : n.path("given").toString());
                    if (hay.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
                return false;
            case "gender":
            case "status":
            case "intent":
                return value.equals(r.path(param).asText());
            case "use":
                return faults.contains(Fault.IGNORE_USE_PARAM) || value.equals(r.path("use").asText());
            case "birthdate":
                return dateMatches(r.path("birthDate").asText(null), value);
            case "_lastUpdated":
                return dateMatches(r.path("meta").path("lastUpdated").asText(null), value);
            case "service-date":
            case "service-start-date":
            case "billable-period-start":
                return dateMatches(r.path("billablePeriod").path("start").asText(null), value);
            case "date":
                return dateMatches(firstDate(r), value);
            case "type":
            case "class":
            case "category":
            case "code":
                return tokenMatches(r.get(param), value);
            case "clinical-status":
                return tokenMatches(r.get("clinicalStatus"), value);
            default:
                return true; // control parameters and unknown ones (lenient handling)
        }
    }

    static boolean referenceMatches(JsonNode ref, String value) {
        if (ref == null) {
            return false;
        }
        String r = ref.path("reference").asText("");
        return r.equals(value) || r.equals("Patient/" + value) || r.endsWith("/" + value);
    }

    static boolean tokenMatches(JsonNode element, String value) {
        if (element == null) {
            return false;
        }
        if (element.isArray()) {
            for (JsonNode e : element) {
                if (tokenMatches(e, value)) {
                    return true;
                }
            }
            return false;
        }
        String system = value.contains("|") ? value.substring(0, value.indexOf('|')) : null;
        String code = value.contains("|") ? value.substring(value.indexOf('|') + 1) : value;
        if (element.has("coding")) {
            for (JsonNode c : element.path("coding")) {
                if (code.equals(c.path("code").asText()) && (system == null || system.equals(c.path("system").asText()))) {
                    return true;
                }
            }
            return false;
        }
        return code.equals(element.path("code").asText()) && (system == null || system.equals(element.path("system").asText()));
    }

    static String firstDate(JsonNode r) {
        for (String f : List.of("effectiveDateTime", "performedDateTime", "occurrenceDateTime", "date", "onsetDateTime", "issued")) {
            if (r.hasNonNull(f)) {
                return r.get(f).asText();
            }
        }
        for (String f : List.of("period", "effectivePeriod", "performedPeriod")) {
            if (r.path(f).hasNonNull("start")) {
                return r.path(f).get("start").asText();
            }
        }
        return null;
    }

    static boolean dateMatches(String actual, String query) {
        if (actual == null) {
            return false;
        }
        String prefix = query.length() > 2 && Character.isLetter(query.charAt(0)) ? query.substring(0, 2) : "eq";
        String wanted = stripPrefix(query);
        String have = actual.length() > wanted.length() ? actual.substring(0, wanted.length()) : actual;
        int cmp = have.compareTo(wanted);
        return switch (prefix) {
            case "ge" -> cmp >= 0;
            case "gt" -> cmp > 0;
            case "le" -> cmp <= 0;
            case "lt" -> cmp < 0;
            case "ne" -> cmp != 0;
            default -> cmp == 0;
        };
    }

    static String stripPrefix(String query) {
        return query.length() > 2 && Character.isLetter(query.charAt(0)) && Character.isLetter(query.charAt(1)) ? query.substring(2) : query;
    }

    /** References an _include value selects from a resource ("Type:*" follows every direct reference). */
    static List<String> references(ObjectNode r, String include) {
        String field = include.contains(":") ? include.substring(include.indexOf(':') + 1) : "*";
        List<String> out = new ArrayList<>();
        Map<String, List<String>> paths = Map.of(
                "patient", List.of("patient"), "provider", List.of("provider"), "insurer", List.of("insurer"), "payee", List.of("payee.party"),
                "coverage", List.of("insurance[].coverage"), "care-team", List.of("careTeam[].provider"), "payor", List.of("payor[]"));
        List<String> selected = field.equals("*") ? paths.values().stream().flatMap(List::stream).toList() : paths.getOrDefault(field, List.of());
        for (String path : selected) {
            collect(r, path.split("\\."), 0, out);
        }
        return out;
    }

    private static void collect(JsonNode node, String[] path, int i, List<String> out) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (i == path.length) {
            if (node.has("reference")) {
                out.add(node.get("reference").asText());
            }
            return;
        }
        String seg = path[i];
        if (seg.endsWith("[]")) {
            for (JsonNode n : node.path(seg.substring(0, seg.length() - 2))) {
                collect(n, path, i + 1, out);
            }
        } else {
            collect(node.get(seg), path, i + 1, out);
        }
    }

    private ObjectNode byReference(String ref) {
        String[] parts = ref.split("/");
        return parts.length == 2 ? resource(parts[0], parts[1]) : null;
    }

    private static void entry(ArrayNode entries, String base, ObjectNode r, String mode) {
        ObjectNode e = entries.addObject();
        e.put("fullUrl", base + "/" + r.path("resourceType").asText() + "/" + r.path("id").asText());
        e.set("resource", r);
        e.putObject("search").put("mode", mode);
    }

    // ------------------------------------------------------------------ discovery documents

    private ObjectNode capabilityStatement(String base) {
        ObjectNode cs = MAPPER.createObjectNode();
        cs.put("resourceType", "CapabilityStatement").put("id", "paw-fake").put("status", "active").put("date", "2026-01-01")
                .put("kind", "instance").put("fhirVersion", "4.0.1");
        cs.putObject("software").put("name", "fake-patient-access-api").put("version", "1");
        cs.putArray("format").add("json").add("application/fhir+json");
        cs.putArray("implementationGuide").add("http://hl7.org/fhir/us/carin-bb/ImplementationGuide/hl7.fhir.us.carin-bb|2.1.0")
                .add("http://hl7.org/fhir/us/davinci-pdex/ImplementationGuide/hl7.fhir.us.davinci-pdex|2.1.0")
                .add("http://hl7.org/fhir/us/core/ImplementationGuide/hl7.fhir.us.core|6.1.0");
        ObjectNode rest = cs.putArray("rest").addObject();
        rest.put("mode", "server");
        if (!faults.contains(Fault.NO_SMART_SECURITY)) {
            ObjectNode security = rest.putObject("security");
            security.putArray("service").addObject().putArray("coding").addObject()
                    .put("system", "http://terminology.hl7.org/CodeSystem/restful-security-service").put("code", "SMART-on-FHIR");
            ObjectNode ext = security.putArray("extension").addObject();
            ext.put("url", "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
            ArrayNode inner = ext.putArray("extension");
            inner.addObject().put("url", "authorize").put("valueUri", base + "/oauth/authorize");
            inner.addObject().put("url", "token").put("valueUri", base + "/oauth/token");
        }
        ArrayNode resources = rest.putArray("resource");
        for (IgCatalog.ResourceSpec spec : catalog.resources().values()) {
            if (spec.type().equals("InsurancePlan") || spec.type().equals("Basic") || spec.type().equals("MedicationKnowledge")) {
                continue;
            }
            ObjectNode r = resources.addObject();
            r.put("type", spec.type());
            ArrayNode profiles = r.putArray("supportedProfile");
            spec.profiles().forEach(p -> profiles.add(p.url()));
            ArrayNode interactions = r.putArray("interaction");
            for (String code : List.of("read", "vread", "search-type")) {
                interactions.addObject().put("code", code);
            }
            ArrayNode params = r.putArray("searchParam");
            for (IgCatalog.SearchParamSpec p : spec.searchParams()) {
                params.addObject().put("name", p.name()).put("type", p.type());
            }
            r.putArray("searchRevInclude").add("Provenance:target");
        }
        return cs;
    }

    private ObjectNode smartConfiguration(String base) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("issuer", base).put("authorization_endpoint", base + "/oauth/authorize").put("token_endpoint", base + "/oauth/token")
                .put("jwks_uri", base + "/oauth/jwks");
        s.putArray("grant_types_supported").add("authorization_code").add("refresh_token").add("client_credentials");
        s.putArray("response_types_supported").add("code");
        if (!faults.contains(Fault.NO_PKCE)) {
            s.putArray("code_challenge_methods_supported").add("S256");
        }
        s.putArray("token_endpoint_auth_methods_supported").add("client_secret_basic").add("private_key_jwt");
        s.putArray("scopes_supported").add("openid").add("fhirUser").add("offline_access").add("launch/patient").add("patient/*.read").add("patient/*.rs");
        s.putArray("capabilities").add("launch-standalone").add("client-public").add("client-confidential-symmetric")
                .add("context-standalone-patient").add("permission-patient").add("permission-offline").add("sso-openid-connect").add("permission-v2");
        return s;
    }

    // ------------------------------------------------------------------ plumbing

    static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (query.isBlank()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    /** The query string again, with _offset replaced when {@code offset} is given. */
    static String rebuild(Map<String, List<String>> query, Integer offset) {
        StringBuilder sb = new StringBuilder();
        query.forEach((k, values) -> {
            if (k.equals("_offset") && offset != null) {
                return;
            }
            for (String v : values) {
                sb.append(sb.length() == 0 ? "" : "&").append(k).append('=').append(v.replace("|", "%7C").replace(" ", "%20"));
            }
        });
        if (offset != null) {
            sb.append(sb.length() == 0 ? "" : "&").append("_offset=").append(offset);
        }
        return sb.toString();
    }

    private static ResponseDefinition json(int status, JsonNode body, String contentType) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", contentType).withBody(body.toString()).build();
    }

    private static ResponseDefinition text(int status, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", "text/plain").withBody(body).build();
    }

    private static ResponseDefinition outcome(int status, String code, String diagnostics) {
        ObjectNode oo = MAPPER.createObjectNode();
        oo.put("resourceType", "OperationOutcome");
        oo.putArray("issue").addObject().put("severity", "error").put("code", code).put("diagnostics", diagnostics);
        return json(status, oo, FHIR_JSON);
    }
}
