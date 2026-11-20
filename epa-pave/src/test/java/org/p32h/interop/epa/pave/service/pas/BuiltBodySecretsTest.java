package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.run.CaseResources;

/**
 * A secret variable a builder writes into a body this tool builds, such as an identifier system an
 * environment declares secret, is masked in the request as shown and searched for in the answer.
 *
 * <p>Found by hand against a stand-in on the live profile: the service echoed such a value, and it
 * appeared in clear in the wire view of the request, in the PAS and DTR designed views and in the
 * verdict, because a built body was shown exactly as it was sent and so was taken to hold no
 * secret at all.
 */
class BuiltBodySecretsTest {

    private static final String VALUE = "placeholder-sysvar-secret-ZZ8812";
    private static final String URL = "https://example.org/p32h-placeholder/fhir/Claim/$submit";

    /** Resolves memberIdSystem as an environment's secret variable, and nothing else. */
    private static final Function<String, ResolvedText> RESOLVER = template -> {
        if ("{{memberIdSystem}}".equals(template)) {
            return new ResolvedText(template, Secrets.mask("memberIdSystem"), VALUE,
                    List.of(new VariableUse("memberIdSystem", VariableSource.ENVIRONMENT, true, null)), List.of());
        }
        return template != null && template.contains("{{") ? new ResolvedText(template, null, null, List.of(), List.of("other"))
                : ResolvedText.plain(template);
    };

    private static PreparedCall call() {
        return TestPreparedCalls.at(1L, RESOLVER, EpaOperation.PAS_SUBMIT, "POST", new ResolvedUrl(URL, URL, URL, List.of(), List.of(), "test"),
                List.of(), new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX), null, false);
    }

    private static String builtWith(String system) {
        return "{\"resourceType\":\"Bundle\",\"identifier\":{\"system\":\"" + system + "\",\"value\":\"S123456789\"}}";
    }

    @Test
    void aBodyBuiltFromASecretVariableIsShownWithItsMaskAndSentAsBuilt() {
        CaseResources.Reads reads = CaseResources.reading(RESOLVER);
        String system = CaseResources.value(reads, "memberIdSystem");
        assertThat(system).isEqualTo(VALUE);
        ResolvedText body = reads.body(builtWith(system));
        assertThat(body.value()).isEqualTo(builtWith(VALUE));
        assertThat(body.display()).isEqualTo(builtWith(Secrets.mask("memberIdSystem")));
        assertThat(body.variables()).extracting(VariableUse::name).containsExactly("memberIdSystem");
        assertThat(reads.shown(builtWith(system))).doesNotContain(VALUE);
        // A builder that read no secret gives the body back as it is, shown as sent.
        CaseResources.Reads none = CaseResources.reading(RESOLVER);
        assertThat(none.body("{\"a\":1}")).isEqualTo(ResolvedText.plain("{\"a\":1}"));
    }

    @Test
    void aServiceThatEchoesTheValueIsShownWithTheMaskInTheWireViewAndInTheDesignedView() {
        CaseResources.Reads reads = CaseResources.reading(RESOLVER);
        ResolvedText body = reads.body(builtWith(CaseResources.value(reads, "memberIdSystem")));
        WireRequest wire = Exchanges.wireRequest(call(), body, ExchangeOptions.json("application/fhir+json"), true, null);
        assertThat(wire.body()).doesNotContain(VALUE).contains(Secrets.mask("memberIdSystem"));
        String echoed = bundle(claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"echo " + VALUE + "\"",
                "\"x-" + VALUE + "\":\"a property named after it\"", items(decided(1, "A1"))));
        VendorExchange answered = Exchanges.fromResponse(wire, 200, List.of(), echoed, 5L, true);
        PasReading reading = new PasResponseMapper().read(answered, sent("E0601"));
        String shown = JsonSafe.mapper().writeValueAsString(reading.detail()) + JsonSafe.mapper().writeValueAsString(reading.additionalFields())
                + JsonSafe.mapper().writeValueAsString(answered.wire(null));
        assertThat(shown).doesNotContain(VALUE).contains(Secrets.mask("memberIdSystem"));

        // The same body shown exactly as sent, as it was before, holds no secret to search for.
        WireRequest before = Exchanges.wireRequest(call(), ResolvedText.plain(builtWith(VALUE)), ExchangeOptions.json("application/fhir+json"),
                true, null);
        assertThat(before.body()).contains(VALUE);
        assertThat(JsonSafe.mapper().writeValueAsString(new PasResponseMapper().read(Exchanges.fromResponse(before, 200, List.of(), echoed, 5L,
                true), sent("E0601")).detail())).contains(VALUE);
    }
}
