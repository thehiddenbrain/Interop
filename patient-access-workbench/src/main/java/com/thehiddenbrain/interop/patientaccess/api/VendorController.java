package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.vendor.VendorCatalog;
import com.thehiddenbrain.interop.patientaccess.vendor.VendorPreset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/vendors", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Vendors", description = "Vendor presets from vendors.yaml that pre-fill an environment from a tenant host")
public class VendorController {

    private final VendorCatalog catalog;

    public VendorController(VendorCatalog catalog) {
        this.catalog = catalog;
    }

    public record Vendors(String source, List<VendorPreset> vendors) {
    }

    @Operation(summary = "All vendor presets and the file they were read from")
    @GetMapping
    public Vendors list() {
        return new Vendors(catalog.source(), catalog.presets());
    }

    @Operation(summary = "One vendor preset by key")
    @GetMapping("/{key}")
    public VendorPreset get(@PathVariable String key) {
        return catalog.preset(key).orElseThrow(() -> WorkbenchException.notFound("vendor preset", key));
    }
}
