package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.patient.PatientWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping(value = "/api/v1/environments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Patient data", description = "Coverage, claims, prior authorizations and clinical data of one member with profile checks")
public class PatientController {

    private static final Set<String> RESERVED = Set.of("type", "since");

    private final EnvironmentService environments;
    private final PatientWorkspaceService workspace;

    public PatientController(EnvironmentService environments, PatientWorkspaceService workspace) {
        this.environments = environments;
        this.workspace = workspace;
    }

    @Operation(summary = "Patient header, profile checks and counts per data class")
    @GetMapping("/patients/{pid}/overview")
    public PatientWorkspaceService.Overview overview(@PathVariable String id, @PathVariable String pid) {
        return workspace.overview(env(id), pid);
    }

    @Operation(summary = "Data classes the overview counts")
    @GetMapping("/patients/data-classes")
    public List<PatientWorkspaceService.DataClass> dataClasses(@PathVariable String id) {
        return PatientWorkspaceService.DATA_CLASSES;
    }

    @Operation(summary = "Coverage of the member (with payor organizations when the server supports _include)")
    @GetMapping("/patients/{pid}/coverage")
    public PatientWorkspaceService.ResourceList coverage(@PathVariable String id, @PathVariable String pid) {
        return workspace.coverage(env(id), pid);
    }

    @Operation(summary = "Claims (C4BB ExplanationOfBenefit, use=claim)", description = "Optional type (institutional, professional, pharmacy, oral, vision), since (_lastUpdated) and any other EOB search parameter.")
    @GetMapping("/patients/{pid}/claims")
    public PatientWorkspaceService.ResourceList claims(@PathVariable String id, @PathVariable String pid,
                                                       @RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String since,
                                                       @RequestParam Map<String, String> query) {
        return workspace.claims(env(id), pid, type, since, extra(query));
    }

    @Operation(summary = "Prior authorizations (PDex PriorAuthorization EOBs, use=preauthorization) summarised per CMS-0057-F")
    @GetMapping("/patients/{pid}/prior-auth")
    public PatientWorkspaceService.PriorAuthList priorAuth(@PathVariable String id, @PathVariable String pid,
                                                           @RequestParam(required = false) String since) {
        return workspace.priorAuthorizations(env(id), pid, since);
    }

    @Operation(summary = "Any resource type by patient (US Core clinical data, MedicationDispense, Provenance...) with extra search parameters")
    @GetMapping("/patients/{pid}/clinical/{type}")
    public PatientWorkspaceService.ResourceList clinical(@PathVariable String id, @PathVariable String pid, @PathVariable String type,
                                                         @RequestParam Map<String, String> query) {
        return workspace.clinical(env(id), pid, type, extra(query));
    }

    @Operation(summary = "One resource with its columns, profile checks and (for a PA EOB) the prior-auth summary")
    @GetMapping("/resources/{type}/{rid}")
    public PatientWorkspaceService.ResourceDetail resource(@PathVariable String id, @PathVariable String type, @PathVariable String rid) {
        return workspace.resource(env(id), type, rid);
    }

    private Environment env(String id) {
        return environments.require(id);
    }

    private static Map<String, String> extra(Map<String, String> query) {
        Map<String, String> extra = new HashMap<>(query);
        RESERVED.forEach(extra::remove);
        return extra;
    }
}
