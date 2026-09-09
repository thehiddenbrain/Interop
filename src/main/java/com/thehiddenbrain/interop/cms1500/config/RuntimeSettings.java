package com.thehiddenbrain.interop.cms1500.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.pdf.TemplateSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The settings the service actually runs with. They start as the application.yaml values;
 * changes made through the settings API are validated, applied immediately (the template is
 * reloaded when it or the form options change) and stored in a JSON file so they survive a
 * restart. Deleting the overrides returns to the yaml values.
 */
@Component
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);
    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,8}");
    private static final int MAX_MARKER_LENGTH = 20;

    private final Cms1500Properties defaults;
    private final Path overridesFile;
    private final TemplateSource templates;
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final AtomicReference<Cms1500Properties> current = new AtomicReference<>();
    private volatile boolean overridden;
    private volatile String note;

    public RuntimeSettings(Cms1500Properties defaults, TemplateSource templates) {
        this.defaults = defaults;
        this.templates = templates;
        this.overridesFile = defaults.settingsFilePath();
        Cms1500Properties effective = defaults;
        if (Files.isRegularFile(overridesFile)) {
            try {
                SettingsUpdate saved = mapper.readValue(overridesFile.toFile(), SettingsUpdate.class);
                List<ViolationDetail> problems = validate(saved);
                if (!problems.isEmpty()) {
                    throw new IOException("invalid values: " + problems.stream()
                            .map(v -> v.getField() + " " + v.getMessage()).toList());
                }
                effective = merge(defaults, saved);
                templates.load(effective.template(), effective.form());
                overridden = true;
                log.info("settings overrides loaded from {}", overridesFile);
            } catch (IOException | ClaimException e) {
                note = "settings file " + overridesFile + " ignored: " + e.getMessage();
                log.error(note);
                effective = defaults;
            }
        }
        if (!overridden) {
            templates.load(effective.template(), effective.form());
        }
        current.set(effective);
    }

    /** The live settings. */
    public Cms1500Properties current() {
        return current.get();
    }

    public Path overridesFile() {
        return overridesFile;
    }

    public boolean overridden() {
        return overridden;
    }

    public SettingsView view() {
        Cms1500Properties p = current();
        Path attachments = p.attachments().rootPath();
        Path output = p.output().rootPath();
        return new SettingsView(p.template(),
                new SettingsView.AttachmentsView(attachments.toString(), Files.isDirectory(attachments),
                        p.attachments().allowedExtensions(), p.attachments().unsupported(), p.attachments().whenNone()),
                new SettingsView.OutputView(output.toString(), Files.isDirectory(output),
                        Files.isDirectory(output) && Files.isWritable(output), p.output().overwrite()),
                new SettingsView.FormView(p.form().uppercase(), p.form().stripDiagnosisPeriods(), p.form().continuationMarker()),
                overridesFile.toString(), overridden, note);
    }

    /** Validates, applies and persists a change; nothing changes if any part of it is rejected. */
    public synchronized Cms1500Properties update(SettingsUpdate update) {
        if (update == null) {
            throw new ClaimException(ErrorCode.MALFORMED_REQUEST, null, "settings body is required");
        }
        List<ViolationDetail> problems = validate(update);
        if (!problems.isEmpty()) {
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, null,
                    "settings rejected: " + problems.stream().map(v -> v.getField() + " " + v.getMessage()).toList(), problems);
        }
        Cms1500Properties merged = merge(current(), update);
        try {
            templates.load(merged.template(), merged.form()); // nothing is stored unless the template loads
        } catch (ClaimException e) {
            if (e.getCode() != ErrorCode.TEMPLATE_ERROR) {
                throw e;
            }
            ViolationDetail detail = ClaimException.detail("template", e.getMessage());
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, null, "settings rejected: " + e.getMessage(),
                    List.of(detail), e);
        }
        persist(merged);
        current.set(merged);
        overridden = true;
        note = null;
        log.info("settings updated: attachments={} output={} overwrite={} unsupported={} whenNone={} template={}",
                merged.attachments().rootPath(), merged.output().rootPath(), merged.output().overwrite(),
                merged.attachments().unsupported(), merged.attachments().whenNone(), merged.template());
        return merged;
    }

    /** Drops the overrides file and returns to the application.yaml values. */
    public synchronized Cms1500Properties reset() {
        templates.load(defaults.template(), defaults.form());
        try {
            Files.deleteIfExists(overridesFile);
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, null,
                    "cannot delete settings file " + overridesFile + ": " + e.getMessage(), e);
        }
        current.set(defaults);
        overridden = false;
        note = null;
        log.info("settings reset to application.yaml values");
        return defaults;
    }

    static List<ViolationDetail> validate(SettingsUpdate u) {
        List<ViolationDetail> problems = new ArrayList<>();
        if (u.template() != null && u.template().isBlank()) {
            problems.add(ClaimException.detail("template", "must not be blank"));
        }
        if (u.attachmentsRoot() != null && u.attachmentsRoot().isBlank()) {
            problems.add(ClaimException.detail("attachmentsRoot", "must not be blank"));
        }
        if (u.outputRoot() != null && u.outputRoot().isBlank()) {
            problems.add(ClaimException.detail("outputRoot", "must not be blank"));
        }
        if (u.allowedExtensions() != null) {
            if (u.allowedExtensions().isEmpty()) {
                problems.add(ClaimException.detail("allowedExtensions", "at least one extension is required"));
            }
            for (int i = 0; i < u.allowedExtensions().size(); i++) {
                String normalized = Cms1500Properties.normalizeExtension(u.allowedExtensions().get(i));
                if (!EXTENSION.matcher(normalized).matches()) {
                    problems.add(ClaimException.detail("allowedExtensions[" + i + "]",
                            "'" + u.allowedExtensions().get(i) + "' is not a file extension (letters and digits, up to 8)"));
                }
            }
        }
        if (u.continuationMarker() != null && u.continuationMarker().trim().length() > MAX_MARKER_LENGTH) {
            problems.add(ClaimException.detail("continuationMarker", "longer than " + MAX_MARKER_LENGTH + " characters"));
        }
        return problems;
    }

    static Cms1500Properties merge(Cms1500Properties base, SettingsUpdate u) {
        Cms1500Properties.Attachments a = base.attachments();
        Cms1500Properties.Output o = base.output();
        Cms1500Properties.Form f = base.form();
        List<String> extensions = u.allowedExtensions() == null ? a.allowedExtensions()
                : u.allowedExtensions().stream().map(Cms1500Properties::normalizeExtension).distinct().toList();
        return new Cms1500Properties(
                or(u.template(), base.template()).trim(),
                new Cms1500Properties.Attachments(or(u.attachmentsRoot(), a.root()).trim(), extensions,
                        or(u.unsupported(), a.unsupported()), or(u.whenNone(), a.whenNone())),
                new Cms1500Properties.Output(or(u.outputRoot(), o.root()).trim(), or(u.overwrite(), o.overwrite())),
                new Cms1500Properties.Form(or(u.uppercase(), f.uppercase()), or(u.stripDiagnosisPeriods(), f.stripDiagnosisPeriods()),
                        or(u.continuationMarker(), f.continuationMarker()).trim()),
                base.settingsFile(), base.ui());
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private void persist(Cms1500Properties settings) {
        try {
            Files.createDirectories(overridesFile.getParent());
            Path temp = overridesFile.resolveSibling(overridesFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), SettingsUpdate.snapshot(settings));
            Files.move(temp, overridesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, null,
                    "cannot write settings file " + overridesFile + ": " + e.getMessage(), e);
        }
    }
}
