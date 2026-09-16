package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The demo FHIR R4 server at {@code /demo/fhir}: CapabilityStatement and SMART discovery (open), then read,
 * vread and search on the demo data behind a bearer token from {@code /demo/auth}. Every response is
 * {@code application/fhir+json}; Bundle links and fullUrls are absolute to the URL the request came in on,
 * so the workbench can point at itself through any host name or reverse proxy.
 */
@RestController
@RequestMapping(DemoFhirController.BASE_PATH)
@DemoEnabled
public class DemoFhirController {

    public static final String BASE_PATH = "/demo/fhir";
    public static final MediaType FHIR_JSON = new MediaType("application", "fhir+json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final DemoDataStore store;
    private final DemoSearchEngine engine;
    private final DemoCapabilityStatement capability;
    private final DemoAuthService auth;
    private final WorkbenchProperties.Demo demo;
    private final Clock clock;
    private final String version;

    public DemoFhirController(DemoDataStore store, DemoSearchEngine engine, DemoCapabilityStatement capability, DemoAuthService auth,
                              WorkbenchProperties properties, Clock clock, Optional<BuildProperties> build) {
        this.store = store;
        this.engine = engine;
        this.capability = capability;
        this.auth = auth;
        this.demo = properties.demo();
        this.clock = clock;
        this.version = build.map(BuildProperties::getVersion).orElse("dev");
    }

    @GetMapping("/metadata")
    public ResponseEntity<JsonNode> metadata(HttpServletRequest request) {
        return fhir(capability.build(fhirBase(request), authBase(request), version));
    }

    /** SMART App Launch discovery document (JSON, not a FHIR resource). */
    @GetMapping(value = "/.well-known/smart-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> smartConfiguration(HttpServletRequest request) {
        String authBase = authBase(request);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", authBase);
        doc.put("authorization_endpoint", authBase + "/authorize");
        doc.put("token_endpoint", authBase + "/token");
        doc.put("jwks_uri", authBase + "/jwks");
        doc.put("registration_endpoint", authBase + "/register");
        doc.put("capabilities", List.of("launch-standalone", "client-public", "client-confidential-symmetric", "client-confidential-asymmetric",
                "sso-openid-connect", "context-standalone-patient", "permission-offline", "permission-patient", "permission-v1", "permission-v2"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("grant_types_supported", List.of("authorization_code", "client_credentials", "refresh_token"));
        List<String> scopes = new ArrayList<>(List.of("openid", "fhirUser", "offline_access", "launch/patient", "patient/*.read", "patient/*.rs", "system/*.rs"));
        for (String type : engine.supportedTypes()) {
            scopes.add("patient/" + type + ".rs");
        }
        doc.put("scopes_supported", scopes);
        doc.put("response_types_supported", List.of("code"));
        doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post", "private_key_jwt"));
        return doc;
    }

    @GetMapping("/{type}")
    public ResponseEntity<JsonNode> search(@PathVariable String type, HttpServletRequest request) {
        String scope = authorize(request);
        requireType(type);
        String prefer = request.getHeader("Prefer");
        boolean strict = prefer != null && prefer.toLowerCase().replace(" ", "").contains("handling=strict");
        DemoSearchEngine.Page page = engine.search(type, request.getParameterMap(), strict, scope);
        return fhir(bundle(type, page, request));
    }

    @GetMapping("/{type}/{id}")
    public ResponseEntity<JsonNode> read(@PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        String scope = authorize(request);
        requireType(type);
        ObjectNode resource = store.find(type, id).orElseThrow(() -> DemoFhirException.notFound(type + "/" + id + " is not known to this server"));
        requireVisible(resource, scope);
        return withVersionHeaders(resource);
    }

    /** Only the single version "1" exists; any other version id is unknown. */
    @GetMapping("/{type}/{id}/_history/{vid}")
    public ResponseEntity<JsonNode> vread(@PathVariable String type, @PathVariable String id, @PathVariable String vid, HttpServletRequest request) {
        String scope = authorize(request);
        requireType(type);
        ObjectNode resource = store.find(type, id).orElseThrow(() -> DemoFhirException.notFound(type + "/" + id + " is not known to this server"));
        requireVisible(resource, scope);
        if (!vid.equals(resource.path("meta").path("versionId").asText())) {
            throw DemoFhirException.notFound(type + "/" + id + " has no version " + vid);
        }
        return withVersionHeaders(resource);
    }

    @ExceptionHandler(DemoFhirException.class)
    public ResponseEntity<JsonNode> fhirError(DemoFhirException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).contentType(FHIR_JSON);
        e.headers().forEach(b::header);
        return b.body(e.outcome());
    }

    // ------------------------------------------------------------------ security

    /**
     * Returns the patient id a patient-bound token is limited to, or null for unrestricted access. Without
     * {@code paw.demo.require-token} an absent token is fine, but a token that is sent must still be valid.
     */
    private String authorize(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (!demo.requireToken()) {
                return null;
            }
            throw DemoFhirException.login();
        }
        DemoAuthService.Token token = auth.authenticate(header.substring(7).trim()).orElseThrow(DemoFhirException::login);
        return token.patientId();
    }

    private void requireVisible(ObjectNode resource, String scope) {
        if (!engine.visible(resource, scope)) {
            throw DemoFhirException.forbidden("the token is bound to Patient/" + scope + " and cannot read "
                    + resource.path("resourceType").asText() + "/" + resource.path("id").asText());
        }
    }

    private void requireType(String type) {
        if (!engine.supports(type)) {
            throw DemoFhirException.notFound("unknown resource type '" + type + "'");
        }
    }

    // ------------------------------------------------------------------ bundles

    private ObjectNode bundle(String type, DemoSearchEngine.Page page, HttpServletRequest request) {
        String fhirBase = fhirBase(request);
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("id", Ids.next(16));
        bundle.putObject("meta").put("lastUpdated", clock.instant().toString());
        bundle.put("type", "searchset");
        bundle.put("total", page.total());
        ArrayNode links = bundle.putArray("link");
        links.addObject().put("relation", "self").put("url", pageUrl(fhirBase, type, request, page.page()));
        if (page.hasNext()) {
            links.addObject().put("relation", "next").put("url", pageUrl(fhirBase, type, request, page.page() + 1));
        }
        if (page.hasPrevious()) {
            links.addObject().put("relation", "previous").put("url", pageUrl(fhirBase, type, request, page.page() - 1));
        }
        ArrayNode entries = bundle.putArray("entry");
        for (ObjectNode r : page.matches()) {
            entry(entries, fhirBase, r, "match");
        }
        for (ObjectNode r : page.included()) {
            entry(entries, fhirBase, r, "include");
        }
        return bundle;
    }

    private static void entry(ArrayNode entries, String fhirBase, ObjectNode resource, String mode) {
        ObjectNode e = entries.addObject();
        e.put("fullUrl", fhirBase + "/" + resource.path("resourceType").asText() + "/" + resource.path("id").asText());
        e.set("resource", resource);
        e.putObject("search").put("mode", mode);
    }

    /** The request's own parameters (order kept, {@code page} replaced) re-encoded the FHIR-friendly way. */
    private static String pageUrl(String fhirBase, String type, HttpServletRequest request, int page) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!"page".equals(k)) {
                params.put(k, new ArrayList<>(List.of(v)));
            }
        });
        if (page > 1 || request.getParameterMap().containsKey("page")) {
            params.add("page", String.valueOf(page));
        }
        String query = UrlBuilder.query(params);
        return fhirBase + "/" + type + (query.isEmpty() ? "" : "?" + query);
    }

    private ResponseEntity<JsonNode> withVersionHeaders(ObjectNode resource) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(FHIR_JSON)
                .eTag("W/\"" + resource.path("meta").path("versionId").asText("1") + "\"");
        try {
            b.header(HttpHeaders.LAST_MODIFIED, HTTP_DATE.format(Instant.parse(resource.path("meta").path("lastUpdated").asText())));
        } catch (RuntimeException ignored) {
            // lastUpdated of an HL7 example may carry an offset Instant.parse rejects; the header is optional
        }
        return b.body(resource);
    }

    private static ResponseEntity<JsonNode> fhir(JsonNode body) {
        return ResponseEntity.ok().contentType(FHIR_JSON).body(body);
    }

    static String fhirBase(HttpServletRequest request) {
        return DemoAuthController.serverBase(request) + BASE_PATH;
    }

    static String authBase(HttpServletRequest request) {
        return DemoAuthController.serverBase(request) + DemoAuthController.BASE_PATH;
    }
}
