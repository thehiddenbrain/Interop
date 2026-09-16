package com.thehiddenbrain.interop.patientaccess.environment;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.AccessToken;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.auth.TokenService;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The "Test connection" button: metadata, SMART discovery, token, and one authenticated call. */
@Service
public class EnvironmentProbe {

    private final SmartDiscoveryService discovery;
    private final TokenService tokens;
    private final FhirGateway gateway;

    public record Step(String name, String status, String message, Integer httpStatus, Long durationMs, String requestId, Map<String, Object> details) {
    }

    public record Result(String environmentId, String correlationId, boolean ok, List<Step> steps) {
    }

    public EnvironmentProbe(SmartDiscoveryService discovery, TokenService tokens, FhirGateway gateway) {
        this.discovery = discovery;
        this.tokens = tokens;
        this.gateway = gateway;
    }

    public Result probe(Environment env) {
        String correlation = "probe-" + Ids.next(8);
        List<Step> steps = new ArrayList<>();
        boolean ok = true;

        SmartDiscoveryService.Discovery d;
        try {
            d = discovery.discover(env, true, correlation);
        } catch (WorkbenchException e) {
            steps.add(new Step("metadata", "FAIL", e.getMessage(), null, null, null, Map.of()));
            return new Result(env.id(), correlation, false, steps);
        }
        JsonNode cs = d.capabilityStatement();
        if (cs != null) {
            steps.add(new Step("metadata", "PASS", "CapabilityStatement received", d.metadataStatus(), d.metadataMs(), d.metadataRequestId(),
                    capabilityDetails(cs)));
        } else {
            ok = false;
            steps.add(new Step("metadata", "FAIL", "no CapabilityStatement at " + env.baseUrl() + "/metadata: " + String.join("; ", d.notes()),
                    d.metadataStatus(), d.metadataMs(), d.metadataRequestId(), Map.of()));
        }
        if (d.smartConfiguration() != null) {
            steps.add(new Step("smart-configuration", "PASS", "SMART discovery document received", d.smartConfigurationStatus(), null,
                    d.smartRequestId(), Map.of("authorizationEndpoint", nz(d.endpoints().authorizationEndpoint()),
                            "tokenEndpoint", nz(d.endpoints().tokenEndpoint()), "capabilities", d.endpoints().capabilities(),
                            "codeChallengeMethods", d.endpoints().codeChallengeMethods(), "scopesSupported", d.endpoints().scopesSupported())));
        } else {
            String status = env.auth().mode() == AuthMode.NONE || env.auth().mode() == AuthMode.STATIC_TOKEN ? "WARN" : "WARN";
            steps.add(new Step("smart-configuration", status, "no /.well-known/smart-configuration (HTTP " + d.smartConfigurationStatus()
                    + "); endpoints from CapabilityStatement: " + d.endpoints().source(), d.smartConfigurationStatus(), null, d.smartRequestId(),
                    Map.of("authorizationEndpoint", nz(d.endpoints().authorizationEndpoint()), "tokenEndpoint", nz(d.endpoints().tokenEndpoint()))));
        }

        for (Map.Entry<String, String> base : env.igBaseUrls().entrySet()) {
            try {
                HttpResult r = gateway.get(env, base.getValue().replaceAll("/+$", "") + "/metadata",
                        FhirGateway.Options.of(FhirGateway.PURPOSE_DISCOVERY, correlation).unauthenticated());
                boolean isCs = r.ok() && "CapabilityStatement".equals(r.resourceType());
                steps.add(new Step("metadata:" + base.getKey(), isCs ? "PASS" : "FAIL", (isCs ? "CapabilityStatement received from " : "no CapabilityStatement at ")
                        + base.getValue(), r.status(), r.durationMs(), r.requestId(), isCs ? capabilityDetails(r.json()) : Map.of()));
                ok = ok && isCs;
            } catch (WorkbenchException e) {
                ok = false;
                steps.add(new Step("metadata:" + base.getKey(), "FAIL", e.getMessage(), upstreamStatus(e), null, upstreamRequest(e), Map.of()));
            }
        }

        switch (env.auth().mode()) {
            case NONE -> steps.add(new Step("token", "SKIP", "no authorization configured", null, null, null, Map.of()));
            case STATIC_TOKEN -> steps.add(new Step("token", tokens.status(env).present() ? "PASS" : "FAIL",
                    tokens.status(env).present() ? "static token is set" : "no static token stored", null, null, null, Map.of()));
            case SMART_AUTHORIZATION_CODE -> {
                var status = tokens.status(env);
                if (!status.present()) {
                    ok = false;
                    steps.add(new Step("token", "FAIL", "no token yet: run the SMART login", null, null, null, Map.of()));
                } else if (status.expired() && !status.refreshable()) {
                    ok = false;
                    steps.add(new Step("token", "FAIL", "token expired and no refresh token: run the SMART login again", null, null, null, Map.of()));
                } else {
                    try {
                        AccessToken t = tokens.current(env);
                        steps.add(new Step("token", "PASS", "token valid until " + t.expiresAt() + (t.patient() == null ? "" : ", patient " + t.patient()),
                                null, null, null, Map.of("scope", nz(t.scope()), "source", nz(t.source()))));
                    } catch (WorkbenchException e) {
                        ok = false;
                        steps.add(new Step("token", "FAIL", e.getMessage(), upstreamStatus(e), null, upstreamRequest(e), Map.of()));
                    }
                }
            }
            default -> {
                try {
                    AccessToken t = tokens.obtain(env, correlation);
                    steps.add(new Step("token", "PASS", "token obtained, valid until " + t.expiresAt(), null, null, null,
                            Map.of("scope", nz(t.scope()), "tokenType", nz(t.tokenType()), "source", nz(t.source()))));
                } catch (WorkbenchException e) {
                    ok = false;
                    steps.add(new Step("token", "FAIL", e.getMessage(), upstreamStatus(e), null, upstreamRequest(e), Map.of()));
                }
            }
        }

        if (ok) {
            try {
                HttpResult r = gateway.get(env, env.baseUrl() + "/metadata", FhirGateway.Options.of(FhirGateway.PURPOSE_DISCOVERY, correlation));
                steps.add(new Step("authenticated-call", r.ok() ? "PASS" : "FAIL", "GET metadata with the environment's headers and token answered " + r.status(),
                        r.status(), r.durationMs(), r.requestId(), Map.of()));
                ok = ok && r.ok();
            } catch (WorkbenchException e) {
                ok = false;
                steps.add(new Step("authenticated-call", "FAIL", e.getMessage(), upstreamStatus(e), null, upstreamRequest(e), Map.of()));
            }
        }
        return new Result(env.id(), correlation, ok, steps);
    }

    static Map<String, Object> capabilityDetails(JsonNode cs) {
        List<String> types = new ArrayList<>();
        List<String> security = new ArrayList<>();
        for (JsonNode rest : cs.path("rest")) {
            for (JsonNode r : rest.path("resource")) {
                types.add(r.path("type").asString(""));
            }
            for (JsonNode svc : rest.path("security").path("service")) {
                for (JsonNode c : svc.path("coding")) {
                    security.add(c.path("code").asString(""));
                }
            }
        }
        List<String> formats = new ArrayList<>();
        for (JsonNode f : cs.path("format")) {
            formats.add(f.asString(""));
        }
        List<String> instantiates = new ArrayList<>();
        for (JsonNode f : cs.path("instantiates")) {
            instantiates.add(f.asString(""));
        }
        for (JsonNode f : cs.path("implementationGuide")) {
            instantiates.add(f.asString(""));
        }
        return Map.of(
                "fhirVersion", cs.path("fhirVersion").asString(""),
                "software", cs.path("software").path("name").asString("") + " " + cs.path("software").path("version").asString(""),
                "implementation", cs.path("implementation").path("description").asString(""),
                "formats", formats,
                "securityServices", security,
                "resourceTypes", types,
                "implementationGuides", instantiates);
    }

    private static Integer upstreamStatus(WorkbenchException e) {
        return e.getUpstream() == null ? null : e.getUpstream().httpStatus();
    }

    private static String upstreamRequest(WorkbenchException e) {
        return e.getUpstream() == null ? null : e.getUpstream().requestId();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
