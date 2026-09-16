package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.EOB;

/**
 * Response times of the calls other groups make anyway (shared through the cache). Informational: the
 * IGs set no numbers, so a slow answer is a warning against the configured thresholds, never a failure.
 */
@Configuration
public class PerformanceChecks {

    private static final String NOTE = "No IG sets a response-time requirement; thresholds are the workbench settings paw.conformance.slow-warn-ms "
            + "/ slow-fail-ms. CMS-9115-F expects the API to be usable by consumer apps";

    static CheckResult judge(CheckResult.Builder b, CheckContext ctx, String what, long ms) {
        b.detail(what + ": " + ms + " ms (warn above " + ctx.slowWarnMs() + " ms, very slow above " + ctx.slowFailMs() + " ms)");
        if (ms > ctx.slowFailMs()) {
            return b.warn(what + " took " + ms + " ms: very slow (above " + ctx.slowFailMs() + " ms)");
        }
        if (ms > ctx.slowWarnMs()) {
            return b.warn(what + " took " + ms + " ms: slow (above " + ctx.slowWarnMs() + " ms)");
        }
        return b.info(what + " took " + ms + " ms");
    }

    @Bean
    Check metadataTime() {
        return SimpleCheck.of("performance.metadata", "performance", "CapabilityStatement response time", Severity.MAY,
                "Duration of GET [base]/metadata compared with the slow thresholds (warning when slower, otherwise the timing is reported)",
                NOTE, false, (b, ctx) -> {
            SmartDiscoveryService.Discovery d = ctx.discovery();
            b.evidence(d.metadataRequestId());
            if (d.capabilityStatement() == null) {
                return b.skip("no CapabilityStatement (HTTP " + d.metadataStatus() + ")");
            }
            return judge(b, ctx, "GET metadata", d.metadataMs());
        });
    }

    @Bean
    Check patientReadTime() {
        return SimpleCheck.of("performance.patientRead", "performance", "Patient read response time", Severity.MAY,
                "Duration of GET Patient/{id} compared with the slow thresholds", NOTE, true, (b, ctx) -> {
            HttpResult r = CheckSupport.patientRead(ctx);
            CheckSupport.record(b, r);
            if (!r.ok()) {
                return b.skip("Patient read answered HTTP " + r.status());
            }
            return judge(b, ctx, "GET Patient/" + ctx.patientId().orElseThrow(), r.durationMs());
        });
    }

    @Bean
    Check eobSearchTime() {
        return SimpleCheck.of("performance.eobSearch", "performance", "EOB search response time", Severity.MAY,
                "Duration of GET ExplanationOfBenefit?patient={id} (first page) compared with the slow thresholds", NOTE, true, (b, ctx) -> {
            HttpResult r = CheckSupport.patientSearch(ctx, EOB).response();
            CheckSupport.record(b, r);
            if (!r.ok()) {
                return b.skip("EOB search answered HTTP " + r.status());
            }
            return judge(b, ctx, "GET ExplanationOfBenefit?patient=" + ctx.patientId().orElseThrow(), r.durationMs());
        });
    }
}
