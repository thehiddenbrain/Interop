package com.thehiddenbrain.interop.patientaccess.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.conformance.FullValidator;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/validation", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Validation", description = "Full HL7 profile validation (HAPI validator; IG packages from paw.validation.packages-dir)")
public class ValidationController {

    private final FullValidator validator;
    private final EnvironmentService environments;
    private final FhirGateway gateway;

    public ValidationController(FullValidator validator, EnvironmentService environments, FhirGateway gateway) {
        this.validator = validator;
        this.environments = environments;
        this.gateway = gateway;
    }

    public record ValidateRequest(JsonNode resource, String profile) {
    }

    @Operation(summary = "Whether IG packages are loaded")
    @GetMapping("/status")
    public FullValidator.Status status() {
        return validator.status();
    }

    @Operation(summary = "Validate a resource you post (optionally against a profile URL)")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public FullValidator.Result validate(@RequestBody ValidateRequest request) {
        return validator.validate(request.resource(), request.profile());
    }

    @Operation(summary = "Read a resource from the environment and validate it")
    @GetMapping("/environments/{id}/{type}/{rid}")
    public FullValidator.Result validateRemote(@PathVariable String id, @PathVariable String type, @PathVariable String rid,
                                               @RequestParam(required = false) String profile) {
        JsonNode resource = gateway.read(environments.require(id), type, rid, FhirGateway.Options.of(FhirGateway.PURPOSE_READ));
        return validator.validate(resource, profile);
    }
}
