package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

import java.nio.charset.StandardCharsets;
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
 * M-ENC and m3 of the second guardrail audit: no request leaves with an encoded slash, backslash,
 * semicolon or dot segment in its path, in any letter case and however often encoded, whatever the
 * environment and the method. The query is not touched. Each test repeats a request of the audit
 * (t60, t92, t20) at the gateway, on both profiles.
 */
class EncodedSeparatorGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PROD_A = "http://127.0.0.1:18742/pa/fhir";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture lockedProduction(String profile) {
        ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites()
                .build();
        fixture.activeEnvironmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A, "crdUrl", PROD_A,
                "crdOrderSignServiceId", "order-sign-crd");
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    @Test
    void theEncodedSeparatorsAreFoundInThePathInAnyCaseAndThroughRepeatedEncoding() {
        for (String path : List.of("/a/..%2F..%2FClaim", "/a/%2fb", "/a%5Cb", "/a%5cb", "/a%3Bv=1", "/a%3bv=1", "/a%252Fb", "/a%25252fb",
                "/a/%2e%2e/b", "/a/%2E/b", "/a/.%2e/b", "/a/%252e%252e/b", "/a/..%3B/b")) {
            assertThat(UrlNormal.encodedSeparator("http://h.example.invalid" + path)).as(path).isNotNull();
        }
        for (String fine : List.of("http://h.example.invalid/Claim/$inquire", "http://h.example.invalid/a%20b/c", "http://h.example.invalid/a/..x/b",
                "http://h.example.invalid/Claim?patient=Patient%2FS123456789&x=%3B", "http://h.example.invalid/Claim#%2F", "http://h.example.invalid/%25")) {
            assertThat(UrlNormal.encodedSeparator(fine)).as(fine).isNull();
        }
    }

    @Test
    void aCrdHookWhoseServiceIdTypedForTheRunHidesASubmitIsNotSentToLockedProduction() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = lockedProduction(profile);
            for (String hidden : List.of("..%2F..%2FClaim%2F%24submit", "..%2f..%2fClaim", "..%252F..%252FClaim", "..%5C..%5CClaim",
                    "%252e%252e/%252e%252e/Claim", "..%3B/..%3B/Claim")) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                        .withRunValues(Map.of("crdOrderSignServiceId", hidden)))).as(profile + " " + hidden)
                        .isInstanceOf(BadInputException.class)
                        .hasMessageContaining("CRD order-sign was not sent: the path of its url holds ")
                        .hasMessageContaining("which came from the variable \"crdOrderSignServiceId\" (typed for this run)")
                        .hasMessageNotContaining(hidden);
            }
            // An escaped dot segment the normal form decodes is a dot segment, removed and judged: here a create, blocked.
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                    .withRunValues(Map.of("crdOrderSignServiceId", "%2e%2e/%2e%2e/Claim")))).as(profile)
                    .isInstanceOf(org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException.class);
        }
    }

    @Test
    void anInquiryWhoseBaseTypedForTheRunHidesACreateIsNotSent() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = lockedProduction(profile);
            for (String tail : List.of("..%2F..%2FClaim?x=", "..%3B/..%3B/Claim?x=")) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)
                        .withRunValues(Map.of("baseUrl", PROD_A + "/Claim/$inquire/" + tail)))).as(profile + " " + tail)
                        .isInstanceOf(BadInputException.class).hasMessageContaining("the variable \"baseUrl\" (typed for this run)");
            }
            // The plain inquiry still goes.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value()).as(profile)
                    .isEqualTo(PROD_A + "/Claim/$inquire");
        }
    }

    @Test
    void anEnvironmentWhoseBaseHoldsAnEncodedSeparatorIsReportedAtUploadAndCannotBeSentTo() {
        for (String profile : PROFILES) {
            for (String base : List.of("http://127.0.0.1:18742/pa%2Ffhir", "http://127.0.0.1:18742/pa/fhir%3Bv=1")) {
                ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).build();
                fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
                ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("s.json",
                        ("{\"name\": \"Spelling 14\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + base + "\"}]}")
                                .getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false);
                assertThat(upload.warnings()).as(profile + " " + base).anySatisfy(warning -> assertThat(warning)
                        .startsWith("The variable \"baseUrl\" holds an address whose path has an encoded slash, backslash, semicolon or "
                                + "dot segment in it"));
                fixture.store.activate("Spelling 14");
                fixture.collection(PostmanFiles.VENDOR_COLLECTION);
                for (EpaOperation operation : List.of(EpaOperation.PAS_SUBMIT, EpaOperation.PAS_INQUIRE, EpaOperation.SUBSCRIPTION_CREATE)) {
                    assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(operation))).as(profile + " " + base + " " + operation)
                            .isInstanceOf(BadInputException.class)
                            .hasMessageContaining("which came from the variable \"baseUrl\" (the active environment)");
                }
            }
        }
    }

    @Test
    void aUrlPartAndALiteralAreNamedAndAQueryKeepsItsEscapes() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).build();
            fixture.activeEnvironmentOf("Audit Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "http://127.0.0.1:18745/sbx");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "../Claim")))
                    .as(profile).isInstanceOf(BadInputException.class);
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "a/b")))
                    .as(profile).isInstanceOf(BadInputException.class);

            StoredCollection literal = fixture.collectionOf("Literal", items(
                    item("search encoded", "GET", "{{baseUrl}}/Claim?patient=Patient%2FS123456789&_has=x%3By"),
                    item("read encoded path", "GET", "{{baseUrl}}/Claim%2F123")));
            PreparedCall search = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(literal, "search encoded")));
            assertThat(search.url().value()).as(profile).isEqualTo("http://127.0.0.1:18745/sbx/Claim?patient=Patient%2FS123456789&_has=x%3By");
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(literal, "read encoded path"))))
                    .as(profile).isInstanceOf(BadInputException.class)
                    .hasMessageContaining("which came from the request \"read encoded path\" of the collection \"Literal\"");
        }
    }
}
