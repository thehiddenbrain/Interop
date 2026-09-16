package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.EOB;

/**
 * Error responses: unknown types and ids are 404s, bad parameters are 400s under strict handling, and
 * every error body is an OperationOutcome. The probes are fetched once and shared by the checks.
 */
@Configuration
public class ErrorHandlingChecks {

    private static final String C4BB_ERRORS = "C4BB 2.1.0 CapabilityStatement c4bb rest documentation item 3: (Status 400) invalid parameter, "
            + "(Status 404) unknown resource; FHIR R4 http.html: OperationOutcome on errors";

    static HttpResult unknownType(CheckContext ctx) {
        return ctx.cached("errors.unknownType", () -> ctx.get(ctx.environment().baseUrl() + "/NotAResource"));
    }

    static HttpResult unknownResource(CheckContext ctx) {
        return ctx.cached("errors.unknownResource", () -> ctx.get(ctx.readUrl(EOB, "does-not-exist-paw")));
    }

    /** Patient?_id=<pid> when a patient is selected (stays inside the token's scope), else Patient?. */
    static MultiValueMap<String, String> patientParams(CheckContext ctx, String... extra) {
        MultiValueMap<String, String> p = CheckContext.params(extra);
        ctx.patientId().ifPresent(pid -> p.add("_id", pid));
        return p;
    }

    static HttpResult strict(CheckContext ctx) {
        return ctx.cached("errors.strictHandling", () -> ctx.getWithHeaders(ctx.searchUrl("Patient", patientParams(ctx, "paw-unknown-param", "1")),
                Map.of("Prefer", "handling=strict")));
    }

    static HttpResult malformedDate(CheckContext ctx) {
        return ctx.cached("errors.malformedDate", () -> ctx.get(ctx.searchUrl("Patient", patientParams(ctx, "_lastUpdated", "not-a-date"))));
    }

    static CheckResult expect(CheckResult.Builder b, HttpResult r, int status, String what) {
        CheckSupport.record(b, r);
        if (r.status() != status) {
            return b.fail("expected HTTP " + status + " for " + what + ", got " + r.status());
        }
        if (!CheckSupport.isOperationOutcome(r)) {
            return b.fail("HTTP " + status + " without an OperationOutcome body");
        }
        return b.pass("HTTP " + status + " with OperationOutcome for " + what);
    }

    @Bean
    Check unknownTypeCheck() {
        return SimpleCheck.of("errors.unknownType", "errors", "Unknown resource type is a 404", Severity.SHALL,
                "GET [base]/NotAResource returns HTTP 404 with an OperationOutcome", C4BB_ERRORS, false,
                (b, ctx) -> expect(b, unknownType(ctx), 404, "an unknown resource type"));
    }

    @Bean
    Check unknownResourceCheck() {
        return SimpleCheck.of("errors.unknownResource", "errors", "Unknown resource id is a 404", Severity.SHALL,
                "GET ExplanationOfBenefit/does-not-exist-paw returns HTTP 404 with an OperationOutcome", C4BB_ERRORS + "; http.html#read", false,
                (b, ctx) -> expect(b, unknownResource(ctx), 404, "an unknown ExplanationOfBenefit id"));
    }

    @Bean
    Check strictHandlingCheck() {
        return SimpleCheck.of("errors.strictHandling", "errors", "Strict handling rejects unknown parameters", Severity.SHOULD,
                "GET Patient?paw-unknown-param=1 (plus _id of the selected patient) with 'Prefer: handling=strict' returns HTTP 400 with an "
                        + "OperationOutcome (servers SHOULD honour strict handling instead of silently ignoring unknown parameters)",
                "FHIR R4 search.html#errors: Prefer handling=strict; " + C4BB_ERRORS, false,
                (b, ctx) -> expect(b, strict(ctx), 400, "an unknown parameter under handling=strict"));
    }

    @Bean
    Check malformedDateCheck() {
        return SimpleCheck.of("errors.malformedDate", "errors", "Malformed date is a 400", Severity.SHOULD,
                "GET Patient?_lastUpdated=not-a-date returns HTTP 400 with an OperationOutcome",
                "FHIR R4 search.html#errors: invalid parameter value 400; " + C4BB_ERRORS, false,
                (b, ctx) -> expect(b, malformedDate(ctx), 400, "a malformed _lastUpdated value"));
    }

    @Bean
    Check operationOutcomeCheck() {
        return SimpleCheck.of("errors.operationOutcome", "errors", "Error bodies are OperationOutcome", Severity.SHALL,
                "Every error response (4xx/5xx) among the probes of this group carries an OperationOutcome body", C4BB_ERRORS
                        + "; FHIR R4 http.html#Status-Codes", false, (b, ctx) -> {
            List<String> wrong = new ArrayList<>();
            int errors = 0;
            for (HttpResult r : List.of(unknownType(ctx), unknownResource(ctx), strict(ctx), malformedDate(ctx))) {
                CheckSupport.record(b, r);
                if (r.status() >= 400) {
                    errors++;
                    if (!CheckSupport.isOperationOutcome(r)) {
                        wrong.add("HTTP " + r.status() + " for " + r.url() + ": " + (r.resourceType() == null ? "no FHIR body" : r.resourceType()));
                    }
                }
            }
            CheckSupport.list(b, "", wrong);
            if (errors == 0) {
                return b.fail("none of the probes produced an error response; the server accepts unknown types, ids and parameters");
            }
            if (!wrong.isEmpty()) {
                return b.fail(wrong.size() + " of " + errors + " error response(s) without OperationOutcome");
            }
            return b.pass("all " + errors + " error response(s) carry an OperationOutcome");
        });
    }
}
