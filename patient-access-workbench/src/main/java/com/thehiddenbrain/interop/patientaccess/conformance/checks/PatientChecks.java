package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.patient.Fhir;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Optional;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.C4BB;
import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.US_CORE;

/** Patient read, the IG search parameters and combinations, and the C4BB / US Core Patient profile essentials. */
@Configuration
public class PatientChecks {

    private static final String C4BB_CS = "C4BB 2.1.0 CapabilityStatement c4bb Patient";
    private static final String PDEX_CS = "PDex 2.1.0 CapabilityStatement pdex-server Patient";
    private static final String USCORE_CS = "US Core CapabilityStatement us-core-server Patient";

    /** The patient resource as read by {@link CheckSupport#patientRead}, or null when the read failed. */
    static JsonNode patient(CheckContext ctx) {
        HttpResult r = CheckSupport.patientRead(ctx);
        return r.ok() && r.isResource("Patient") ? r.json() : null;
    }

    static String family(JsonNode patient) {
        for (JsonNode n : patient.path("name")) {
            String f = Fhir.text(n.get("family"));
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** Runs a Patient search that must find the patient under test. */
    static CheckResult expectPatient(CheckResult.Builder b, CheckContext ctx, MultiValueMap<String, String> params) {
        String pid = ctx.patientId().orElseThrow();
        SearchPage page = CheckSupport.search(ctx, b, "Patient", params);
        String problem = CheckSupport.bundleProblem(page, "Patient");
        if (problem != null) {
            return b.fail(problem);
        }
        if (!CheckSupport.containsId(page.resources(), pid)) {
            return b.fail("Patient/" + pid + " is not among the " + page.count() + " result(s)");
        }
        return b.pass("Patient/" + pid + " found (" + page.count() + " result(s))");
    }

    /** Skip when the patient could not be read, or a required search value is missing. */
    static CheckResult searchWith(CheckResult.Builder b, CheckContext ctx, String[] paramNames, java.util.function.Function<JsonNode, String[]> values) {
        JsonNode p = patient(ctx);
        if (p == null) {
            return b.skip("patient could not be read (see patient.read)");
        }
        String[] v = values.apply(p);
        String[] kv = new String[paramNames.length * 2];
        for (int i = 0; i < paramNames.length; i++) {
            if (v[i] == null) {
                return b.skip("Patient/" + Fhir.idOf(p) + " has no value for '" + paramNames[i] + "'");
            }
            kv[2 * i] = paramNames[i];
            kv[2 * i + 1] = v[i];
        }
        return expectPatient(b, ctx, CheckContext.params(kv));
    }

    @Bean
    Check patientRead() {
        return SimpleCheck.of("patient.read", "patient", "Patient read", Severity.SHALL,
                "GET Patient/{id} returns HTTP 200 with a Patient resource whose id is the requested one",
                C4BB_CS + " read SHALL; " + USCORE_CS + " read SHALL", true, (b, ctx) -> {
            String pid = ctx.patientId().orElseThrow();
            HttpResult r = CheckSupport.patientRead(ctx);
            CheckSupport.record(b, r);
            if (!r.ok()) {
                return b.fail("HTTP " + r.status() + (r.body().isBlank() ? "" : ": " + r.errorSummary()));
            }
            if (!r.isResource("Patient")) {
                return b.fail("body is not a Patient (" + r.resourceType() + ")");
            }
            if (!pid.equals(Fhir.idOf(r.json()))) {
                return b.fail("returned Patient/" + Fhir.idOf(r.json()) + " for a read of Patient/" + pid);
            }
            JsonNode p = r.json();
            b.detail("name: " + (p.path("name").path(0).path("given").path(0).asString("") + " " + (family(p) == null ? "" : family(p))).trim()
                    + ", birthDate " + p.path("birthDate").asString("-") + ", gender " + p.path("gender").asString("-"));
            return b.pass("Patient/" + pid + " read in " + r.durationMs() + " ms");
        });
    }

    @Bean
    Check patientVread() {
        return SimpleCheck.of("patient.vread", "patient", "Patient vread", Severity.SHOULD,
                "When the Patient carries meta.versionId, GET Patient/{id}/_history/{versionId} returns that version (C4BB payers SHALL "
                        + "support vread when they keep point-in-time data; skipped when no versionId is present)",
                C4BB_CS + " vread SHOULD; C4BB EOB documentation: versioned references", true, (b, ctx) -> {
            JsonNode p = patient(ctx);
            if (p == null) {
                return b.skip("patient could not be read");
            }
            String vid = Fhir.text(p.path("meta").get("versionId"));
            if (vid == null) {
                return b.skip("Patient/" + Fhir.idOf(p) + " has no meta.versionId; vread cannot be exercised");
            }
            HttpResult r = ctx.get(ctx.readUrl("Patient", Fhir.idOf(p)) + "/_history/" + vid);
            CheckSupport.record(b, r);
            if (r.ok() && r.isResource("Patient")) {
                return b.pass("version " + vid + " of Patient/" + Fhir.idOf(p) + " read");
            }
            return b.fail("vread answered HTTP " + r.status());
        });
    }

    @Bean
    Check patientSearchById() {
        return SimpleCheck.of("patient.search.id", "patient", "Patient search by _id", Severity.SHALL,
                "GET Patient?_id={id} returns a searchset containing the patient", C4BB_CS + " _id SHALL; " + USCORE_CS + " _id SHALL", true,
                (b, ctx) -> expectPatient(b, ctx, CheckContext.params("_id", ctx.patientId().orElseThrow())));
    }

    @Bean
    Check patientSearchByIdentifier() {
        return SimpleCheck.of("patient.search.identifier", "patient", "Patient search by identifier", Severity.SHALL,
                "GET Patient?identifier={system}|{value} with the member identifier (type MB, else the first identifier) returns the patient",
                PDEX_CS + " identifier SHALL; " + USCORE_CS + " identifier SHALL", true, (b, ctx) -> searchWith(b, ctx, new String[]{"identifier"}, p -> {
            Optional<JsonNode> mb = CheckSupport.identifierOfType(p, "MB");
            JsonNode id = mb.orElse(p.path("identifier").size() > 0 ? p.path("identifier").get(0) : null);
            return new String[]{id == null ? null : CheckSupport.identifierToken(id)};
        }));
    }

    @Bean
    Check patientSearchByName() {
        return SimpleCheck.of("patient.search.name", "patient", "Patient search by name", Severity.SHALL,
                "GET Patient?name={family} returns the patient", PDEX_CS + " name SHALL; " + USCORE_CS + " name SHALL", true,
                (b, ctx) -> searchWith(b, ctx, new String[]{"name"}, p -> new String[]{family(p)}));
    }

    @Bean
    Check patientSearchByNameBirthdate() {
        return SimpleCheck.of("patient.search.name.birthdate", "patient", "Patient search by name + birthdate", Severity.SHALL,
                "GET Patient?name={family}&birthdate={birthDate} returns the patient (required combination)",
                USCORE_CS + " birthdate+name SHALL; " + PDEX_CS + " combination birthdate+name SHALL", true,
                (b, ctx) -> searchWith(b, ctx, new String[]{"name", "birthdate"}, p -> new String[]{family(p), Fhir.text(p.get("birthDate"))}));
    }

    @Bean
    Check patientSearchByGenderName() {
        return SimpleCheck.of("patient.search.gender.name", "patient", "Patient search by gender + name", Severity.SHALL,
                "GET Patient?gender={gender}&name={family} returns the patient (required combination)",
                USCORE_CS + " gender+name SHALL; " + PDEX_CS + " combination gender+name SHALL", true,
                (b, ctx) -> searchWith(b, ctx, new String[]{"gender", "name"}, p -> new String[]{Fhir.text(p.get("gender")), family(p)}));
    }

    @Bean
    Check patientSearchByFamilyGender() {
        return SimpleCheck.of("patient.search.family.gender", "patient", "Patient search by family + gender", Severity.SHOULD,
                "GET Patient?family={family}&gender={gender} returns the patient", USCORE_CS + " family+gender SHOULD", true,
                (b, ctx) -> searchWith(b, ctx, new String[]{"family", "gender"}, p -> new String[]{family(p), Fhir.text(p.get("gender"))}));
    }

    @Bean
    Check patientSearchByBirthdateFamily() {
        return SimpleCheck.of("patient.search.birthdate.family", "patient", "Patient search by birthdate + family", Severity.SHOULD,
                "GET Patient?birthdate={birthDate}&family={family} returns the patient", USCORE_CS + " birthdate+family SHOULD", true,
                (b, ctx) -> searchWith(b, ctx, new String[]{"birthdate", "family"}, p -> new String[]{Fhir.text(p.get("birthDate")), family(p)}));
    }

    @Bean
    Check patientProfileDeclared() {
        return SimpleCheck.of("patient.profile.declared", "patient", "Patient declares its profile", Severity.SHALL,
                "Patient.meta.profile contains the C4BB Patient or US Core Patient profile (C4BB: 'Identify the CARIN-BB profiles supported "
                        + "as part of the FHIR meta.profile attribute for each instance')",
                "C4BB 2.1.0 CapabilityStatement c4bb rest documentation item 5; C4BB-Patient 2.1.0; US Core Patient", true, (b, ctx) -> {
            JsonNode p = patient(ctx);
            if (p == null) {
                return b.skip("patient could not be read");
            }
            List<String> profiles = Fhir.profiles(p);
            b.detail("meta.profile: " + (profiles.isEmpty() ? "(none)" : String.join(", ", profiles)));
            if (CheckSupport.declaresProfile(p, C4BB + "C4BB-Patient") || CheckSupport.declaresProfile(p, US_CORE + "us-core-patient")) {
                return b.pass("C4BB / US Core Patient profile declared");
            }
            return b.fail("neither " + C4BB + "C4BB-Patient nor " + US_CORE + "us-core-patient declared");
        });
    }

    @Bean
    Check patientProfileLite() {
        return SimpleCheck.of("patient.profileLite", "patient", "C4BB Patient required elements", Severity.SHALL,
                "The Patient satisfies the required elements and fixed values of the C4BB Patient profile (lite check from the catalog's "
                        + "element rules: cardinality, fixed/pattern values, slices); must-support elements that are not populated are listed "
                        + "as information", "C4BB-Patient 2.1.0 StructureDefinition", true, (b, ctx) -> {
            JsonNode p = patient(ctx);
            if (p == null) {
                return b.skip("patient could not be read");
            }
            ProfileLiteChecker.Report report = ctx.checker().check(p);
            b.detail("checked against " + report.profile() + (report.declared() ? "" : " (not declared by the resource)"));
            int[] listed = {0};
            int errors = CheckSupport.addIssues(b, p, report, listed);
            int infos = 0;
            for (ProfileLiteChecker.Issue i : report.issues()) {
                if ("info".equals(i.severity()) && infos++ < 10) {
                    b.detail(i.path() + ": " + i.message() + " [info]");
                }
            }
            if (errors > 0) {
                return b.fail(CheckSupport.plural(errors, "error") + ", " + CheckSupport.plural(report.warnings(), "warning"));
            }
            return b.pass("no errors (" + CheckSupport.plural(report.warnings(), "warning") + ", " + CheckSupport.plural(report.infos(), "info") + ")");
        });
    }

    @Bean
    Check patientMemberIdentifier() {
        return SimpleCheck.of("patient.identifier.memberId", "patient", "Member identifier present", Severity.SHALL,
                "Patient.identifier contains a member identifier: type coding MB (v2-0203) or um (C4BB unique member id); C4BB requires "
                        + "identifier 1..* with the memberid slice", "C4BB-Patient 2.1.0 identifier:memberid (MB) / identifier:uniqueMemberId (um)", true,
                (b, ctx) -> {
            JsonNode p = patient(ctx);
            if (p == null) {
                return b.skip("patient could not be read");
            }
            Optional<JsonNode> id = CheckSupport.identifierOfType(p, "MB", "um");
            b.detail("identifiers: " + p.path("identifier").size());
            if (id.isEmpty()) {
                return b.fail("no identifier with type MB or um" + (p.path("identifier").size() == 0 ? " (identifier is absent)" : ""));
            }
            String type = Fhir.code(id.get().path("type"), null);
            return b.pass("member identifier of type " + type + ": " + CheckSupport.identifierToken(id.get()));
        });
    }

    @Bean
    Check patientLastUpdated() {
        return SimpleCheck.of("patient.lastUpdated", "patient", "Patient has meta.lastUpdated", Severity.SHALL,
                "Patient.meta.lastUpdated is populated (C4BB: reference resources SHALL carry the last update or creation time so apps can "
                        + "tell current data from as-of-service data)", "C4BB 2.1.0 CapabilityStatement description: meta.lastUpdated; C4BB-Patient meta.lastUpdated 1..1", true,
                (b, ctx) -> {
            JsonNode p = patient(ctx);
            if (p == null) {
                return b.skip("patient could not be read");
            }
            String lu = Fhir.lastUpdated(p);
            return lu == null ? b.fail("meta.lastUpdated is absent") : b.pass("meta.lastUpdated " + lu);
        });
    }

    @Bean
    Check patientUnknown404() {
        return SimpleCheck.of("patient.unknown.404", "patient", "Unknown Patient id is a 404", Severity.SHALL,
                "GET Patient/does-not-exist-paw returns HTTP 404 with an OperationOutcome body",
                "FHIR R4 http.html#read: unknown resource 404; C4BB 2.1.0 CapabilityStatement rest documentation: (Status 404) unknown resource",
                false, (b, ctx) -> {
            HttpResult r = ctx.get(ctx.readUrl("Patient", "does-not-exist-paw"));
            CheckSupport.record(b, r);
            if (r.status() != 404) {
                return b.fail("expected HTTP 404, got " + r.status());
            }
            if (!CheckSupport.isOperationOutcome(r)) {
                return b.fail("404 without an OperationOutcome body");
            }
            return b.pass("404 with OperationOutcome");
        });
    }

    @Bean
    Check patientRevincludeProvenance() {
        return SimpleCheck.of("patient.revinclude.provenance", "patient", "Provenance via _revinclude on Patient", Severity.SHOULD,
                "GET Patient?_id={id}&_revinclude=Provenance:target returns HTTP 200 (US Core: Provenance is retrieved with _revinclude); "
                        + "informational when no Provenance is returned", USCORE_CS + " searchRevInclude Provenance:target; PDex 2.1.0 Provenance", true,
                (b, ctx) -> {
            String pid = ctx.patientId().orElseThrow();
            SearchPage page = CheckSupport.search(ctx, b, "Patient", CheckContext.params("_id", pid, "_revinclude", "Provenance:target"));
            String problem = CheckSupport.bundleProblem(page, "Patient");
            if (problem != null) {
                return b.fail(problem);
            }
            long provenance = page.included().stream().filter(r -> "Provenance".equals(r.path("resourceType").asString(""))).count();
            if (provenance == 0) {
                return b.info("search accepted, no Provenance returned for Patient/" + pid);
            }
            return b.pass(provenance + " Provenance resource(s) included");
        });
    }
}
