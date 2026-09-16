package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.OAuthEndpoints;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** SMART App Launch discovery: the well-known document and its agreement with the CapabilityStatement. */
@Configuration
public class SmartChecks {

    private static final String SMART = "SMART App Launch 2.2.0 (2.x) Conformance / .well-known/smart-configuration";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Bean
    Check wellKnownPresent() {
        return SimpleCheck.of("smart.wellKnown.present", "smart", "smart-configuration is served", Severity.SHALL,
                "GET [base]/.well-known/smart-configuration returns HTTP 200 with a JSON object (a SMART server SHALL publish its discovery "
                        + "document at this path)", SMART + " 'Server SHALL serve /.well-known/smart-configuration'", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId());
            if (d.smartConfiguration() != null) {
                return b.pass("smart-configuration received from " + d.smartConfigurationUrl());
            }
            for (String n : d.notes()) {
                b.detail(n);
            }
            return b.fail("no smart-configuration at " + d.smartConfigurationUrl() + (d.smartConfigurationStatus() == null ? ""
                    : " (HTTP " + d.smartConfigurationStatus() + ")"));
        });
    }

    @Bean
    Check wellKnownRequired() {
        return SimpleCheck.of("smart.wellKnown.required", "smart", "Required smart-configuration fields", Severity.SHALL,
                "smart-configuration carries authorization_endpoint, token_endpoint, capabilities, code_challenge_methods_supported "
                        + "containing S256 and grant_types_supported containing authorization_code (required metadata in SMART 2.x)",
                SMART + " metadata: required fields", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId());
            JsonNode s = d.smartConfiguration();
            if (s == null) {
                return b.skip("no smart-configuration");
            }
            List<String> missing = new ArrayList<>();
            for (String f : List.of("authorization_endpoint", "token_endpoint", "capabilities", "code_challenge_methods_supported", "grant_types_supported")) {
                if (!s.hasNonNull(f)) {
                    missing.add(f);
                }
            }
            if (s.has("code_challenge_methods_supported") && !contains(s.get("code_challenge_methods_supported"), "S256")) {
                missing.add("code_challenge_methods_supported: S256");
            }
            if (s.has("grant_types_supported") && !contains(s.get("grant_types_supported"), "authorization_code")) {
                missing.add("grant_types_supported: authorization_code");
            }
            b.detail("authorization_endpoint: " + s.path("authorization_endpoint").asText("-"));
            b.detail("token_endpoint: " + s.path("token_endpoint").asText("-"));
            if (!missing.isEmpty()) {
                return b.fail("missing or incomplete: " + String.join(", ", missing));
            }
            return b.pass("all required fields present; PKCE S256 and authorization_code declared");
        });
    }

    @Bean
    Check wellKnownCapabilities() {
        return SimpleCheck.of("smart.wellKnown.capabilities", "smart", "Patient-facing SMART capabilities", Severity.SHOULD,
                "smart-configuration.capabilities lists launch-standalone, a client-* profile (client-public, client-confidential-symmetric "
                        + "or client-confidential-asymmetric), context-standalone-patient, permission-patient, permission-offline and "
                        + "sso-openid-connect: the capabilities a consumer app needs for a standalone patient launch with refresh tokens; "
                        + "lists what is missing", SMART + " capability sets; CMS-9115-F 42 CFR 422.119(c) SMART App Launch", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId());
            JsonNode s = d.smartConfiguration();
            if (s == null) {
                return b.skip("no smart-configuration");
            }
            JsonNode caps = s.path("capabilities");
            List<String> present = new ArrayList<>();
            for (JsonNode c : caps) {
                present.add(c.asText());
            }
            b.detail("capabilities: " + String.join(", ", present));
            List<String> missing = new ArrayList<>();
            for (String c : List.of("launch-standalone", "context-standalone-patient", "permission-patient", "permission-offline", "sso-openid-connect")) {
                if (!present.contains(c)) {
                    missing.add(c);
                }
            }
            if (present.stream().noneMatch(c -> c.startsWith("client-"))) {
                missing.add("client-public or client-confidential-symmetric/asymmetric");
            }
            if (!missing.isEmpty()) {
                return b.fail("missing: " + String.join(", ", missing));
            }
            return b.pass("standalone patient launch, offline access and OpenID Connect declared");
        });
    }

    @Bean
    Check wellKnownScopes() {
        return SimpleCheck.of("smart.wellKnown.scopes", "smart", "Patient scopes are advertised", Severity.SHOULD,
                "smart-configuration.scopes_supported includes openid, fhirUser, offline_access and a patient-level wildcard read scope "
                        + "(patient/*.read or patient/*.rs) so consumer apps can request everything the member is entitled to",
                SMART + " scopes_supported (SHOULD); SMART 2.x scopes patient/*.rs, v1 patient/*.read", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId());
            JsonNode s = d.smartConfiguration();
            if (s == null) {
                return b.skip("no smart-configuration");
            }
            if (!s.has("scopes_supported")) {
                return b.fail("scopes_supported is absent");
            }
            List<String> scopes = new ArrayList<>();
            for (JsonNode c : s.get("scopes_supported")) {
                scopes.add(c.asText());
            }
            CheckSupport.list(b, "scope: ", scopes);
            List<String> missing = new ArrayList<>();
            for (String x : List.of("openid", "fhirUser", "offline_access")) {
                if (!scopes.contains(x)) {
                    missing.add(x);
                }
            }
            if (!scopes.contains("patient/*.read") && !scopes.contains("patient/*.rs") && !scopes.contains("patient/*.*")) {
                missing.add("patient/*.read or patient/*.rs");
            }
            if (!missing.isEmpty()) {
                return b.fail("missing: " + String.join(", ", missing));
            }
            return b.pass("openid, fhirUser, offline_access and a patient wildcard read scope are advertised");
        });
    }

    @Bean
    Check endpointsHttps() {
        return SimpleCheck.of("smart.endpoints.https", "smart", "OAuth endpoints use HTTPS", Severity.SHALL,
                "The discovered authorization and token endpoints are https URLs (SMART requires TLS for all OAuth exchanges; plain http on a "
                        + "loopback address is tolerated for local sandboxes and reported as a warning)",
                SMART + "; SMART App Launch Best Practices in Authorization: TLS", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId()).evidence(d.metadataRequestId());
            OAuthEndpoints e = d.endpoints();
            if (e == null || (e.authorizationEndpoint() == null && e.tokenEndpoint() == null)) {
                return b.skip("no OAuth endpoints discovered (smart-configuration or CapabilityStatement)");
            }
            List<String> plain = new ArrayList<>();
            List<String> loopback = new ArrayList<>();
            for (String url : new String[]{e.authorizationEndpoint(), e.tokenEndpoint()}) {
                if (url == null) {
                    continue;
                }
                b.detail(url);
                if (!url.toLowerCase().startsWith("https://")) {
                    String host = UrlBuilder.hostOf(url);
                    (host != null && LOOPBACK.contains(host) ? loopback : plain).add(url);
                }
            }
            if (!plain.isEmpty()) {
                return b.fail("not https: " + String.join(", ", plain));
            }
            if (!loopback.isEmpty()) {
                return b.warn("plain http on a loopback address (acceptable only for local testing): " + String.join(", ", loopback));
            }
            return b.pass("authorization and token endpoints use https");
        });
    }

    @Bean
    Check endpointsConsistent() {
        return SimpleCheck.of("smart.endpoints.consistent", "smart", "smart-configuration and CapabilityStatement agree", Severity.SHOULD,
                "When both the well-known document and the CapabilityStatement oauth-uris extension are present, their authorize and token "
                        + "URIs are the same (apps may use either source; disagreement breaks one of them)",
                SMART + " (well-known) and CapabilityStatement oauth-uris extension", false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.smartRequestId()).evidence(d.metadataRequestId());
            JsonNode s = d.smartConfiguration();
            JsonNode security = CheckSupport.security(d.capabilityStatement());
            if (s == null || security == null) {
                return b.skip(s == null ? "no smart-configuration" : "no CapabilityStatement security section");
            }
            String csAuthorize = null;
            String csToken = null;
            for (JsonNode ext : security.path("extension")) {
                if (SmartDiscoveryService.OAUTH_URIS_EXTENSION.equals(ext.path("url").asText())) {
                    for (JsonNode inner : ext.path("extension")) {
                        String value = inner.hasNonNull("valueUri") ? inner.get("valueUri").asText() : inner.path("valueUrl").asText(null);
                        if ("authorize".equals(inner.path("url").asText())) {
                            csAuthorize = value;
                        } else if ("token".equals(inner.path("url").asText())) {
                            csToken = value;
                        }
                    }
                }
            }
            if (csAuthorize == null && csToken == null) {
                return b.skip("CapabilityStatement has no oauth-uris extension to compare with");
            }
            List<String> diffs = new ArrayList<>();
            compare(b, diffs, "authorize", s.path("authorization_endpoint").asText(null), csAuthorize);
            compare(b, diffs, "token", s.path("token_endpoint").asText(null), csToken);
            if (!diffs.isEmpty()) {
                return b.fail("endpoints differ: " + String.join("; ", diffs));
            }
            return b.pass("authorize and token URIs are the same in both documents");
        });
    }

    private static void compare(CheckResult.Builder b, List<String> diffs, String name, String wellKnown, String capability) {
        b.detail(name + ": well-known=" + wellKnown + ", CapabilityStatement=" + capability);
        if (wellKnown != null && capability != null && !wellKnown.equals(capability)) {
            diffs.add(name);
        }
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }
}
