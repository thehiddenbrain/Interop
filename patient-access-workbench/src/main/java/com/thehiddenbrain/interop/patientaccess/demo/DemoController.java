package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.ClientAuthMethod;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentView;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.environment.IdentifierSystem;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Workbench-side helpers for the demo server: which members exist (with the ids a tester types into the
 * member search) and a one-click environment that points the workbench at its own demo API.
 */
@RestController
@RequestMapping(value = "/api/v1/demo", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Demo server", description = "The in-process demo Patient Access API: its members and an environment pointing at it")
@DemoEnabled
public class DemoController {

    public static final String ENVIRONMENT_NAME = "Demo (local)";
    static final String VENDOR = "Patient Access Workbench demo";
    private static final Map<String, String> IDENTIFIER_TYPE_LABELS = Map.of(
            "MB", "Member number", "MR", "Medical record number", "XV", "Health plan identifier", "PT", "Patient external identifier",
            "um", "Unique member id (C4BB)", "SS", "Social security number", "MA", "Medicaid number", "MC", "Medicare number");

    public record IdentifierView(String system, String value, String type) {
    }

    public record Member(String patientId, String name, String birthDate, String gender, List<IdentifierView> identifiers,
                         boolean hasCoverage, boolean hasClaims, boolean hasPriorAuths, int coverages, int claims, int priorAuths) {
    }

    private final DemoDataStore store;
    private final EnvironmentService environments;
    private final WorkbenchProperties.Demo demo;

    public DemoController(DemoDataStore store, EnvironmentService environments, WorkbenchProperties properties) {
        this.store = store;
        this.environments = environments;
        this.demo = properties.demo();
    }

    @Operation(summary = "The demo members with the identifiers the member search accepts and what data each one has")
    @GetMapping("/members")
    public List<Member> members() {
        List<Member> out = new ArrayList<>();
        for (ObjectNode p : store.patients()) {
            String id = p.path("id").asText();
            List<IdentifierView> ids = new ArrayList<>();
            for (JsonNode i : p.path("identifier")) {
                ids.add(new IdentifierView(i.path("system").asText(null), i.path("value").asText(null), typeCode(i)));
            }
            int coverages = (int) store.all("Coverage").stream().filter(c -> id.equals(store.patientOf(c).orElse(null))).count();
            List<ObjectNode> eobs = store.all("ExplanationOfBenefit").stream().filter(e -> id.equals(store.patientOf(e).orElse(null))).toList();
            int priorAuths = (int) eobs.stream().filter(PriorAuthSummarizer::isPriorAuth).count();
            int claims = eobs.size() - priorAuths;
            out.add(new Member(id, DemoAuthController.displayName(p), p.path("birthDate").asText(null), p.path("gender").asText(null), ids,
                    coverages > 0, claims > 0, priorAuths > 0, coverages, claims, priorAuths));
        }
        return out;
    }

    @Operation(summary = "Create (or return) the environment \"Demo (local)\" that points at this workbench's demo API",
            description = "Client credentials with the demo client, endpoint discovery on, identifier systems taken from the demo patients. 201 when created, 200 when it already existed.")
    @PostMapping("/environment")
    public ResponseEntity<EnvironmentView> environment(HttpServletRequest request) {
        Optional<EnvironmentView> existing = environments.list().stream().filter(e -> ENVIRONMENT_NAME.equalsIgnoreCase(e.name())).findFirst();
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }
        String fhirBase = DemoFhirController.fhirBase(request);
        EnvironmentInput.AuthInput auth = new EnvironmentInput.AuthInput(AuthMode.CLIENT_CREDENTIALS, true, null, null, demo.clientId(),
                demo.clientSecret(), ClientAuthMethod.CLIENT_SECRET_BASIC, DemoAuthService.DEFAULT_SYSTEM_SCOPE, null, null, null, null, null,
                true, Map.of(), Map.of());
        EnvironmentInput input = new EnvironmentInput(ENVIRONMENT_NAME, VENDOR, EnvironmentTier.SANDBOX, fhirBase, auth, List.of(),
                identifierSystems(), FhirOptions.defaults(), null, notes(fhirBase), true, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(environments.create(input));
    }

    /** One entry per identifier system/type pair found on the demo patients; member-number (MB) systems are the member-id defaults. */
    List<IdentifierSystem> identifierSystems() {
        List<IdentifierSystem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ObjectNode p : store.patients()) {
            for (JsonNode i : p.path("identifier")) {
                String system = i.path("system").asText(null);
                if (system == null || system.isBlank()) {
                    continue;
                }
                String type = typeCode(i);
                if (!seen.add(system + "|" + type)) {
                    continue;
                }
                String label = (type == null ? "Identifier" : IDENTIFIER_TYPE_LABELS.getOrDefault(type, type)) + " (" + host(system) + ")";
                out.add(new IdentifierSystem(label, system, type, "MB".equals(type)));
            }
        }
        return out;
    }

    private String notes(String fhirBase) {
        StringBuilder sb = new StringBuilder("Created by POST /api/v1/demo/environment. Points at this workbench's own demo Patient Access API (")
                .append(fhirBase).append("): HL7 C4BB / PDex / US Core / Drug Formulary example resources plus synthetic prior authorizations and clinical data. ")
                .append("Token: client_credentials with client id '").append(demo.clientId()).append("' at ").append(fhirBase.replace(DemoFhirController.BASE_PATH, DemoAuthController.BASE_PATH))
                .append("/token; switch the auth mode to SMART_AUTHORIZATION_CODE to try the SMART login (sign in as a demo patient). Demo members: ");
        boolean first = true;
        for (Member m : members()) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(m.name()).append(" = Patient/").append(m.patientId());
            m.identifiers().stream().filter(i -> "MB".equals(i.type())).findFirst().ifPresent(i -> sb.append(", member id ").append(i.value()));
        }
        return sb.append('.').toString();
    }

    private static String typeCode(JsonNode identifier) {
        JsonNode coding = identifier.path("type").path("coding");
        return coding.isArray() && coding.size() > 0 ? coding.get(0).path("code").asText(null) : null;
    }

    private static String host(String system) {
        try {
            String h = URI.create(system).getHost();
            return h == null ? system : h;
        } catch (IllegalArgumentException e) {
            return system;
        }
    }
}
