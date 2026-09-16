package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Authorization behaviour: requests without a valid bearer token are rejected, the configured token
 * works, and the CapabilityStatement stays readable without one. Skipped for open (AuthMode.NONE) environments.
 */
@Configuration
public class SecurityChecks {

    private static final String C4BB_SECURITY = "C4BB 2.1.0 CapabilityStatement c4bb rest.security: 'A server SHALL reject any unauthorized "
            + "requests by returning an HTTP 401 Unauthorized, HTTP 403 Forbidden, or HTTP 404 Not Found'";
    private static final String PDEX_SECURITY = "PDex 2.1.0 CapabilityStatement pdex-server rest.security: 'A server SHALL reject any "
            + "unauthorized requests by returning an HTTP 401'";

    /** A protected URL: the patient under test when there is one, else the Patient endpoint. */
    static String protectedUrl(CheckContext ctx) {
        return ctx.patientId().map(pid -> ctx.readUrl("Patient", pid))
                .orElseGet(() -> ctx.searchUrl("Patient", CheckContext.params("_count", "1")));
    }

    static HttpResult unauthenticated(CheckContext ctx) {
        return ctx.cached("security.unauthenticated", () -> ctx.getUnauthenticated(protectedUrl(ctx)));
    }

    static boolean open(CheckContext ctx) {
        return ctx.environment().auth().mode() == AuthMode.NONE;
    }

    @Bean
    Check unauthenticatedRejected() {
        return SimpleCheck.of("security.unauthenticated.rejected", "security", "Requests without a token are rejected", Severity.SHALL,
                "GET Patient/{id} (or Patient?_count=1 without a patient) without an Authorization header is answered with HTTP 401 or 403; "
                        + "C4BB also allows 404 (reported with a note). Skipped when the environment uses no authentication",
                C4BB_SECURITY + "; " + PDEX_SECURITY, false, (b, ctx) -> {
            if (open(ctx)) {
                return b.skip("environment uses AuthMode.NONE: there is no authorization to enforce");
            }
            HttpResult r = unauthenticated(ctx);
            CheckSupport.record(b, r);
            if (r.status() == 401 || r.status() == 403) {
                return b.pass("HTTP " + r.status() + " without Authorization header");
            }
            if (r.status() == 404) {
                b.detail("404 hides the resource from unauthorized callers; C4BB allows it, PDex expects 401");
                return b.pass("HTTP 404 without Authorization header (C4BB-acceptable)");
            }
            return b.fail("expected 401/403 without a token, got HTTP " + r.status());
        });
    }

    @Bean
    Check invalidTokenRejected() {
        return SimpleCheck.of("security.invalidToken.rejected", "security", "An invalid bearer token is rejected", Severity.SHALL,
                "The same request with 'Authorization: Bearer this-is-not-a-valid-token' is answered with HTTP 401 or 403. Skipped when the "
                        + "environment uses no authentication", C4BB_SECURITY + "; " + PDEX_SECURITY + "; RFC 6750 section 3.1", false, (b, ctx) -> {
            if (open(ctx)) {
                return b.skip("environment uses AuthMode.NONE: there is no authorization to enforce");
            }
            HttpResult r = ctx.getWithHeaders(protectedUrl(ctx), Map.of("Authorization", "Bearer this-is-not-a-valid-token"));
            CheckSupport.record(b, r);
            if (r.status() == 401 || r.status() == 403) {
                return b.pass("HTTP " + r.status() + " with an invalid token");
            }
            if (r.status() == 404) {
                return b.pass("HTTP 404 with an invalid token (C4BB-acceptable)");
            }
            return b.fail("expected 401/403 with an invalid token, got HTTP " + r.status());
        });
    }

    @Bean
    Check tokenObtained() {
        return SimpleCheck.of("security.token.obtained", "security", "The configured token works", Severity.SHALL,
                "A bearer token is available for the environment (static, client credentials, backend services or SMART authorization code) "
                        + "and an authenticated GET [base]/metadata is answered with HTTP 200. Skipped when the environment uses no authentication",
                "SMART App Launch 2.2.0 / RFC 6750: access token in the Authorization header", false, (b, ctx) -> {
            if (open(ctx)) {
                return b.skip("environment uses AuthMode.NONE");
            }
            HttpResult r;
            try {
                r = ctx.get(ctx.environment().baseUrl() + "/metadata");
            } catch (WorkbenchException e) {
                if (e.getCode() == ErrorCode.AUTH_FAILED || e.getCode() == ErrorCode.NOT_SUPPORTED) {
                    if (e.getUpstream() != null) {
                        b.evidence(e.getUpstream().requestId());
                    }
                    return b.fail("no token: " + e.getMessage());
                }
                throw e;
            }
            CheckSupport.record(b, r);
            b.detail("auth mode: " + ctx.environment().auth().mode());
            if (r.ok()) {
                return b.pass("token sent; authenticated metadata request answered HTTP 200");
            }
            return b.fail("authenticated metadata request answered HTTP " + r.status());
        });
    }

    @Bean
    Check metadataOpen() {
        return SimpleCheck.of("security.metadata.open", "security", "CapabilityStatement readable without a token", Severity.SHOULD,
                "GET [base]/metadata without an Authorization header is answered with HTTP 200: the capability and SMART discovery documents "
                        + "must be reachable before an app has a token",
                "FHIR R4 http.html#capabilities (metadata is exempt from authorization); SMART App Launch 2.2.0 discovery", false, (b, ctx) -> {
            HttpResult r = ctx.getUnauthenticated(ctx.environment().baseUrl() + "/metadata");
            CheckSupport.record(b, r);
            if (r.ok() && r.isResource("CapabilityStatement")) {
                return b.pass("metadata is open");
            }
            return b.fail("metadata without a token answered HTTP " + r.status() + (r.isResource("CapabilityStatement") ? "" : " (no CapabilityStatement)"));
        });
    }

    @Bean
    Check wwwAuthenticate() {
        return SimpleCheck.of("security.wwwAuthenticate", "security", "401 carries WWW-Authenticate", Severity.SHOULD,
                "The 401 response to a request without a token includes a WWW-Authenticate header (RFC 6750 requires it on bearer-token "
                        + "challenges; apps use it to detect that a new token is needed). Skipped when the environment uses no authentication",
                "RFC 6750 section 3 The WWW-Authenticate Response Header Field", false, (b, ctx) -> {
            if (open(ctx)) {
                return b.skip("environment uses AuthMode.NONE");
            }
            HttpResult r = unauthenticated(ctx);
            CheckSupport.record(b, r);
            if (r.status() != 401) {
                return b.skip("unauthenticated request answered HTTP " + r.status() + ", WWW-Authenticate applies to 401 only");
            }
            String header = r.header("WWW-Authenticate");
            if (header == null) {
                return b.fail("401 without a WWW-Authenticate header");
            }
            b.detail("WWW-Authenticate: " + header);
            return b.pass("WWW-Authenticate present on the 401 response");
        });
    }
}
