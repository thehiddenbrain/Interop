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
    void namesTheGuardrailAuditFoundInTheClearAreSecretNow() {
        // m1 of the member data audit: each of these was shown in the clear and not warned about at upload.
        for (String name : new String[] {"passcode", "pin", "otp", "sas", "sig", "hmac", "connectionString", "connection_string", "dsn",
                "xsrf", "csrf", "X-XSRF-TOKEN", "accessCode", "access_code", "pfx", "signingKey", "clientAssertion", "client_assertion",
                "privateKeyPem", "passwd", "sessionToken", "Ocp-Apim-Subscription-Key", "sharedAccessSignature", "userPin"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isTrue();
        }
        // Names the specification and a vendor collection use for ordinary values stay readable.
        for (String name : new String[] {"baseUrl", "crdServiceId", "memberIdSystem", "subscriptionTopic", "callbackUrl", "tokenUrl",
                "clientId", "scope", "pasUrl", "connection", "string", "access", "code", "procedureCode", "pinned", "signal", "design",
                "sasUrl", "lineOfBusiness", "payerId", "npi"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isFalse();
        }
    }

    @Test
    void namesThatLocateOrDescribeACredentialAreNotSecret() {
        for (String name : new String[] {"tokenUrl", "token_url", "clientId", "client_id", "baseUrl", "keyId", "tokenType",
                "secretName", "monkeyUrl", "keyword", "tokenizer", "scopes", "crdServiceId", "host", "authUrl",
                "authType", "auth_method", "tokenAudience", "author", "crdOrderSignServiceId", "sigFormat", "targetFormat", "tokenFormat"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isFalse();
        }
    }

    @Test
    void namesWrittenWithNoSeparatorOrWithANumberAreStillSecret() {
        // Each of these was shown in clear when a word had to equal a list entry exactly.
        for (String name : new String[] {"clientsecret", "accesstoken", "ACCESSTOKEN", "CLIENTSECRET", "authtoken", "secretkey",
                "subscriptionkey", "clientSecret2", "accessToken1", "token2", "apiKey1", "pass", "pw", "creds", "secret_id", "sessionId",
                "JSESSIONID", "oauth2Token", "bearertoken"}) {
            assertThat(Secrets.looksSecret(name)).as(name).isTrue();
        }
        assertThat(Secrets.credentialHeader("X-Session-Id")).isTrue();
        assertThat(Secrets.credentialHeader("Authentication")).isTrue();
        // The id rule is lifted only after secret and session. A key id is still a label.
        assertThat(Secrets.looksSecret("keyId")).isFalse();
        assertThat(Secrets.looksSecret("clientId")).isFalse();
        assertThat(Secrets.looksSecret("oauth2")).isFalse();
    }

    @Test
    void aCredentialHeaderIsShownAsItsSchemeAtMostAndNeverAsPartOfItsValue() {
        assertThat(Secrets.redactCredential("Authorization", "Basic dXNlcjpwYXNz")).isEqualTo("Basic [redacted]");
        assertThat(Secrets.redactCredential("authorization", "bearer placeholder-token")).isEqualTo("Bearer [redacted]");
        assertThat(Secrets.redactCredential("Proxy-Authorization", "Digest username=x")).isEqualTo("Digest [redacted]");
        // A first word that is not a known scheme may be the credential itself.
        assertThat(Secrets.redactCredential("Authorization", "placeholderrawtoken extra")).isEqualTo("[redacted]");
        assertThat(Secrets.redactCredential("Authorization", "placeholderrawtoken")).isEqualTo("[redacted]");
        // Any other credential header shows nothing of its value, whatever it contains.
        assertThat(Secrets.redactCredential("Cookie", "JSESSIONID=PLACEHOLDERSESSION; route=abc")).isEqualTo("[redacted]");
        assertThat(Secrets.redactCredential("X-Api-Key", "Bearer placeholder")).isEqualTo("[redacted]");
        assertThat(Secrets.redactCredential("X-Api-Key", null)).isEqualTo("[redacted]");
        // Applying it twice changes nothing.
        assertThat(Secrets.redactCredential("Authorization", "Bearer [redacted]")).isEqualTo("Bearer [redacted]");
        assertThat(Secrets.redactCredential("Cookie", "[redacted]")).isEqualTo("[redacted]");
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
