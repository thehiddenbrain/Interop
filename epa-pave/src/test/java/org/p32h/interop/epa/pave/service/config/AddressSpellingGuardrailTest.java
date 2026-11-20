package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * B1 of the second guardrail audit: an address is read exactly as the HTTP client reads it, and
 * an address that cannot be read that way is never taken as "not production". Each test repeats
 * a request of the audit (t20, t90, t91, t94) at the gateway, on both profiles.
 */
class AddressSpellingGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PROD_A = "http://127.0.0.1:18742/pa/fhir";
    private static final String PROD_B = "http://127.0.0.1:18743/pb/fhir";
    private static final String SANDBOX = "http://127.0.0.1:18745/sbx";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile);
    }

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static ProdWriteBlockedException blocked(ConfigFixture fixture, CallIntent intent) {
        try {
            PreparedCall call = fixture.gateway.prepare(intent);
            throw new AssertionError("The guardrail prepared a call it should have refused, to " + call.url().value());
        } catch (ProdWriteBlockedException e) {
            return e;
        }
    }

    @Test
    void aPortWithLeadingZerosIsTheAddressTheClientConnectsTo() {
        UrlNormal.Destination prod = UrlNormal.destination(PROD_A);
        for (String spelling : List.of("http://127.0.0.1:0018742/pa/fhir", "http://127.0.0.1:018742/pa/fhir",
                "http://127.0.0.1:00000000000018742/pa/fhir", "HTTP://127.0.0.1:18742/pa/fhir", "http://LOCALHOST:18742/pa/fhir",
                "http://user@127.0.0.1:18742/pa/fhir", "http://127.000.000.001:18742/pa/fhir", "http://2130706433:18742/pa/fhir",
                "http://[::ffff:127.0.0.1]:18742/pa/fhir")) {
            UrlNormal.Destination read = UrlNormal.destination(spelling);
            assertThat(read.readable()).as(spelling).isTrue();
            assertThat(read.port()).as(spelling).isEqualTo(18742);
            assertThat(prod.covers(read)).as(spelling).isTrue();
        }
        // An explicit default port is the default port.
        assertThat(UrlNormal.destination("http://example.invalid:80/x")).isEqualTo(UrlNormal.destination("http://example.invalid/x"));
        assertThat(UrlNormal.destination("https://example.invalid:0443/x").port()).isEqualTo(443);
        // A host of digits and dots is read in decimal, as the JDK reads it: 0177 is 177, not 127.
        assertThat(prod.covers(UrlNormal.destination("http://0177.0.0.1:18742/pa/fhir"))).isFalse();
        // The client refuses these, so they are not readable, and are compared leniently.
        for (String unreadable : List.of("http://127.0.0.1:99999/pa/fhir", "http://127.1:18742/pa/fhir", "http://0x7f.0.0.1:18742/pa/fhir",
                "http://127.0.0.1:4294985038/pa/fhir")) {
            UrlNormal.Destination read = UrlNormal.destination(unreadable);
            assertThat(read.readable()).as(unreadable).isFalse();
            assertThat(prod.overlaps(read)).as(unreadable).isTrue();
            assertThat(UrlNormal.sendable(unreadable)).as(unreadable).isNull();
        }
    }

    @Test
    void aSandboxAtTheProductionAddressWrittenWithLeadingZerosIsRefusedAtUpload() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
            for (String spelling : List.of("http://127.0.0.1:0018742/pa/fhir", "http://127.0.0.1:018742/pa/fhir")) {
                for (boolean confirmed : new boolean[] {false, true}) {
                    assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("Spelling 0", spelling),
                            EnvironmentKind.SANDBOX, confirmed)).as(profile + " " + spelling).isInstanceOf(ValidationException.class)
                            .hasMessageContaining("is an address of the production environment \"Audit Live A\"");
                }
            }
        }
    }

    @Test
    void aProductionEnvironmentWrittenWithLeadingZerosIsRememberedAtItsRealPort() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("Zero Prod", EnvironmentKind.PROD, "baseUrl", "http://127.0.0.1:0018745/zp/fhir");
            assertThat(fixture.store.snapshot().remembered()).as(profile).extracting(known -> known.destination().shown())
                    .containsExactly("http://127.0.0.1:18745/zp/fhir");
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("Plain Copy", "http://127.0.0.1:18745/zp/fhir"),
                    EnvironmentKind.SANDBOX, true)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("\"Zero Prod\"");
        }
    }

    @Test
    void aProductionAddressWithLeadingZerosInApplicationYmlIsNotWrittenToFromASandbox() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().onyx("variables.ymlZero", "http://127.0.0.1:0018743/pb/fhir").build();
            fixture.environmentOf("Audit Live B", EnvironmentKind.PROD, "baseUrl", PROD_B);
            fixture.activeEnvironmentOf("Audit Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "{{ymlZero}}");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            assertThat(refusal.getMessage()).as(profile).contains("belongs to the production environment \"Audit Live B\"");
        }
    }

    @Test
    void aCollectionLiteralWithLeadingZerosIsRefusedWhileAnotherProductionIsUnlockedAndFromASandbox() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
            fixture.environmentOf("Audit Live B", EnvironmentKind.PROD, "baseUrl", PROD_B);
            fixture.environmentOf("Audit Sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX);
            StoredCollection literal = fixture.collectionOf("Literal", items(item("transaction zero", "POST", "http://127.0.0.1:0018743/pb/fhir")));
            for (String active : List.of("Audit Live A", "Audit Sandbox")) {
                fixture.store.activate(active);
                ProdUnlock unlock = active.equals("Audit Live A") ? fixture.gateway.unlock("Audit Live A") : null;
                ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED)
                        .withRequest(ref(literal, "transaction zero")).withUnlock(unlock));
                assertThat(refusal.getMessage()).as(profile + " " + active).contains("belongs to the production environment \"Audit Live B\"");
            }
        }
    }

    @Test
    void aWriteToAnAddressThatCannotBeReadIsRefusedAndNamesWhereItCameFrom() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("u.json",
                    environment("Odd Sandbox", "http://127.0.0.1:99999/sbx"), EnvironmentKind.SANDBOX, false);
            assertThat(upload.warnings()).as(profile).anySatisfy(warning -> assertThat(warning).startsWith("The variable \"baseUrl\" holds an "
                    + "address whose scheme, host and port this tool cannot read the way the HTTP client reads them"));
            assertThat(fixture.service.environments().environments().get(0).addressWarnings()).as(profile).hasSize(1);
            assertThat(fixture.service.activate("Odd Sandbox", null).addressWarnings()).as(profile).hasSize(1);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);

            for (CallIntent intent : List.of(CallIntent.of(EpaOperation.PAS_SUBMIT), CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE))) {
                ProdWriteBlockedException refusal = blocked(fixture, intent);
                assertThat(refusal.getMessage()).as(profile).contains("is blocked: the scheme, host and port it would go to could not be read "
                        + "the way the HTTP client reads them, so this tool cannot tell whether that address is production. The address "
                        + "comes from the variable \"baseUrl\" (the active environment).");
                assertThat(refusal.action()).as(profile).startsWith("Nothing was sent.");
            }

            // Written literally in a collection: the request is named.
            StoredCollection literal = fixture.collectionOf("Literal", items(item("odd literal", "POST", "http://127.1:18799/fhir/Claim/$submit")));
            ProdWriteBlockedException named = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(literal, "odd literal")));
            assertThat(named.getMessage()).as(profile).contains("The address comes from the request \"odd literal\" of the collection \"Literal\".");
        }
    }

    @Test
    void anUnreadableAddressInApplicationYmlIsRefusedForAWriteAndNamesTheSetting() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().onyx("pas.url", "http://127.0.0.1:0x10/fhir").build();
            fixture.activeEnvironmentOf("Plain Sandbox", EnvironmentKind.SANDBOX, "other", "value");
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            assertThat(refusal.getMessage()).as(profile).contains("The address comes from application.yml: onyx.pas.url");
        }
    }

    @Test
    void anOrdinarySandboxWithAnExplicitPortStillSendsItsWrites() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
            ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("s.json", environment("Audit Sandbox", SANDBOX),
                    EnvironmentKind.SANDBOX, false);
            assertThat(upload.warnings()).as(profile).isEmpty();
            fixture.store.activate("Audit Sandbox");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value()).as(profile)
                    .isEqualTo(SANDBOX + "/Claim/$submit");
            // And production unlocked for itself, written with leading zeros in the url it is sent to.
            fixture.store.activate("Audit Live A");
            StoredCollection zeros = fixture.collectionOf("Zeros", items(item("submit zeros", "POST", "http://127.0.0.1:0018742/pa/fhir/Claim/$submit")));
            PreparedCall own = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withRequest(ref(zeros, "submit zeros"))
                    .withUnlock(fixture.gateway.unlock("Audit Live A")));
            assertThat(own.url().value()).as(profile).isEqualTo("http://127.0.0.1:0018742/pa/fhir/Claim/$submit");
        }
    }
}
