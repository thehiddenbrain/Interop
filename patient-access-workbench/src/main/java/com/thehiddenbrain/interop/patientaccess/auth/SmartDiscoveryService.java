package com.thehiddenbrain.interop.patientaccess.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpExecutor;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@code /.well-known/smart-configuration} and, as a fallback, the OAuth URIs extension of the
 * CapabilityStatement. Results are cached for a few minutes per environment version.
 */
@Service
public class SmartDiscoveryService {

    public static final String OAUTH_URIS_EXTENSION = "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final HttpExecutor http;
    private final EnvironmentService environments;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Discovery discovery, Instant at, long version) {
    }

    /** Raw discovery results with the well-known document and the security section of the CapabilityStatement. */
    public record Discovery(JsonNode smartConfiguration, String smartConfigurationUrl, Integer smartConfigurationStatus,
                            String smartRequestId, JsonNode capabilityStatement, Integer metadataStatus, String metadataRequestId,
                            long metadataMs, OAuthEndpoints endpoints, List<String> notes) {
    }

    public SmartDiscoveryService(HttpExecutor http, EnvironmentService environments, Clock clock) {
        this.http = http;
        this.environments = environments;
        this.clock = clock;
    }

    public Discovery discover(Environment env, boolean refresh, String correlationId) {
        Cached c = cache.get(env.id());
        if (!refresh && c != null && c.version() == env.version() && c.at().plus(TTL).isAfter(clock.instant())) {
            return c.discovery();
        }
        Discovery d = fetch(env, correlationId);
        cache.put(env.id(), new Cached(d, clock.instant(), env.version()));
        return d;
    }

    public void evict(String environmentId) {
        cache.remove(environmentId);
    }

    /** Endpoints to use: configured values win, discovered ones fill the gaps. */
    public OAuthEndpoints endpointsFor(Environment env, String correlationId) {
        AuthConfig a = env.auth();
        OAuthEndpoints discovered = null;
        if (a.discoverEndpoints() && (isBlank(a.tokenEndpoint()) || isBlank(a.authorizationEndpoint()))) {
            try {
                discovered = discover(env, false, correlationId).endpoints();
            } catch (WorkbenchException e) {
                discovered = null;
            }
        }
        String authorize = !isBlank(a.authorizationEndpoint()) ? a.authorizationEndpoint()
                : discovered != null ? discovered.authorizationEndpoint() : null;
        String token = !isBlank(a.tokenEndpoint()) ? a.tokenEndpoint() : discovered != null ? discovered.tokenEndpoint() : null;
        if (discovered == null) {
            return new OAuthEndpoints(authorize, token, null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), "configured");
        }
        return new OAuthEndpoints(authorize, token, discovered.issuer(), discovered.jwksUri(), discovered.registrationEndpoint(),
                discovered.introspectionEndpoint(), discovered.revocationEndpoint(), discovered.capabilities(), discovered.scopesSupported(),
                discovered.codeChallengeMethods(), discovered.grantTypesSupported(), discovered.tokenEndpointAuthMethods(),
                (!isBlank(a.tokenEndpoint()) ? "configured+" : "") + discovered.source());
    }

    private Discovery fetch(Environment env, String correlationId) {
        List<String> notes = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, application/fhir+json");
        headers.putAll(environments.resolvedHeaders(env));
        var secretNames = environments.secretHeaderNames(env);

        String wellKnownUrl = env.baseUrl() + "/.well-known/smart-configuration";
        JsonNode smart = null;
        Integer smartStatus = null;
        String smartRequestId = null;
        try {
            HttpResult r = http.execute(env, HttpExecutor.Call.get(wellKnownUrl, headers, "discovery", correlationId, secretNames));
            smartStatus = r.status();
            smartRequestId = r.requestId();
            if (r.ok() && r.json() != null && r.json().isObject()) {
                smart = r.json();
            } else {
                notes.add("smart-configuration: HTTP " + r.status() + (r.isJson() ? "" : " (not JSON)"));
            }
        } catch (WorkbenchException e) {
            notes.add("smart-configuration: " + e.getMessage());
        }

        Map<String, String> fhirHeaders = new LinkedHashMap<>(headers);
        fhirHeaders.put("Accept", env.fhir().acceptHeaderOrDefault());
        JsonNode capability = null;
        Integer metadataStatus = null;
        String metadataRequestId = null;
        long metadataMs = 0;
        try {
            HttpResult r = http.execute(env, HttpExecutor.Call.get(env.baseUrl() + "/metadata", fhirHeaders, "discovery", correlationId, secretNames));
            metadataStatus = r.status();
            metadataRequestId = r.requestId();
            metadataMs = r.durationMs();
            if (r.ok() && "CapabilityStatement".equals(r.resourceType())) {
                capability = r.json();
            } else {
                notes.add("metadata: HTTP " + r.status() + (r.resourceType() == null ? " (no CapabilityStatement)" : " " + r.resourceType()));
            }
        } catch (WorkbenchException e) {
            notes.add("metadata: " + e.getMessage());
        }

        OAuthEndpoints endpoints = endpointsFrom(smart, capability, notes);
        return new Discovery(smart, wellKnownUrl, smartStatus, smartRequestId, capability, metadataStatus, metadataRequestId, metadataMs, endpoints, notes);
    }

    static OAuthEndpoints endpointsFrom(JsonNode smart, JsonNode capability, List<String> notes) {
        String authorize = null;
        String token = null;
        String issuer = null;
        String jwks = null;
        String registration = null;
        String introspection = null;
        String revocation = null;
        List<String> capabilities = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        List<String> pkce = new ArrayList<>();
        List<String> grants = new ArrayList<>();
        List<String> authMethods = new ArrayList<>();
        String source = "none";
        if (smart != null) {
            authorize = text(smart, "authorization_endpoint");
            token = text(smart, "token_endpoint");
            issuer = text(smart, "issuer");
            jwks = text(smart, "jwks_uri");
            registration = text(smart, "registration_endpoint");
            introspection = text(smart, "introspection_endpoint");
            revocation = text(smart, "revocation_endpoint");
            capabilities = strings(smart, "capabilities");
            scopes = strings(smart, "scopes_supported");
            pkce = strings(smart, "code_challenge_methods_supported");
            grants = strings(smart, "grant_types_supported");
            authMethods = strings(smart, "token_endpoint_auth_methods_supported");
            source = "smart-configuration";
        }
        if ((authorize == null || token == null) && capability != null) {
            for (JsonNode rest : capability.path("rest")) {
                for (JsonNode ext : rest.path("security").path("extension")) {
                    if (OAUTH_URIS_EXTENSION.equals(ext.path("url").asText())) {
                        for (JsonNode inner : ext.path("extension")) {
                            String url = inner.path("url").asText();
                            String value = inner.path("valueUri").asText(null);
                            if (value == null) {
                                value = inner.path("valueUrl").asText(null);
                            }
                            if ("authorize".equals(url) && authorize == null) {
                                authorize = value;
                            } else if ("token".equals(url) && token == null) {
                                token = value;
                            } else if ("register".equals(url) && registration == null) {
                                registration = value;
                            } else if ("introspect".equals(url) && introspection == null) {
                                introspection = value;
                            } else if ("revoke".equals(url) && revocation == null) {
                                revocation = value;
                            }
                        }
                        source = smart == null ? "capabilitystatement" : source + "+capabilitystatement";
                    }
                }
            }
        }
        if (authorize == null && token == null) {
            notes.add("no OAuth endpoints found in smart-configuration or CapabilityStatement.rest.security");
        }
        return new OAuthEndpoints(authorize, token, issuer, jwks, registration, introspection, revocation, capabilities, scopes,
                pkce, grants, authMethods, source);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static List<String> strings(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : n.path(field)) {
            out.add(v.asText());
        }
        return out;
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static WorkbenchException missingTokenEndpoint(Environment env) {
        return new WorkbenchException(ErrorCode.AUTH_FAILED, "no token endpoint for environment '" + env.name()
                + "': set auth.tokenEndpoint or make sure " + env.baseUrl() + "/.well-known/smart-configuration is served");
    }
}
