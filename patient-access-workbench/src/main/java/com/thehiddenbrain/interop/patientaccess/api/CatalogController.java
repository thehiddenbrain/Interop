package com.thehiddenbrain.interop.patientaccess.api;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/v1/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "IG catalog", description = "Search parameters, profiles and codes from the CARIN BB, PDex, US Core and Formulary IGs")
public class CatalogController {

    private final IgCatalog catalog;

    public CatalogController(IgCatalog catalog) {
        this.catalog = catalog;
    }

    public record Summary(Map<String, IgCatalog.IgInfo> igs, List<String> resourceTypes, List<IgCatalog.EobProfile> eobProfiles) {
    }

    @Operation(summary = "IG versions, resource types and EOB profiles")
    @GetMapping
    public Summary summary() {
        return new Summary(catalog.igs(), List.copyOf(catalog.resources().keySet()), catalog.eobProfiles());
    }

    @Operation(summary = "Everything the IGs say about one resource type (profiles, search parameters, combos, includes)")
    @GetMapping("/resources/{type}")
    public IgCatalog.ResourceSpec resource(@PathVariable String type) {
        return catalog.resource(type).orElseThrow(() -> WorkbenchException.notFound("resource type", type));
    }

    @Operation(summary = "All resource specs at once (drives the UI search options)")
    @GetMapping("/resources")
    public Map<String, IgCatalog.ResourceSpec> resources() {
        return catalog.resources();
    }

    @Operation(summary = "Element rules (required / must-support) of a profile by canonical URL")
    @GetMapping("/profiles")
    public Object profiles(@RequestParam(required = false) String url) {
        if (url == null) {
            return catalog.profiles().values().stream().map(p -> Map.of("url", p.url(), "name", p.name(), "type", p.type(), "ig", p.ig())).toList();
        }
        return catalog.profile(url).orElseThrow(() -> WorkbenchException.notFound("profile", url));
    }

    @Operation(summary = "Code systems and value sets used to label claims and prior-auth data")
    @GetMapping("/codes")
    public Map<String, JsonNode> codes() {
        return Map.of("codeSystems", catalog.codeSystems(), "valueSets", catalog.valueSets());
    }
}
