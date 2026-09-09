package com.thehiddenbrain.interop.cms1500.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything under the {@code cms1500} prefix of application.yaml. These are the startup
 * defaults; {@link RuntimeSettings} holds the live values, which the test UI can change.
 */
@ConfigurationProperties(prefix = "cms1500")
@Validated
public record Cms1500Properties(
        /** Template PDF location (classpath: or file: URL, or a plain path). */
        @NotBlank String template,
        @Valid @NotNull Attachments attachments,
        @Valid @NotNull Output output,
        @Valid @NotNull Form form,
        /** JSON file that stores settings changed at runtime; absent until something is changed. */
        @DefaultValue("./data/cms1500-settings.json") String settingsFile,
        @Valid @DefaultValue Ui ui) {

    public Path settingsFilePath() {
        return Path.of(settingsFile).toAbsolutePath().normalize();
    }

    public record Attachments(
            /** Shared-drive folder that holds {@code <claimNumber>_<n>.<ext>} files. */
            @NotBlank String root,
            @NotEmpty List<String> allowedExtensions,
            @NotNull @DefaultValue("FAIL") UnsupportedPolicy unsupported,
            @NotNull @DefaultValue("WARN") WhenNonePolicy whenNone) {

        public Path rootPath() {
            return Path.of(root).toAbsolutePath().normalize();
        }

        public Set<String> allowedExtensionSet() {
            return allowedExtensions.stream()
                    .map(Cms1500Properties::normalizeExtension)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public record Output(
            /** Shared-drive folder that receives {@code <claimNumber>.pdf}. */
            @NotBlank String root,
            @DefaultValue("true") boolean overwrite) {

        public Path rootPath() {
            return Path.of(root).toAbsolutePath().normalize();
        }
    }

    public record Form(
            @DefaultValue("true") boolean uppercase,
            @DefaultValue("true") boolean stripDiagnosisPeriods,
            /** Printed in item 28 on every page but the last of a multi-page claim. */
            @DefaultValue("") String continuationMarker) {
    }

    /** The browser test UI and the settings API it uses. */
    public record Ui(@DefaultValue("true") boolean enabled) {
    }

    /** What to do with an attachment whose extension is not allowed. */
    public enum UnsupportedPolicy { FAIL, SKIP }

    /** What to do when no attachment matches the claim number. */
    public enum WhenNonePolicy { WARN, FAIL }

    /** {@code .PDF } becomes {@code pdf}. */
    public static String normalizeExtension(String extension) {
        return extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
    }
}
