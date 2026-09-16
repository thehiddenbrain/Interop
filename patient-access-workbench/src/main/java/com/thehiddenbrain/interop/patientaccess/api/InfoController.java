package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Info", description = "Version and effective settings")
public class InfoController {

    private final WorkbenchProperties properties;
    private final ObjectProvider<BuildProperties> build;
    private final IgCatalog catalog;
    private final SecretCrypto crypto;

    public InfoController(WorkbenchProperties properties, ObjectProvider<BuildProperties> build, IgCatalog catalog, SecretCrypto crypto) {
        this.properties = properties;
        this.build = build;
        this.catalog = catalog;
        this.crypto = crypto;
    }

    @Operation(summary = "Version, data folder, feature flags and IG versions")
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        BuildProperties b = build.getIfAvailable();
        out.put("name", "patient-access-workbench");
        out.put("version", b == null ? "dev" : b.getVersion());
        out.put("buildTime", b == null ? null : b.getTime());
        out.put("igs", catalog.igs());
        out.put("ui", properties.ui().enabled());
        out.put("demo", properties.demo().enabled());
        out.put("basicAuth", properties.security().basic().enabled());
        return out;
    }

    @Operation(summary = "Effective application settings (read-only; change them via environment variables or application.yaml)")
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataDir", properties.dataDirPath().toString());
        out.put("dataDirPresent", Files.isDirectory(properties.dataDirPath()));
        out.put("masterKeySource", crypto.keySource());
        out.put("publicBaseUrl", properties.publicBaseUrl());
        out.put("http", properties.http());
        out.put("history", properties.history());
        out.put("search", properties.search());
        out.put("conformance", properties.conformance());
        out.put("validationPackagesDir", properties.packagesDirPath().toString());
        out.put("validationPackagesPresent", Files.isDirectory(properties.packagesDirPath()));
        out.put("demoEnabled", properties.demo().enabled());
        out.put("basicAuthEnabled", properties.security().basic().enabled());
        return out;
    }
}
