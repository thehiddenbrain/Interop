package com.thehiddenbrain.interop.patientaccess.api;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Raw FHIR access through the workbench: the same headers, token and history as every other call,
 * but the tester chooses resource type and parameters. Responses are returned whatever the status
 * so error bodies (OperationOutcome) can be inspected.
 */
@RestController
@RequestMapping(value = "/api/v1/environments/{id}/fhir", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "FHIR requests", description = "Run any search, read or page against the environment with the workbench's token")
public class FhirProxyController {

    private final EnvironmentService environments;
    private final FhirGateway gateway;
    private final IgCatalog catalog;

    public FhirProxyController(EnvironmentService environments, FhirGateway gateway, IgCatalog catalog) {
        this.environments = environments;
        this.gateway = gateway;
        this.catalog = catalog;
    }

    public record ProxyResponse(String url, int status, long durationMs, String requestId, String contentType, List<String> warnings,
                                JsonNode body, String text) {

        static ProxyResponse of(HttpResult r, List<String> warnings) {
            JsonNode json = r.json();
            return new ProxyResponse(r.url(), r.status(), r.durationMs(), r.requestId(), r.contentType(), warnings, json,
                    json == null ? r.body() : null);
        }
    }

    @Operation(summary = "Search a resource type; every query parameter is passed through (IG warnings for undeclared parameters)")
    @GetMapping("/{type}")
    public ProxyResponse search(@PathVariable String id, @PathVariable String type, @RequestParam Map<String, String> query,
                                @RequestParam(defaultValue = "true") boolean authenticated) {
        Environment env = environments.require(id);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        query.forEach((k, v) -> {
            if (!k.equals("authenticated")) {
                params.add(k, v);
            }
        });
        List<String> warnings = catalog.validateParams(type, params.keySet());
        if (env.fhir().sendCountParam() && !params.containsKey("_count")) {
            params.add("_count", String.valueOf(gateway.pageSize(env)));
        }
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH);
        HttpResult r = gateway.get(env, UrlBuilder.searchUrl(env, type, params), authenticated ? options : options.unauthenticated());
        return ProxyResponse.of(r, warnings);
    }

    @Operation(summary = "Read one resource")
    @GetMapping("/{type}/{rid}")
    public ProxyResponse read(@PathVariable String id, @PathVariable String type, @PathVariable String rid,
                              @RequestParam(defaultValue = "true") boolean authenticated) {
        Environment env = environments.require(id);
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_READ);
        HttpResult r = gateway.get(env, UrlBuilder.readUrl(env, type, rid), authenticated ? options : options.unauthenticated());
        return ProxyResponse.of(r, List.of());
    }

    @Operation(summary = "Fetch a page by its link URL (must be inside the environment) or any relative path such as metadata")
    @GetMapping("/page")
    public ProxyResponse page(@PathVariable String id, @RequestParam String url, @RequestParam(defaultValue = "true") boolean authenticated) {
        Environment env = environments.require(id);
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH);
        HttpResult r = gateway.get(env, url, authenticated ? options : options.unauthenticated());
        return ProxyResponse.of(r, List.of());
    }
}
