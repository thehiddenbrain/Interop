package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentProbe;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/environments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Environments", description = "Vendor FHIR environments (UAT, Prod, sandbox) with their auth settings")
public class EnvironmentController {

    private final EnvironmentService environments;
    private final EnvironmentProbe probe;

    public EnvironmentController(EnvironmentService environments, EnvironmentProbe probe) {
        this.environments = environments;
        this.probe = probe;
    }

    @Operation(summary = "Test the connection: metadata, SMART discovery, token, one authenticated call")
    @PostMapping("/{id}/test")
    public EnvironmentProbe.Result test(@PathVariable String id) {
        return probe.probe(environments.require(id));
    }

    @Operation(summary = "All environments, secrets masked")
    @GetMapping
    public List<EnvironmentView> list() {
        return environments.list();
    }

    @Operation(summary = "One environment")
    @GetMapping("/{id}")
    public EnvironmentView get(@PathVariable String id) {
        return environments.get(id);
    }

    @Operation(summary = "Create an environment", description = "Secret fields (clientSecret, staticToken, privateKeyJwk, secret header values) are encrypted at rest and never returned.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView create(@RequestBody EnvironmentInput input) {
        return environments.create(input);
    }

    @Operation(summary = "Update an environment", description = "Only the fields sent change. A secret sent as null is kept, as \"\" is cleared. Send the version you loaded; a stale version is rejected with 409.")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EnvironmentView update(@PathVariable String id, @RequestBody EnvironmentInput input) {
        return environments.update(id, input);
    }

    @Operation(summary = "Copy an environment (e.g. UAT to Prod) including its secrets")
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView duplicate(@PathVariable String id, @RequestParam(required = false) String name) {
        return environments.duplicate(id, name);
    }

    @Operation(summary = "Delete an environment")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        environments.delete(id);
    }
}
