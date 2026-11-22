package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.Header;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.SignIn;
import org.springframework.web.client.RestClient;

/**
 * Each way a lookup saved on screen signs in (R-11): none, an API key header, a bearer token, its
 * own client credentials, and the active environment's sign-in, which is the only way the
 * environment's token goes to a lookup. A secret is sent and never shown, and reaches no log line.
 */
class LookupSignInTest {

    private static final Clock CLOCK = ConfigFixture.CLOCK;
    private static final String KEY = "member-key-placeholder-4242";
    private static final String BEARER = "bearer-placeholder-5151";
    private static final String CLIENT_SECRET = "client-secret-placeholder-6161";
    private static final String HEADER_VALUE = "trace-placeholder-7171";
    private static final String TOKEN_ANSWER = "{\"access_token\": \"issued-token-placeholder-8181\", \"token_type\": \"Bearer\", "
            + "\"expires_in\": 3600}";
    private static final String ONYX_TOKEN_ANSWER = "{\"access_token\": \"onyx-token-placeholder-9191\", \"token_type\": \"Bearer\", "
            + "\"expires_in\": 3600}";
    private static final String FOUND = "{\"memberId\": \"S123456789\", \"firstName\": \"Placeholder-Given\", \"coverageStatus\": \"active\"}";

    @TempDir
    Path directory;

    private record Bed(ConfigFixture fixture, OnyxProperties onyx) {
    }

    /** The environment's own sign-in is onyx.auth against the loopback token url, so a lookup that takes it is seen to. */
    private Bed bed(LoopbackApi api) {
        Map<String, String> onyx = Map.of("auth.enabled", "true", "auth.token-url", api.base() + "/onyx-token", "auth.client-id",
                "pave-client", "auth.client-secret", "onyx-secret-placeholder-1212");
        Map<String, String> system = new HashMap<>();
        system.put("PAVE_MEMBER_KEY", KEY);
        ConfigFixture.Builder builder = ConfigFixture.on(directory).live().operatingSystem(system);
        onyx.forEach(builder::onyx);
        ConfigFixture fixture = builder.build();
        fixture.activeEnvironmentOf("Lookup Sandbox", EnvironmentKind.SANDBOX, "lookupBearer", BEARER);
        return new Bed(fixture, OnyxProperties.of(onyx));
    }

    private static HttpMemberLookupClient client(Bed bed, LookupSettings settings) {
        return new HttpMemberLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(bed.onyx(), bed.fixture().gateway, RestClient.builder().build(), CLOCK), bed.fixture().gateway,
                LookupProperties.defaults(), RestClient.builder(), CLOCK, kind -> kind == LookupKind.MEMBER ? settings : null);
    }

    private static LookupSettings get(String base, SignIn signIn, List<Header> headers) {
        return new LookupSettings(true, "GET", base + "/members/{id}", null, null, "PACKED", signIn, headers, null, Map.of(), null, null);
    }

    private static SignIn signIn(String method, String headerName, String apiKey, String bearer, String tokenUrl, String clientId,
                                 String clientSecret) {
        return new SignIn(method, headerName, apiKey, bearer, tokenUrl, clientId, clientSecret, null, "BASIC");
    }

    private static String shown(MemberLookupResult result) {
        StringBuilder out = new StringBuilder();
        for (WireHeader header : result.wire().request().headers()) {
            out.append(header.name()).append(": ").append(header.value()).append('\n');
        }
        return out.toString();
    }

    @Test
    void noneSendsNoCredentialEvenWhenTheEnvironmentSignsIn() throws Exception {
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/members/S123456789", 200, FOUND).answer("/onyx-token", 200, ONYX_TOKEN_ANSWER);
            MemberLookupResult result = client(bed(api), get(api.base(), signIn(SignIn.NONE, null, null, null, null, null, null), List.of()))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(api.last().headers()).doesNotContainKey("authorization");
            // The environment's token endpoint was never asked: its token goes to a lookup only when the tester chose so.
            assertThat(api.seen()).noneMatch(seen -> seen.path().startsWith("/onyx-token"));
        }
    }

    @Test
    void anApiKeyFromAReferenceAndASecretHeaderAreSentAndNeverShownOrLogged() throws Exception {
        try (LoopbackApi api = new LoopbackApi(); CapturedLog log = new CapturedLog()) {
            api.answer("/members/S123456789", 200, FOUND);
            MemberLookupResult result = client(bed(api), get(api.base(),
                    signIn(SignIn.API_KEY, "x-api-key", "{{env:PAVE_MEMBER_KEY}}", null, null, null, null),
                    List.of(new Header("x-trace", HEADER_VALUE)))).lookup(MemberIds.forms("S123456789"), null);
            assertThat(api.last().headers().get("x-api-key")).isEqualTo(KEY);
            assertThat(api.last().headers().get("x-trace")).isEqualTo(HEADER_VALUE);
            assertThat(shown(result)).contains("x-api-key").doesNotContain(KEY).doesNotContain(HEADER_VALUE);
            assertThat(String.join("\n", log.lines())).doesNotContain(KEY).doesNotContain(HEADER_VALUE).doesNotContain("S123456789");
        }
    }

    @Test
    void aBearerTokenGoesAsTheAuthorizationHeader() throws Exception {
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/members/S123456789", 200, FOUND);
            MemberLookupResult result = client(bed(api), get(api.base(), signIn(SignIn.BEARER_TOKEN, null, null, "{{lookupBearer}}", null, null,
                    null), List.of())).lookup(MemberIds.forms("S123456789"), null);
            assertThat(api.last().headers().get("authorization")).isEqualTo("Bearer " + BEARER);
            assertThat(shown(result)).doesNotContain(BEARER);
        }
    }

    @Test
    void itsOwnClientCredentialsAreExchangedAtItsOwnTokenUrl() throws Exception {
        try (LoopbackApi api = new LoopbackApi(); CapturedLog log = new CapturedLog()) {
            api.answer("/members/S123456789", 200, FOUND).answer("/lookup-token", 200, TOKEN_ANSWER);
            MemberLookupResult result = client(bed(api), get(api.base(), signIn(SignIn.CLIENT_CREDENTIALS, null, null, null,
                    api.base() + "/lookup-token", "lookup-client", CLIENT_SECRET), List.of())).lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(api.seen()).anyMatch(seen -> seen.path().equals("/lookup-token") && seen.method().equals("POST"));
            assertThat(api.last().headers().get("authorization")).isEqualTo("Bearer issued-token-placeholder-8181");
            assertThat(api.seen()).noneMatch(seen -> seen.path().startsWith("/onyx-token"));
            assertThat(shown(result)).doesNotContain("issued-token-placeholder-8181");
            assertThat(String.join("\n", log.lines())).doesNotContain(CLIENT_SECRET).doesNotContain("issued-token-placeholder-8181");
        }
    }

    @Test
    void itsOwnClientCredentialsWithAPlaceholderNobodyDefinesSendNothingAndNameTheField() throws Exception {
        try (LoopbackApi api = new LoopbackApi()) {
            MemberLookupResult result = client(bed(api), get(api.base(), signIn(SignIn.CLIENT_CREDENTIALS, null, null, null,
                    api.base() + "/lookup-token", "lookup-client", "{{noSuchSecret}}"), List.of())).lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(result.error().title()).contains("Its Client secret is not set");
            assertThat(api.seen()).isEmpty();
        }
    }

    @Test
    void theActiveEnvironmentsSignInIsUsedOnlyWhenChosen() throws Exception {
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/members/S123456789", 200, FOUND).answer("/onyx-token", 200, ONYX_TOKEN_ANSWER);
            MemberLookupResult result = client(bed(api), get(api.base(), signIn(SignIn.ENVIRONMENT, null, null, null, null, null, null),
                    List.of())).lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(api.last().headers().get("authorization")).isEqualTo("Bearer onyx-token-placeholder-9191");
        }
    }

    @Test
    void aRefusedTokenIsLetGoOfSoTheNextLookupAsksForANewOne() throws Exception {
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/members/S123456789", 401, "{}").answer("/lookup-token", 200, TOKEN_ANSWER);
            HttpMemberLookupClient client = client(bed(api), get(api.base(), signIn(SignIn.CLIENT_CREDENTIALS, null, null, null,
                    api.base() + "/lookup-token", "lookup-client", CLIENT_SECRET), List.of()));
            MemberLookupResult first = client.lookup(MemberIds.forms("S123456789"), null);
            client.lookup(MemberIds.forms("S123456789"), null);
            assertThat(first.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(first.error().action()).contains("Check Sign in with and the Headers of the member lookup in Configuration > Members.");
            assertThat(api.seen().stream().filter(seen -> seen.path().equals("/lookup-token")).count()).isEqualTo(2);
        }
    }
}
