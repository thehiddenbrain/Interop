package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.error.BadInputException;

/**
 * N3 of the check of the guardrail fixes: an overlong or look-alike encoding of a slash is an
 * encoded separator. A FHIR path is plain ASCII, so no request leaves with a percent-escape of a
 * byte above 0x7F in its path, by the same rule and the same refusal as the other encoded
 * separators, and an environment whose base address holds one is reported at upload. The query is
 * not touched. Each test repeats a request of the check (t61, t20b) at the gateway, on both profiles.
 */
class NonAsciiEscapeGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PROD_A = "http://127.0.0.1:18742/pa/fhir";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture lockedProduction(String profile) {
        ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites().build();
        fixture.activeEnvironmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A, "crdUrl", PROD_A,
                "crdOrderSignServiceId", "order-sign-crd");
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    @Test
    void anEscapeAboveAsciiInThePathIsFoundInAnyCaseAndThroughRepeatedEncoding() {
        for (String path : List.of("/a/..%c0%af..%c0%afClaim", "/a/..%C0%AF", "/a/..%E2%88%95..", "/a/..%EF%BC%8F..", "/a/%e0%80%af",
                "/a/%C1%9C", "/a/%25C0%25AF", "/a%c3%a9b", "/a/%80")) {
            String found = UrlNormal.encodedSeparator("http://h.example.invalid" + path);
            assertThat(found).as(path).isNotNull();
            assertThat(UrlNormal.aboveAscii(found)).as(path).isTrue();
        }
        // The query and the fragment keep their escapes, and an escape of ASCII that is no separator is fine.
        for (String fine : List.of("http://h.example.invalid/Patient?name=%C3%A9", "http://h.example.invalid/a#%C0%AF",
                "http://h.example.invalid/a%20b", "http://h.example.invalid/a%7Eb")) {
            assertThat(UrlNormal.encodedSeparator(fine)).as(fine).isNull();
        }
    }

    @Test
    void aCrdHookWhoseServiceIdHidesASubmitBehindAnOverlongOrLookAlikeSlashIsNotSent() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = lockedProduction(profile);
            for (String hidden : List.of("..%c0%af..%c0%afClaim%c0%af%24submit", "..%E2%88%95..%E2%88%95Claim", "..%EF%BC%8F..%EF%BC%8FClaim",
                    "..%25c0%25af..%25c0%25afClaim")) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                        .withRunValues(Map.of("crdOrderSignServiceId", hidden)))).as(profile + " " + hidden)
                        .isInstanceOf(BadInputException.class)
                        .hasMessageContaining("CRD order-sign was not sent: the path of its url holds a percent-escape of a byte above 0x7F (")
                        .hasMessageContaining("), the form an overlong or look-alike separator is written in and one a FHIR path never needs, "
                                + "which came from")
                        .hasMessageContaining("which came from the variable \"crdOrderSignServiceId\" (typed for this run)")
                        .hasMessageNotContaining(hidden);
            }
            // The plain service id is prepared.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                    .withRunValues(Map.of("crdOrderSignServiceId", "order-sign-crd"))).url().value()).as(profile)
                    .endsWith("/order-sign-crd");
        }
    }

    @Test
    void anEnvironmentWhoseBaseHoldsAnOverlongSlashIsReportedAtUploadAndCannotBeSentTo() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites().build();
            fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
            ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("s.json", ("{\"name\": \"Overlong\", \"values\": "
                    + "[{\"key\": \"baseUrl\", \"value\": \"http://127.0.0.1:18742/pa%c0%affhir\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    EnvironmentKind.SANDBOX, true);
            assertThat(upload.warnings()).as(profile).contains("The variable \"baseUrl\" holds an address whose path has an encoded slash, "
                    + "backslash, semicolon or dot segment in it, such as %2F, or a percent-escape of a byte above 0x7F, such as %C0. A "
                    + "server in front of the service can decode it before it reads the path, so no request is sent to that address. "
                    + "Write the path without it and upload the environment again.");
            fixture.store.activate("Overlong");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT))).as(profile)
                    .isInstanceOf(BadInputException.class).hasMessageContaining("(%C0)")
                    .hasMessageContaining("which came from the variable \"baseUrl\" (the active environment)");
        }
    }
}
