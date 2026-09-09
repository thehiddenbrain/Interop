package com.thehiddenbrain.interop.cms1500.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Logs the effective folders at startup so a wrong mount or environment variable is visible immediately. */
@Component
public class StartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final RuntimeSettings settings;

    public StartupReport(RuntimeSettings settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        Cms1500Properties properties = settings.current();
        Path attachments = properties.attachments().rootPath();
        Path output = properties.output().rootPath();
        log.info("settings source: {}{}", settings.overridden() ? "overrides file " + settings.overridesFile() : "application.yaml",
                settings.view().note() == null ? "" : " (" + settings.view().note() + ")");
        log.info("attachments folder: {} ({})", attachments, Files.isDirectory(attachments) ? "present" : "MISSING - claims will fail with STORAGE_ERROR until it exists");
        log.info("bundle output folder: {} ({}, overwrite={})", output, Files.isDirectory(output) ? "present" : "will be created", properties.output().overwrite());
        log.info("attachment policy: allowed={}, unsupported={}, when-none={}", properties.attachments().allowedExtensionSet().stream().sorted().toList(),
                properties.attachments().unsupported(), properties.attachments().whenNone());
        log.info("test UI: {}", properties.ui().enabled() ? "enabled at /ui/" : "disabled");
    }
}
