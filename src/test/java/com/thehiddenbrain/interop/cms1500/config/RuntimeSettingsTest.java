package com.thehiddenbrain.interop.cms1500.config;

import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.pdf.Cms1500Fields;
import com.thehiddenbrain.interop.cms1500.pdf.TemplateSource;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TestSettings;
import com.thehiddenbrain.interop.cms1500.support.TestTemplate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSettingsTest {

    @TempDir
    Path dir;
    Path settingsFile;
    Cms1500Properties defaults;

    @BeforeEach
    void defaults() {
        settingsFile = dir.resolve("config").resolve("settings.json");
        defaults = TestSettings.properties(dir.resolve("attachments"), dir.resolve("bundles"), settingsFile);
    }

    private RuntimeSettings runtime() {
        return new RuntimeSettings(defaults, new TemplateSource(new DefaultResourceLoader()));
    }

    private static SettingsUpdate outputOnly(String outputRoot) {
        return new SettingsUpdate(null, null, null, null, null, outputRoot, null, null, null, null);
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\");
    }

    @Test
    void startsFromYamlValuesWhenNoFileExists() throws IOException {
        RuntimeSettings settings = runtime();
        assertThat(settings.current()).isEqualTo(defaults);
        assertThat(settings.overridden()).isFalse();
        assertThat(settings.overridesFile()).isEqualTo(settingsFile.toAbsolutePath());
        SettingsView view = settings.view();
        assertThat(view.attachments().rootExists()).isFalse();
        assertThat(view.output().rootExists()).isFalse();
        assertThat(view.note()).isNull();
        Files.createDirectories(dir.resolve("attachments"));
        assertThat(settings.view().attachments().rootExists()).isTrue();
    }

    @Test
    void updateAppliesImmediatelyPersistsAndSurvivesRestart() throws IOException {
        RuntimeSettings settings = runtime();
        Path newOutput = dir.resolve("elsewhere");
        SettingsUpdate change = new SettingsUpdate(null, null, List.of(".PDF", "png", "pdf"),
                Cms1500Properties.UnsupportedPolicy.SKIP, null, newOutput.toString(), false, null, null, "  Continued ");

        Cms1500Properties updated = settings.update(change);

        assertThat(updated.output().rootPath()).isEqualTo(newOutput.toAbsolutePath());
        assertThat(updated.output().overwrite()).isFalse();
        assertThat(updated.attachments().allowedExtensions()).containsExactly("pdf", "png");
        assertThat(updated.attachments().unsupported()).isEqualTo(Cms1500Properties.UnsupportedPolicy.SKIP);
        assertThat(updated.attachments().whenNone()).as("untouched").isEqualTo(Cms1500Properties.WhenNonePolicy.WARN);
        assertThat(updated.attachments().root()).as("untouched").isEqualTo(defaults.attachments().root());
        assertThat(updated.form().continuationMarker()).isEqualTo("Continued");
        assertThat(updated.settingsFile()).isEqualTo(defaults.settingsFile());
        assertThat(settings.current()).isSameAs(updated);
        assertThat(settings.overridden()).isTrue();
        assertThat(settingsFile).exists();
        assertThat(Files.readString(settingsFile)).contains("\"outputRoot\"").contains("elsewhere").contains("\"overwrite\" : false");
        assertThat(Files.list(settingsFile.getParent())).as("no temp file left").hasSize(1);

        RuntimeSettings restarted = runtime();
        assertThat(restarted.overridden()).isTrue();
        assertThat(restarted.current()).isEqualTo(updated);
    }

    @Test
    void invalidUpdateIsRejectedAsAWholeAndNothingChanges() {
        RuntimeSettings settings = runtime();
        SettingsUpdate bad = new SettingsUpdate(" ", "  ", List.of("pdf", "p df", ""), null, null, "",
                null, null, null, "x".repeat(21));
        assertThatThrownBy(() -> settings.update(bad))
                .isInstanceOf(ClaimException.class)
                .satisfies(e -> {
                    ClaimException ce = (ClaimException) e;
                    assertThat(ce.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ce.getDetails()).extracting(ViolationDetail::getField).containsExactlyInAnyOrder(
                            "template", "attachmentsRoot", "outputRoot", "allowedExtensions[1]", "allowedExtensions[2]",
                            "continuationMarker");
                });
        assertThatThrownBy(() -> settings.update(new SettingsUpdate(null, null, List.of(), null, null, null, null, null, null, null)))
                .isInstanceOf(ClaimException.class).hasMessageContaining("allowedExtensions");
        assertThatThrownBy(() -> settings.update(null))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(settings.current()).isEqualTo(defaults);
        assertThat(settingsFile).doesNotExist();
        assertThat(settings.overridden()).isFalse();
    }

    @Test
    void unusableTemplateIsRejectedWithoutChangingAnything() throws IOException {
        RuntimeSettings settings = runtime();
        Path flat = dir.resolve("flat.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(flat.toFile());
        }
        Path outputBefore = settings.current().output().rootPath();

        for (String template : List.of(dir.resolve("missing.pdf").toString(), flat.toString())) {
            assertThatThrownBy(() -> settings.update(new SettingsUpdate(template, null, null, null, null,
                    dir.resolve("changed-too").toString(), null, null, null, null)))
                    .isInstanceOf(ClaimException.class)
                    .satisfies(e -> {
                        ClaimException ce = (ClaimException) e;
                        assertThat(ce.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(ce.getDetails()).singleElement().extracting(ViolationDetail::getField).isEqualTo("template");
                    });
        }
        assertThat(settings.current().output().rootPath()).as("output change discarded with the bad template").isEqualTo(outputBefore);
        assertThat(settings.current().template()).isEqualTo(TestSettings.TEMPLATE);
        assertThat(settingsFile).doesNotExist();
    }

    @Test
    void templateAndFormOptionsAreReloadedOnChange() throws IOException {
        TemplateSource templates = new TemplateSource(new DefaultResourceLoader());
        RuntimeSettings settings = new RuntimeSettings(defaults, templates);
        Path copy = dir.resolve("my-cms1500.pdf");
        Files.write(copy, TestTemplate.bytes());

        settings.update(new SettingsUpdate(copy.toString(), null, null, null, null, null, null, false, false, null));

        assertThat(templates.loadedTemplate()).isEqualTo(copy.toString());
        try (PDDocument page = templates.current().fillPage(ClaimFixtures.fullClaim("CLM-OPT"), 0, 1)) {
            var form = page.getDocumentCatalog().getAcroForm();
            assertThat(form.getField(Cms1500Fields.PATIENT_NAME).getValueAsString()).as("uppercase off").isEqualTo("Doe, John A");
            assertThat(form.getField(Cms1500Fields.diagnosis(1)).getValueAsString()).as("periods kept").isEqualTo("S82.101A");
        }
        assertThat(Files.readString(settingsFile)).contains("my-cms1500.pdf");
    }

    @Test
    void resetDeletesTheFileAndReturnsToYamlValues() {
        RuntimeSettings settings = runtime();
        settings.update(outputOnly(dir.resolve("x").toString()));
        assertThat(settingsFile).exists();

        Cms1500Properties after = settings.reset();

        assertThat(after).isEqualTo(defaults);
        assertThat(settings.current()).isEqualTo(defaults);
        assertThat(settings.overridden()).isFalse();
        assertThat(settingsFile).doesNotExist();
        assertThat(settings.reset()).as("idempotent").isEqualTo(defaults);
    }

    @Test
    void unreadableOrInvalidSettingsFileIsIgnoredWithANote() throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{not json");
        RuntimeSettings corrupt = runtime();
        assertThat(corrupt.current()).isEqualTo(defaults);
        assertThat(corrupt.overridden()).isFalse();
        assertThat(corrupt.view().note()).contains("ignored");

        Files.writeString(settingsFile, "{\"outputRoot\":\"\",\"allowedExtensions\":[\"pdf\"]}");
        RuntimeSettings invalid = runtime();
        assertThat(invalid.current()).isEqualTo(defaults);
        assertThat(invalid.view().note()).contains("outputRoot");

        Files.writeString(settingsFile, "{\"template\":\"" + json(dir.resolve("gone.pdf").toString()) + "\"}");
        RuntimeSettings badTemplate = runtime();
        assertThat(badTemplate.current()).isEqualTo(defaults);
        assertThat(badTemplate.view().note()).contains("gone.pdf");
        assertThat(badTemplate.view().template()).isEqualTo(TestSettings.TEMPLATE);
    }

    @Test
    void unknownKeysInTheFileAreTolerated() throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{\"outputRoot\":\"" + json(dir.resolve("o").toString()) + "\",\"futureOption\":1}");
        RuntimeSettings settings = runtime();
        assertThat(settings.overridden()).isTrue();
        assertThat(settings.current().output().rootPath()).isEqualTo(dir.resolve("o").toAbsolutePath());
    }
}
