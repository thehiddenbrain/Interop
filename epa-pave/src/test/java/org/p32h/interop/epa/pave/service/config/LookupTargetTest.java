package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.OnyxHosts;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

/**
 * A lookup saved on screen reaches the gateway as a {@link LookupTarget} (R-11, plan 5.2 and 9.2):
 * it is resolved against the snapshot the call is prepared from, a POST to a search address is
 * never a write even on a production environment, an address that reads as a write is judged as
 * one, its secret header values are held and never shown, and the environment's sign-in goes with
 * it only when the tester chose so.
 */
class LookupTargetTest {

    private static final String SEARCH = "https://members.lookup.example.invalid/member/search";
    private static final String TRACE_VALUE = "trace-value-placeholder-7";
    private static final String KEY_VALUE = "member-key-placeholder-42";

    @TempDir
    Path directory;

    private static LookupTarget posted(String address, HeaderSetting... headers) {
        return new LookupTarget("POST", address, List.of(headers), false, null, null);
    }

    private static ConfigFixture prodLive(Path directory) {
        Map<String, String> system = new HashMap<>();
        system.put("PAVE_MEMBER_KEY", KEY_VALUE);
        system.put("PAVE_ONYX_CLIENT_SECRET", "onyx-client-secret-placeholder");
        ConfigFixture fixture = ConfigFixture.on(directory).live().operatingSystem(system).build();
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        return fixture;
    }

    @Test
    void aPostTargetIsNeverAWriteOnAProductionEnvironmentAndGoesWhereItWasSaved() {
        ConfigFixture fixture = prodLive(directory);
        for (EpaOperation operation : List.of(EpaOperation.MEMBER_LOOKUP, EpaOperation.PROVIDER_LOOKUP)) {
            CallIntent intent = CallIntent.of(operation).withLookup(posted(SEARCH));
            CallPlan plan = fixture.gateway.describe(fixture.store.snapshot(), intent);
            assertThat(plan.write()).isFalse();
            assertThat(plan.refusal()).isNull();

            // No unlock is held, and the call is prepared all the same: a lookup is a read.
            PreparedCall call = fixture.gateway.prepare(intent);
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.url().value()).isEqualTo(SEARCH);
            assertThat(call.requestName()).isNull();
            assertThat(call.url().source()).isEqualTo(operation == EpaOperation.MEMBER_LOOKUP
                    ? "the member lookup saved in Configuration > Members" : "the provider lookup saved in Configuration > Providers");
        }
        // Without a saved target a lookup keeps its own method whatever is asked: a read cannot be turned into a write by naming one.
        CallPlan withoutTarget = fixture.gateway.describe(fixture.store.snapshot(), CallIntent.of(EpaOperation.MEMBER_LOOKUP).withMethod("POST"));
        assertThat(withoutTarget.method()).isEqualTo("GET");
    }

    @Test
    void aGetTargetFillsTheIdentifierAndResolvesItsPlaceholdersAgainstTheSnapshot() {
        ConfigFixture fixture = prodLive(directory);
        LookupTarget target = new LookupTarget("get", "{{baseUrl}}/members/{id}", List.of(), false, null, null);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(target).withUrlValue("id", "S123456789"));
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.url().value()).isEqualTo("https://api.onyx.example.invalid/fhir/members/S123456789");
    }

    @Test
    void aTargetWhoseAddressReadsAsAWriteIsJudgedAsOneAndWaitsForTheUnlock() {
        ConfigFixture fixture = prodLive(directory);
        // A lookup is a read for its method, never for an address that submits a claim.
        CallIntent submit = CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(posted("https://members.lookup.example.invalid/fhir/Claim/$submit"));
        CallPlan plan = fixture.gateway.describe(fixture.store.snapshot(), submit);
        assertThat(plan.write()).isTrue();
        assertThat(plan.writeBecause()).isEqualTo("the address of the saved lookup reads as PAS submit");
        assertThatThrownBy(() -> fixture.gateway.prepare(submit)).isInstanceOf(ProdWriteBlockedException.class);

        // A header that asks the far side to act with another method is judged with that method, as for any call.
        CallIntent override = CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(new LookupTarget("GET", SEARCH,
                List.of(new HeaderSetting("X-HTTP-Method-Override", "DELETE", false)), false, null, null));
        assertThat(fixture.gateway.describe(fixture.store.snapshot(), override).write()).isTrue();
        assertThatThrownBy(() -> fixture.gateway.prepare(override)).isInstanceOf(ProdWriteBlockedException.class);
    }

    @Test
    void itsSecretHeaderValuesAreHeldAndNeverShown() {
        ConfigFixture fixture = prodLive(directory);
        LookupTarget target = posted(SEARCH, new HeaderSetting("x-trace", TRACE_VALUE, true), new HeaderSetting("x-api-key", "{{env:PAVE_MEMBER_KEY}}", false),
                new HeaderSetting("Accept", "application/json", false));
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(target));

        Map<String, ResolvedHeader> headers = new HashMap<>();
        call.headers().forEach(header -> headers.put(header.name(), header));
        // Sent as saved, shown masked: one saved as a secret, one that is a credential by its name.
        assertThat(headers.get("x-trace").value()).isEqualTo(TRACE_VALUE);
        assertThat(headers.get("x-trace").display()).doesNotContain(TRACE_VALUE);
        assertThat(headers.get("x-api-key").value()).isEqualTo(KEY_VALUE);
        assertThat(headers.get("x-api-key").display()).doesNotContain(KEY_VALUE);
        assertThat(headers.get("Accept").display()).isEqualTo("application/json");
        assertThat(call.toString()).doesNotContain(TRACE_VALUE).doesNotContain(KEY_VALUE);
        assertThat(target.toString()).doesNotContain(TRACE_VALUE).doesNotContain(SEARCH);

        // A service that repeats either value is masked in what is shown of its answer.
        SentSecrets.Masked masked = call.heldSecrets().mask("{\"echo\": \"" + TRACE_VALUE + "\", \"key\": \"" + KEY_VALUE + "\"}");
        assertThat(masked.places()).isEqualTo(2);
        assertThat(masked.text()).doesNotContain(TRACE_VALUE).doesNotContain(KEY_VALUE);
    }

    @Test
    void whatEverySourceOfHeldSecretsHoldsJoinsACallAndANotification() {
        ConfigFixture fixture = prodLive(directory);
        String lookupSecret = "saved-lookup-secret-placeholder";
        HeldSecretsSource source = () -> SentSecrets.builder().variable("lookup secret", lookupSecret).build();
        HeldSecretsSource failing = () -> {
            throw new IllegalStateException("never shown");
        };
        fixture.gateway.heldSecretsSources(List.of(source));

        String answer = "{\"echo\": \"" + lookupSecret + "\"}";
        assertThat(fixture.gateway.secretsGivenToServices().mask(answer).text()).doesNotContain(lookupSecret);
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PROVIDER_LOOKUP).withLookup(posted(SEARCH)));
        assertThat(call.heldSecrets().mask(answer).text()).doesNotContain(lookupSecret);
        assertThat(call.heldSecrets().incomplete()).isFalse();

        // A source that cannot answer leaves the set marked incomplete, so the wire view says not every secret was known.
        fixture.gateway.heldSecretsSources(List.of(source, failing));
        SentSecrets held = fixture.gateway.secretsGivenToServices();
        assertThat(held.incomplete()).isTrue();
        assertThat(held.mask(answer).text()).doesNotContain(lookupSecret);
    }

    @Test
    void theEnvironmentsSignInGoesWithALookupOnlyWhenChosen() throws Exception {
        ConfigFixture fixture = prodLive(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("id", fixture.environmentId("Onyx Production"));
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "https://api.onyx.example.invalid/oauth/token");
        signIn.put("clientId", "client-placeholder");
        signIn.put("clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        PreparedCall own = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(posted(SEARCH)));
        assertThat(own.signIn().method()).isEqualTo(SignInSettings.Method.NONE);
        assertThat(own.signIn().clientSecret()).isNull();

        PreparedCall chosen = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP)
                .withLookup(new LookupTarget("POST", SEARCH, List.of(), true, null, null)));
        assertThat(chosen.signIn().method()).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        assertThat(chosen.signIn().clientSecret()).isEqualTo("onyx-client-secret-placeholder");

        // The lookup's host is none of the Onyx addresses, so the access token is never sent there on its own.
        assertThat(fixture.gateway.configuredUrls(Set.of(Protocol.CRD, Protocol.DTR, Protocol.PAS, Protocol.SUBSCRIPTION)))
                .noneMatch(url -> url.startsWith("https://members.lookup.example.invalid"));
        assertThat(OnyxHosts.admits(fixture.gateway, SEARCH)).isFalse();
    }

    @Test
    void itsTimeoutsStandOverTheLookupTimeoutsOfApplicationYml() {
        ConfigFixture fixture = prodLive(directory);
        PreparedCall stated = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP)
                .withLookup(new LookupTarget("POST", SEARCH, List.of(), false, null, 9000)));
        assertThat(stated.timeouts()).isEqualTo(new Timeouts(3000, 9000, Timeouts.FROM_APPLICATION, Timeouts.FROM_LOOKUP));
        PreparedCall unstated = fixture.gateway.prepare(CallIntent.of(EpaOperation.PROVIDER_LOOKUP).withLookup(posted(SEARCH)));
        assertThat(unstated.timeouts()).isEqualTo(Timeouts.application(3000, 5000));
    }

    @Test
    void aTargetIsForALookupOnlyAndIsSentWithGetOrPostOnly() {
        LookupTarget target = posted(SEARCH);
        assertThatThrownBy(() -> CallIntent.of(EpaOperation.PAS_SUBMIT).withLookup(target)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallIntent.of(EpaOperation.CRD_DISCOVERY).withLookup(target)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(target).withRequest(new RequestRef("c", "r")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookupTarget("PUT", SEARCH, List.of(), false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookupTarget("DELETE", SEARCH, List.of(), false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookupTarget("POST", " ", List.of(), false, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LookupTarget("POST", SEARCH, List.of(), false, 50, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new HeaderSetting("x-trace", TRACE_VALUE, true).toString()).doesNotContain(TRACE_VALUE);
        assertThat(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withLookup(target).toString()).doesNotContain(SEARCH);
    }
}
