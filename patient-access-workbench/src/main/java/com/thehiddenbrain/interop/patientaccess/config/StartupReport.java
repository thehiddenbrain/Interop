package com.thehiddenbrain.interop.patientaccess.config;

import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

/** Logs the effective configuration at startup so a wrong mount or variable is visible immediately. */
@Component
public class StartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    /** Fails startup when the data folder cannot be written: better than a green readiness probe and a failing first save. */
    static void ensureWritable(java.nio.file.Path dir) {
        try {
            Files.createDirectories(dir);
            java.nio.file.Path probe = Files.createTempFile(dir, ".write-check", ".tmp");
            Files.deleteIfExists(probe);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("data folder " + dir + " is not writable (" + e.getMessage() + "); set PAW_DATA_DIR to a writable location", e);
        }
    }

    private final WorkbenchProperties properties;
    private final SecretCrypto crypto;
    private final BasicAuthFilter basicAuth;

    public StartupReport(WorkbenchProperties properties, SecretCrypto crypto, BasicAuthFilter basicAuth) {
        this.properties = properties;
        this.crypto = crypto;
        this.basicAuth = basicAuth;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureWritable(properties.dataDirPath());
        log.info("data folder: {} ({})", properties.dataDirPath(), Files.isDirectory(properties.dataDirPath()) ? "present" : "will be created");
        log.info("master key: {}", crypto.keySource());
        log.info("public base URL: {}", properties.publicBaseUrl().isBlank() ? "derived from requests" : properties.publicBaseUrl());
        log.info("UI: {}; demo FHIR server: {}; basic auth: {}", properties.ui().enabled() ? "enabled at /ui/" : "disabled",
                properties.demo().enabled() ? "enabled at /demo/fhir" : "disabled", basicAuth.enabled() ? "on" : "off");
        log.info("IG packages folder for full validation: {} ({})", properties.packagesDirPath(),
                Files.isDirectory(properties.packagesDirPath()) ? "present" : "absent, lite checks only");
    }
}
