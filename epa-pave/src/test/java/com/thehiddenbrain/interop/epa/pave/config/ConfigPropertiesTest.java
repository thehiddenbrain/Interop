package com.thehiddenbrain.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedConfiguration() {
        assertThat(EpaProperties.defaults()).isEqualTo(new EpaProperties("PAVE", "1.0.0", "onyx", "mock"));
        assertThat(EpaProperties.defaults().mock()).isTrue();
        assertThat(EpaProperties.defaults().live()).isFalse();

        ConfigStoreProperties store = ConfigStoreProperties.defaults();
        assertThat(store.directory()).isEqualTo("./config-store");
        assertThat(store.activeEnvironment()).isEmpty();
        // The floor defaults to closed: production writes are impossible until an owner opens it.
        assertThat(store.allowProdWrites()).isFalse();

        TrackingProperties tracking = TrackingProperties.defaults();
        assertThat(tracking.enabled()).isTrue();
        assertThat(tracking.directory()).isEqualTo("./run-history");
        // Member IDs are not recorded unless configured. Only the mock profile turns this on.
        assertThat(tracking.recordMemberId()).isFalse();
        assertThat(tracking.recordNpi()).isTrue();
        assertThat(tracking.saltFile()).isEmpty();
        assertThat(tracking.slowThresholdMs()).isZero();

        AppProperties app = AppProperties.defaults();
        assertThat(app.scenarioDir()).isEmpty();
        assertThat(app.autocompleteLimit()).isEqualTo(25);
        assertThat(app.mockLatencyEnabled()).isTrue();
    }

    @Test
    void onyxDefaultsLeaveEveryUrlBlankAndAuthOff() {
        OnyxProperties onyx = OnyxProperties.defaults();
        assertThat(onyx.crd().url()).isEmpty();
        assertThat(onyx.dtr().url()).isEmpty();
        assertThat(onyx.pas().url()).isEmpty();
        assertThat(onyx.pas().attachmentMode()).isEqualTo("fhir");
        assertThat(onyx.subscription().url()).isEmpty();
        assertThat(onyx.subscription().callbackUrl()).isEmpty();
        assertThat(onyx.connectTimeoutMs()).isEqualTo(5000);
        assertThat(onyx.readTimeoutMs()).isEqualTo(15000);
        assertThat(onyx.auth().enabled()).isFalse();
        assertThat(onyx.auth().clientSecret()).isEmpty();
        assertThat(onyx.variables()).isEmpty();
    }

    @Test
    void lookupTimeoutsAreShorterThanProtocolTimeouts() {
        LookupProperties lookup = LookupProperties.defaults();
        OnyxProperties onyx = OnyxProperties.defaults();
        for (LookupProperties.Endpoint endpoint : new LookupProperties.Endpoint[] {lookup.member(), lookup.provider()}) {
            assertThat(endpoint.url()).isEmpty();
            assertThat(endpoint.enabled()).isTrue();
            assertThat(endpoint.connectTimeoutMs()).isEqualTo(3000).isLessThan(onyx.connectTimeoutMs());
            assertThat(endpoint.readTimeoutMs()).isEqualTo(5000).isLessThan(onyx.readTimeoutMs());
            assertThat(endpoint.headers()).isEmpty();
            assertThat(endpoint.useOnyxAuth()).isFalse();
        }
    }

    @Test
    void codelistDefaultsCarryCandidateHeadersForEveryColumn() {
        CodelistProperties codelist = CodelistProperties.defaults();
        assertThat(codelist.file()).isEmpty();
        assertThat(codelist.format()).isEqualTo("auto");
        assertThat(codelist.delimiter()).isEqualTo(",");
        CodelistProperties.Columns columns = codelist.columns();
        assertThat(columns.code()).contains("code", "procedure code", "cpt");
        assertThat(columns.description()).isNotEmpty();
        assertThat(columns.codeType()).isNotEmpty();
        assertThat(columns.system()).isNotEmpty();
        assertThat(columns.authRequired()).contains("auth required", "pa required");
        assertThat(columns.lineOfBusiness()).contains("line of business", "lob");
        assertThat(columns.delegatedVendor()).contains("delegated vendor", "vendor");
        assertThat(columns.effectiveDate()).isNotEmpty();
        assertThat(columns.endDate()).isNotEmpty();
        assertThat(columns.notes()).isNotEmpty();
    }

    @Test
    void overridesBindByPropertyNameAsApplicationYmlWritesThem() {
        TrackingProperties tracking = TrackingProperties.of(Map.of("directory", "/tmp/ledger", "record-member-id", "true",
                "slow-threshold-ms", "2500"));
        assertThat(tracking.directory()).isEqualTo("/tmp/ledger");
        assertThat(tracking.recordMemberId()).isTrue();
        assertThat(tracking.slowThresholdMs()).isEqualTo(2500);
        assertThat(tracking.recordNpi()).isTrue();

        OnyxProperties onyx = OnyxProperties.of(Map.of("crd.url", "{{baseUrl}}", "pas.attachment-mode", "x12-275",
                "auth.enabled", "true", "auth.client-secret", "placeholder-secret", "variables.crdOrderSignServiceId", "order-sign-crd"));
        assertThat(onyx.crd().url()).isEqualTo("{{baseUrl}}");
        assertThat(onyx.pas().attachmentMode()).isEqualTo("x12-275");
        assertThat(onyx.auth().enabled()).isTrue();
        assertThat(onyx.variables()).containsEntry("crdOrderSignServiceId", "order-sign-crd");

        LookupProperties lookup = LookupProperties.of(Map.of("member.url", "https://example.invalid/members/{id}",
                "member.headers.X-Api-Key", "{{memberApiKey}}", "provider.enabled", "false"));
        assertThat(lookup.member().url()).isEqualTo("https://example.invalid/members/{id}");
        assertThat(lookup.member().headers()).containsEntry("X-Api-Key", "{{memberApiKey}}");
        assertThat(lookup.provider().enabled()).isFalse();
        assertThat(LookupProperties.of(Map.of("member.use-onyx-auth", "true")).member().useOnyxAuth()).isTrue();
        // A header value can be an API key typed literally, so it is not printed.
        assertThat(LookupProperties.of(Map.of("member.headers.X-Api-Key", "placeholder-key-value")).toString())
                .doesNotContain("placeholder-key-value").contains("X-Api-Key");

        CodelistProperties codelist = CodelistProperties.of(Map.of("columns.code[0]", "Proc Cd", "delimiter", "|"));
        assertThat(codelist.columns().code()).containsExactly("Proc Cd");
        assertThat(codelist.columns().description()).isNotEmpty();
        assertThat(codelist.delimiter()).isEqualTo("|");
        // With no alias configured a line of business matches only itself.
        assertThat(CodelistProperties.defaults().lineOfBusinessAliases()).isEmpty();
        CodelistProperties aliased = CodelistProperties.of(Map.of("line-of-business-aliases.MCR[0]", "Medicare",
                "line-of-business-aliases.MCR[1]", "Medicare Advantage"));
        assertThat(aliased.lineOfBusinessAliases()).containsEntry("MCR", java.util.List.of("Medicare", "Medicare Advantage"));
    }

    @Test
    void noSecretIsPrintedOrSerialisedFromAPropertiesRecord() {
        // The shortest way to build "everything in force" is to return these records, and the
        // shortest startup log line prints one. Neither may show a secret.
        OnyxProperties onyx = OnyxProperties.of(Map.of("auth.client-secret", "placeholder-secret-1",
                "variables.clientSecret", "placeholder-secret-2", "variables.baseUrl", "https://example.invalid"));
        LookupProperties lookup = LookupProperties.of(Map.of("member.headers.X-Api-Key", "placeholder-secret-3"));

        assertThat(onyx.auth().toString()).doesNotContain("placeholder-secret-1");
        assertThat(onyx.toString()).doesNotContain("placeholder-secret-1").doesNotContain("placeholder-secret-2").contains("clientSecret");
        assertThat(lookup.toString()).doesNotContain("placeholder-secret-3").contains("X-Api-Key");

        String onyxJson = com.thehiddenbrain.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(onyx);
        String lookupJson = com.thehiddenbrain.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(lookup);
        assertThat(onyxJson).doesNotContain("placeholder-secret-1").doesNotContain("placeholder-secret-2");
        assertThat(lookupJson).doesNotContain("placeholder-secret-3");
        // The values are still there for the code that sends them.
        assertThat(onyx.auth().clientSecret()).isEqualTo("placeholder-secret-1");
        assertThat(onyx.variables()).containsEntry("clientSecret", "placeholder-secret-2");
        assertThat(lookup.member().headers()).containsEntry("X-Api-Key", "placeholder-secret-3");
    }
}
