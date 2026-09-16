package com.thehiddenbrain.interop.patientaccess.vendor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendorCatalogTest {

    @Test
    void bundledFileDescribesOnyxSafhir() {
        var presets = VendorCatalog.loadBundled();
        assertThat(presets).extracting(VendorPreset::key).contains("onyx-safhir");
        VendorPreset onyx = presets.stream().filter(p -> p.key().equals("onyx-safhir")).findFirst().orElseThrow();
        assertThat(onyx.name()).isEqualTo("Onyx SAFHIR");
        assertThat(onyx.fhirBaseUrl()).isEqualTo("{host}/v1/api/pdex");
        assertThat(onyx.igBaseUrls()).containsEntry("c4bb", "{host}/v1/api/carin-bb").containsEntry("plannet", "{host}/v1/api/provider-directory");
        assertThat(onyx.auth()).containsEntry("mode", "SMART_AUTHORIZATION_CODE").containsEntry("tokenEndpoint", "{host}/v1/token")
                .containsEntry("audience", "{host}/v1").containsEntry("discoverEndpoints", false);
        assertThat(onyx.fhir()).containsEntry("allowNextLinkHostMismatch", true);
        assertThat(onyx.tierPatterns()).containsKeys("PROD", "UAT");
        assertThat(onyx.note()).contains("Onyx developer portal");
    }

    @Test
    void externalFileReplacesBundledOne(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("vendors.yaml");
        Files.writeString(file, """
                vendors:
                  - key: acme
                    name: Acme FHIR
                    fhirBaseUrl: "{host}/fhir"
                    auth:
                      mode: CLIENT_CREDENTIALS
                    identifierSystems:
                      - label: Member ID
                        system: https://acme.example.org/member
                        typeCode: MB
                        defaultForMemberId: true
                """);
        var presets = VendorCatalog.load(file);
        assertThat(presets).hasSize(1);
        assertThat(presets.get(0).identifierSystems()).singleElement().satisfies(s -> {
            assertThat(s.system()).isEqualTo("https://acme.example.org/member");
            assertThat(s.defaultForMemberId()).isTrue();
        });
        assertThat(presets.get(0).igBaseUrls()).isEmpty();
        assertThat(presets.get(0).headers()).isEmpty();
    }

    @Test
    void brokenFilesFailWithTheFileNameNotASilentFallback(@TempDir Path dir) throws IOException {
        Path typo = dir.resolve("typo.yaml");
        Files.writeString(typo, "vendors:\n  - key: acme\n    name: Acme\n    fhirBaseUrls: x\n");
        assertThatThrownBy(() -> VendorCatalog.load(typo)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(typo.toString()).hasMessageContaining("fhirBaseUrls");

        Path noKey = dir.resolve("nokey.yaml");
        Files.writeString(noKey, "vendors:\n  - name: Acme\n");
        assertThatThrownBy(() -> VendorCatalog.load(noKey)).hasMessageContaining("needs a key");

        Path dup = dir.resolve("dup.yaml");
        Files.writeString(dup, "vendors:\n  - key: a\n    name: A\n  - key: a\n    name: B\n");
        assertThatThrownBy(() -> VendorCatalog.load(dup)).hasMessageContaining("'a' twice");

        Path badKey = dir.resolve("badkey.yaml");
        Files.writeString(badKey, "vendors:\n  - key: Bad Key\n    name: B\n");
        assertThatThrownBy(() -> VendorCatalog.load(badKey)).hasMessageContaining("lower-case");
    }
}
