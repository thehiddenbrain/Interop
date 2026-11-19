package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void namesThatReadAsCredentialsAreSecret() {
        for (String name : new String[] {"clientSecret", "client_secret", "CLIENT-SECRET", "apiKey", "api_key", "x-api-key",
                "password", "accessToken", "access_token", "bearerToken", "privateKey", "subscription.key", "jwt",
                "auth", "basicAuth", "authHeader", "authorizationHeader", "cookie", "sessionCookie", "passphrase"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isTrue();
        }
    }

    @Test
    void namesThatLocateOrDescribeACredentialAreNotSecret() {
        for (String name : new String[] {"tokenUrl", "token_url", "clientId", "client_id", "baseUrl", "keyId", "tokenType",
                "secretName", "monkeyUrl", "keyword", "tokenizer", "scopes", "crdServiceId", "host", "authUrl",
                "authType", "auth_method", "tokenAudience", "author", "crdOrderSignServiceId"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isFalse();
        }
    }

    @Test
    void blankNamesAreNotSecret() {
        assertThat(Secrets.looksSecret(null)).isFalse();
        assertThat(Secrets.looksSecret("  ")).isFalse();
    }

    @Test
    void credentialHeadersAreRecognisedByName() {
        assertThat(Secrets.credentialHeader("Authorization")).isTrue();
        assertThat(Secrets.credentialHeader("authorization")).isTrue();
        assertThat(Secrets.credentialHeader("Cookie")).isTrue();
        assertThat(Secrets.credentialHeader("X-Api-Key")).isTrue();
        assertThat(Secrets.credentialHeader("Ocp-Apim-Subscription-Key")).isTrue();
        assertThat(Secrets.credentialHeader("Content-Type")).isFalse();
        assertThat(Secrets.credentialHeader("Accept")).isFalse();
        assertThat(Secrets.credentialHeader(null)).isFalse();
    }

    @Test
    void theMaskNamesTheVariableAndNeverTheValue() {
        assertThat(Secrets.mask("clientSecret")).isEqualTo("[secret clientSecret]");
    }
}
