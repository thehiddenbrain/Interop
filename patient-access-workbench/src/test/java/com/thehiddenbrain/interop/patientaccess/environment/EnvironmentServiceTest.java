package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentServiceTest {

    private static final String SECRET = "client-secret-value-ABCD";

    @TempDir
    Path dir;
    TestGraph g;

    @BeforeEach
    void setUp() {
        g = new TestGraph(dir);
    }

    private static EnvironmentInput input(String name, EnvironmentTier tier, String baseUrl, EnvironmentInput.AuthInput auth,
                                          List<EnvironmentInput.HeaderInput> headers, FhirOptions fhir, Long version) {
        return new EnvironmentInput(name, null, tier, baseUrl, auth, headers, null, fhir, null, null, null, null, version);
    }

    private static EnvironmentInput.AuthInput auth(AuthMode mode, Boolean discover, String authorize, String token, String clientId,
                                                   String clientSecret, String staticToken, String jwk, String alg) {
        return new EnvironmentInput.AuthInput(mode, discover, authorize, token, clientId, clientSecret, null, null, null, staticToken, jwk, alg,
                null, null, null, null);
    }

    private static List<String> fields(Throwable t) {
        return ((WorkbenchException) t).getDetails().stream().map(ApiError.Detail::field).toList();
    }

    private static void assertRejected(Runnable action, String field, String messagePart) {
        assertThatThrownBy(action::run).isInstanceOf(WorkbenchException.class).satisfies(t -> {
            WorkbenchException e = (WorkbenchException) t;
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.getDetails()).anySatisfy(d -> {
                assertThat(d.field()).isEqualTo(field);
                assertThat(d.message()).contains(messagePart);
            });
        });
    }

    @Test
    void createAppliesDefaultsAndStampsVersionAndTimestamps() {
        EnvironmentView v = g.environments.create(input("UAT", null, "https://uat.example.org/fhir/", null, null, null, null));
        assertThat(v.id()).hasSize(20);
        assertThat(v.vendor()).isEqualTo(EnvironmentService.DEFAULT_VENDOR);
        assertThat(v.tier()).isEqualTo(EnvironmentTier.UAT);
        assertThat(v.implementationGuides()).isEqualTo(EnvironmentService.DEFAULT_IGS);
        assertThat(v.auth().mode()).isEqualTo(AuthMode.NONE);
        assertThat(v.enabled()).isTrue();
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.createdAt()).isEqualTo(g.clock.instant());
        assertThat(g.environments.require(v.id()).baseUrl()).isEqualTo("https://uat.example.org/fhir");
    }

    @Test
    void nameIsRequiredAndUniqueIgnoringCase() {
        assertRejected(() -> g.environments.create(input(" ", null, "https://x.example.org", null, null, null, null)), "name", "required");
        g.environments.create(input("Onyx UAT", null, "https://x.example.org", null, null, null, null));
        assertRejected(() -> g.environments.create(input("onyx uat", null, "https://y.example.org", null, null, null, null)), "name", "another environment");
        assertRejected(() -> g.environments.create(input("x".repeat(81), null, "https://y.example.org", null, null, null, null)), "name", "80");
    }

    @Test
    void prodRequiresHttpsExceptForLoopback() {
        assertRejected(() -> g.environments.create(input("prod", EnvironmentTier.PROD, "http://fhir.example.org/r4", null, null, null, null)),
                "fhirBaseUrl", "https for a PROD");
        EnvironmentView local = g.environments.create(input("local prod", EnvironmentTier.PROD, "http://localhost:8080/fhir", null, null, null, null));
        assertThat(local.fhirBaseUrl()).isEqualTo("http://localhost:8080/fhir");
        EnvironmentView uat = g.environments.create(input("plain uat", EnvironmentTier.UAT, "http://fhir.example.org/r4", null, null, null, null));
        assertThat(uat.tier()).isEqualTo(EnvironmentTier.UAT);
        assertRejected(() -> g.environments.create(input("bad", null, "ftp://x.example.org", null, null, null, null)), "fhirBaseUrl", "http or https");
        assertRejected(() -> g.environments.create(input("bad2", null, "https://x.example.org/fhir?x=1", null, null, null, null)), "fhirBaseUrl", "query string");
        assertRejected(() -> g.environments.create(input("bad3", null, "/relative", null, null, null, null)), "fhirBaseUrl", "absolute");
        assertRejected(() -> g.environments.create(input("bad4", null, null, null, null, null, null)), "fhirBaseUrl", "required");
    }

    @Test
    void trustAllCertificatesIsRefusedOnProd() {
        FhirOptions trustAll = new FhirOptions(null, null, null, true, true, false, null, null, false);
        assertRejected(() -> g.environments.create(input("prod", EnvironmentTier.PROD, "https://fhir.example.org", null, null, trustAll, null)),
                "fhir.trustAllCertificates", "PROD");
        EnvironmentView uat = g.environments.create(input("uat", EnvironmentTier.UAT, "https://fhir.example.org", null, null, trustAll, null));
        assertThat(uat.fhir().trustAllCertificates()).isTrue();
        FhirOptions badPaging = new FhirOptions(0, 1000, null, true, false, false, null, null, false);
        assertRejected(() -> g.environments.create(input("paging", null, "https://fhir.example.org", null, null, badPaging, null)), "fhir.pageSize", "between");
        assertRejected(() -> g.environments.create(input("paging", null, "https://fhir.example.org", null, null, badPaging, null)), "fhir.maxPages", "between");
    }

    @Test
    void authModesHaveTheirOwnRequirements() {
        String base = "https://fhir.example.org";
        assertRejected(() -> g.environments.create(input("s", null, base, auth(AuthMode.STATIC_TOKEN, true, null, null, null, null, null, null, null), null, null, null)),
                "auth.staticToken", "STATIC_TOKEN");

        assertThatThrownBy(() -> g.environments.create(input("cc", null, base,
                auth(AuthMode.CLIENT_CREDENTIALS, false, null, null, null, null, null, null, null), null, null, null)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(fields(t)).contains("auth.clientId", "auth.clientSecret", "auth.tokenEndpoint"));
        // with discovery on, the token endpoint may be left empty
        EnvironmentView cc = g.environments.create(input("cc", null, base,
                auth(AuthMode.CLIENT_CREDENTIALS, true, null, null, "client", SECRET, null, null, null), null, null, null));
        assertThat(cc.auth().discoverEndpoints()).isTrue();

        assertThatThrownBy(() -> g.environments.create(input("bs", null, base,
                auth(AuthMode.BACKEND_SERVICES, false, null, "https://as.example.org/token", "client", null, null, null, "HS256"), null, null, null)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(fields(t)).containsExactlyInAnyOrder("auth.privateKeyJwk", "auth.signingAlgorithm"));
        EnvironmentView bs = g.environments.create(input("bs", null, base,
                auth(AuthMode.BACKEND_SERVICES, false, null, "https://as.example.org/token", "client", null, null, rsaJwk(), null), null, null, null));
        assertThat(bs.auth().signingAlgorithm()).isEqualTo("RS384");
        assertThat(bs.auth().privateKeyJwk().set()).isTrue();

        assertThatThrownBy(() -> g.environments.create(input("smart", null, base,
                auth(AuthMode.SMART_AUTHORIZATION_CODE, false, null, "not a url", "app", null, null, null, null), null, null, null)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(fields(t)).contains("auth.authorizationEndpoint", "auth.tokenEndpoint"));
        assertRejected(() -> g.environments.create(input("smart-prod", EnvironmentTier.PROD, base,
                auth(AuthMode.SMART_AUTHORIZATION_CODE, false, "http://as.example.org/authorize", "https://as.example.org/token", "app", null, null, null, null),
                null, null, null)), "auth.authorizationEndpoint", "https for a PROD");
    }

    @Test
    void authorizationIsNotAllowedAsExtraHeaderAndIdentifierSystemsNeedASystem() {
        List<EnvironmentInput.HeaderInput> headers = List.of(new EnvironmentInput.HeaderInput("Authorization", "Bearer x", false));
        assertRejected(() -> g.environments.create(input("h", null, "https://fhir.example.org", null, headers, null, null)), "headers[0].name", "Authorization");
        EnvironmentInput withBadSystem = new EnvironmentInput("i", null, null, "https://fhir.example.org", null, null,
                List.of(new IdentifierSystem("Member", " ", "MB", true)), null, null, null, null, null, null);
        assertRejected(() -> g.environments.create(withBadSystem), "identifierSystems[0].system", "required");
    }

    @Test
    void secretsAreKeptClearedOrReplacedAccordingToTheInput() {
        EnvironmentView created = g.environments.create(input("cc", null, "https://fhir.example.org",
                auth(AuthMode.CLIENT_CREDENTIALS, false, null, "https://as.example.org/token", "client", SECRET, null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "api-key-value-1234", true),
                        new EnvironmentInput.HeaderInput("X-Trace", "plain", false)), null, null));
        String id = created.id();
        assertThat(g.crypto.reveal(g.environments.require(id).auth().clientSecret())).isEqualTo(SECRET);

        // null keeps
        g.environments.update(id, input(null, null, null, auth(null, null, null, null, null, null, null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", null, true), new EnvironmentInput.HeaderInput("X-Trace", "changed", false)), null, null));
        Environment kept = g.environments.require(id);
        assertThat(g.crypto.reveal(kept.auth().clientSecret())).isEqualTo(SECRET);
        assertThat(g.environments.resolvedHeaders(kept)).containsEntry("X-Api-Key", "api-key-value-1234").containsEntry("X-Trace", "changed");
        assertThat(g.environments.secretHeaderNames(kept)).containsExactly("x-api-key");

        // value replaces
        g.environments.update(id, input(null, null, null, auth(null, null, null, null, null, "new-secret-value-9999", null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "rotated-key-value", true)), null, null));
        Environment replaced = g.environments.require(id);
        assertThat(g.crypto.reveal(replaced.auth().clientSecret())).isEqualTo("new-secret-value-9999");
        assertThat(g.environments.resolvedHeaders(replaced)).containsExactly(Map.entry("X-Api-Key", "rotated-key-value"));

        // "" clears (which makes CLIENT_CREDENTIALS invalid, so switch the mode at the same time)
        g.environments.update(id, input(null, null, null, auth(AuthMode.NONE, null, null, null, null, "", null, null, null), List.of(), null, null));
        Environment cleared = g.environments.require(id);
        assertThat(cleared.auth().clientSecret()).isNull();
        assertThat(cleared.headers()).isEmpty();
    }

    @Test
    void viewsMaskSecretsAndPlainHeadersStayReadable() throws Exception {
        EnvironmentView v = g.environments.create(input("cc", null, "https://fhir.example.org",
                auth(AuthMode.CLIENT_CREDENTIALS, false, null, "https://as.example.org/token", "client", SECRET, null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "api-key-value-1234", true),
                        new EnvironmentInput.HeaderInput("X-Trace", "plain", false)), null, null));
        assertThat(v.auth().clientSecret().set()).isTrue();
        assertThat(v.auth().clientSecret().hint()).isEqualTo("***ABCD");
        assertThat(v.auth().staticToken().set()).isFalse();
        assertThat(v.auth().privateKeyJwk().set()).isFalse();
        assertThat(v.headers()).hasSize(2);
        assertThat(v.headers().get(0).secret()).isTrue();
        assertThat(v.headers().get(0).value()).isNull();
        assertThat(v.headers().get(0).secretValue().hint()).isEqualTo("***1234");
        assertThat(v.headers().get(1).value()).isEqualTo("plain");
        String json = JsonFile.MAPPER.writeValueAsString(v);
        assertThat(json).doesNotContain(SECRET).doesNotContain("api-key-value-1234").doesNotContain("\"enc\"");
        assertThat(JsonFile.MAPPER.writeValueAsString(g.environments.list())).doesNotContain(SECRET);
    }

    @Test
    void staleVersionIsRejectedWithConflict() {
        EnvironmentView v = g.environments.create(input("v", null, "https://fhir.example.org", null, null, null, null));
        assertThatThrownBy(() -> g.environments.update(v.id(), input("renamed", null, null, null, null, null, 7L)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("version 1").hasMessageContaining("you sent 7");
        g.clock.advance(Duration.ofMinutes(1));
        EnvironmentView updated = g.environments.update(v.id(), input("renamed", null, null, null, null, null, 1L));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.name()).isEqualTo("renamed");
        assertThat(updated.createdAt()).isEqualTo(v.createdAt());
        assertThat(updated.updatedAt()).isAfter(v.updatedAt());
        EnvironmentView unchecked = g.environments.update(v.id(), input("again", null, null, null, null, null, null));
        assertThat(unchecked.version()).isEqualTo(3);
        assertThatThrownBy(() -> g.environments.update("nope", input("x", null, null, null, null, null, null)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void duplicateCopiesSecretsUnderANewIdAndName() {
        EnvironmentView v = g.environments.create(input("UAT", null, "https://fhir.example.org",
                auth(AuthMode.CLIENT_CREDENTIALS, false, null, "https://as.example.org/token", "client", SECRET, null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "api-key-value-1234", true)), null, null));
        EnvironmentView copy = g.environments.duplicate(v.id(), null);
        assertThat(copy.id()).isNotEqualTo(v.id());
        assertThat(copy.name()).isEqualTo("UAT (copy)");
        assertThat(copy.version()).isEqualTo(1);
        Environment stored = g.environments.require(copy.id());
        assertThat(g.crypto.reveal(stored.auth().clientSecret())).isEqualTo(SECRET);
        assertThat(g.environments.resolvedHeaders(stored)).containsEntry("X-Api-Key", "api-key-value-1234");
        EnvironmentView named = g.environments.duplicate(v.id(), "Prod");
        assertThat(named.name()).isEqualTo("Prod");
        assertThatThrownBy(() -> g.environments.duplicate(v.id(), "UAT (copy)")).isInstanceOf(WorkbenchException.class)
                .hasMessageContaining("another environment has this name");
        assertThat(g.environments.list()).extracting(EnvironmentView::name).containsExactly("Prod", "UAT", "UAT (copy)");
    }

    @Test
    void storePersistsAcrossInstancesIncludingEncryptedSecrets() {
        EnvironmentView v = g.environments.create(input("persisted", EnvironmentTier.SANDBOX, "https://fhir.example.org",
                auth(AuthMode.CLIENT_CREDENTIALS, false, null, "https://as.example.org/token", "client", SECRET, null, null, null),
                List.of(new EnvironmentInput.HeaderInput("X-Api-Key", "api-key-value-1234", true)), null, null));
        g.environments.delete(g.environments.create(input("deleted", null, "https://fhir.example.org", null, null, null, null)).id());

        EnvironmentStore reloaded = new EnvironmentStore(g.environmentStore.path(), g.clock);
        assertThat(reloaded.all()).hasSize(1);
        Environment e = reloaded.require(v.id());
        assertThat(e.name()).isEqualTo("persisted");
        assertThat(e.tier()).isEqualTo(EnvironmentTier.SANDBOX);
        assertThat(e.version()).isEqualTo(1);
        assertThat(e.createdAt()).isEqualTo(g.clock.instant());
        assertThat(e.auth().clientId()).isEqualTo("client");
        assertThat(g.crypto.reveal(e.auth().clientSecret())).isEqualTo(SECRET);
        assertThat(e.headers().get(0).secret()).isTrue();
        assertThat(g.crypto.reveal(e.headers().get(0).secretValue())).isEqualTo("api-key-value-1234");
        assertThatThrownBy(() -> reloaded.delete("missing")).isInstanceOf(WorkbenchException.class)
                .satisfies(t -> assertThat(((WorkbenchException) t).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static String rsaJwk() {
        try {
            return new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test").generate().toJSONString();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
