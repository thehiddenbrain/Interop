package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

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
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;

/**
 * N2 of the check of the guardrail fixes: a url whose scheme, host and port read well and which is
 * not a valid url after them is refused for what it is, naming the value whose text made it so,
 * and is never blamed on the host, the port or the environment's base address.
 */
class InvalidUrlWordingTest {

    private static final List<String> BROKEN = List.of("a b", "50%", "a%zz", "x|y", "a\"b", "a<b");
    private static final String SANDBOX = "http://127.0.0.1:18745/sbx";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture sandbox(String profile) {
        ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites().build();
        fixture.activeEnvironmentOf("Audit Sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX, "crdUrl", SANDBOX,
                "crdOrderSignServiceId", "order-sign-crd");
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    @Test
    void theAddressIsToldFromWhatFollowsIt() {
        for (String text : BROKEN) {
            String url = SANDBOX + "/cds-services/" + text;
            assertThat(UrlNormal.authorityReadable(url)).as(text).isTrue();
            assertThat(UrlNormal.sendable(url)).as(text).isNull();
            assertThat(UrlNormal.validInPath(text)).as(text).isFalse();
        }
        for (String address : List.of("http://127.0.0.1:+1/x", "http://127.0.0.1:99999/x", "http://127.0.0.1:1:/x", "http://a b/x")) {
            assertThat(UrlNormal.authorityReadable(address)).as(address).isFalse();
        }
        assertThat(UrlNormal.validInPath("order-sign-crd")).isTrue();
        assertThat(UrlNormal.validInPath("a%20b")).isTrue();
    }

    @Test
    void aValueTypedForTheRunThatBreaksTheUrlOfAReadIsNamedOnLive() {
        ConfigFixture fixture = sandbox(EpaProperties.LIVE);
        for (String text : BROKEN) {
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                    .withRunValues(Map.of("crdOrderSignServiceId", text)))).as(text).isInstanceOf(BadInputException.class)
                    .hasMessage("CRD order-sign was not sent: the url as filled is not a valid url, because the text of the variable "
                            + "\"crdOrderSignServiceId\" (typed for this run) holds a character that cannot stand in a url as it is, such "
                            + "as a space, a percent sign that starts no escape or a vertical bar. Its scheme, host and port are not the "
                            + "problem.")
                    .satisfies(e -> assertThat(((BadInputException) e).action()).isEqualTo("Nothing was sent. Correct that value: write "
                            + "a space as %20 and a percent sign as %25, or leave the character out, then send it again."));
        }
        // On mock a read is never sent, so nothing is refused.
        ConfigFixture mock = sandbox(EpaProperties.MOCK);
        assertThat(mock.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN).withRunValues(Map.of("crdOrderSignServiceId", "a b"))))
                .isNotNull();
    }

    @Test
    void aWriteSentAsWrittenNamesTheValueAndNotTheBaseAddressOnBothProfiles() {
        for (String profile : List.of(EpaProperties.MOCK, EpaProperties.LIVE)) {
            ConfigFixture fixture = sandbox(profile);
            StoredCollection collection = fixture.collectionOf("By variable", items(item("inquiry by variable", "POST", "{{baseUrl}}/Claim/{{op}}")));
            for (String text : BROKEN) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED)
                        .withRequest(ref(collection, "inquiry by variable")).withRunValues(Map.of("op", text)))).as(profile + " " + text)
                        .isInstanceOf(BadInputException.class)
                        .hasMessageContaining("the url as filled is not a valid url, because the text of the variable \"op\" (typed for this run)")
                        .hasMessageNotContaining("baseUrl").hasMessageNotContaining("could not be read");
            }
            // The same request with a plain value is prepared.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(collection, "inquiry by variable"))
                    .withRunValues(Map.of("op", "$inquire"))).url().value()).as(profile).isEqualTo(SANDBOX + "/Claim/$inquire");
            // An address whose port cannot be read keeps the sentence about the address.
            StoredCollection odd = fixture.collectionOf("Odd", items(item("odd port", "POST", "http://127.0.0.1:+18745/sbx/Claim/{{op}}")));
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(odd, "odd port"))
                    .withRunValues(Map.of("op", "a b")))).as(profile).isInstanceOf(ProdWriteBlockedException.class)
                    .hasMessageContaining("the scheme, host and port it would go to could not be read");
        }
    }
}
