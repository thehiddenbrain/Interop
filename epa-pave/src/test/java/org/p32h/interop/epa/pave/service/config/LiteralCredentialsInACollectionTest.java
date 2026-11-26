package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanParser;

/**
 * M7: a credential written literally into a collection, in a url, a body, an authentication helper
 * or a saved example. The upload says where, never what. Every view of the configuration masks it.
 * A call sends it as written, and shows it masked. The file on disk keeps it, and the upload says so.
 */
class LiteralCredentialsInACollectionTest {

    /** Every value typed into the collection below starts with this, so one search finds any of them. */
    private static final String CANARY = "ZZLIT";

    static final String COLLECTION = """
            {"info": {"name": "zz literal credentials", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "auth": {"type": "basic", "basic": [{"key": "username", "value": "tester"}, {"key": "password", "value": "ZZLIT-BASIC-21"}]},
             "item": [{"name": "Tokens", "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "X-Api-Key"},
                                                                          {"key": "value", "value": "ZZLIT-APIKEY-23"}]},
               "item": [{"name": "token with secrets in the url and the body",
                 "request": {"method": "POST",
                   "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "ZZLIT-BEARER-20"}]},
                   "url": {"raw": "{{baseUrl}}/oauth2/token?client_secret=ZZLIT-URLSECRET-15&api_key=ZZLIT-URLKEY-16&access_token=ZZLIT-URLTOKEN-18&scope=read"},
                   "header": [{"key": "Content-Type", "value": "application/json"}],
                   "body": {"mode": "raw", "raw": "{\\"resourceType\\": \\"Parameters\\", \\"password\\": \\"ZZLIT-JSONPASSWORD-24\\"}"}},
                 "response": [{"name": "issued", "code": 200, "status": "OK",
                   "header": [{"key": "Authorization", "value": "Bearer ZZLIT-EXAMPLETOKEN-19"}],
                   "body": "{\\"access_token\\": \\"ZZLIT-EXAMPLETOKEN-19\\", \\"expires_in\\": 3600}"}]}]}]}
            """;

    @TempDir
    Path directory;

    private static String json(Object view) {
        return JsonSafe.mapper().writeValueAsString(view);
    }

    @Test
    void theUploadSaysWhereEachLiteralCredentialIsAndThatTheFileKeepsItAndNeverQuotesOne() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("literal.postman_collection.json",
                COLLECTION.getBytes(StandardCharsets.UTF_8));
        String file = fixture.store.directory().resolve(upload.collection().file()).toString();
        List<String> warnings = upload.warnings();
        assertThat(String.join(" ", warnings)).doesNotContain(CANARY);
        assertThat(warnings).contains(
                "The request \"Tokens / token with secrets in the url and the body\" holds a credential typed into the collection, in the "
                        + "query parameter client_secret of its url and in the query parameter api_key of its url and in the query parameter "
                        + "access_token of its url and in the field password of its body. It sits in the file " + file + " in plain text: the "
                        + "collection is kept exactly as it was uploaded, value included. This tool masks it wherever it shows the request, "
                        + "and sends it as written. To keep it out of that file, move the value into a secret variable of the environment, "
                        + "or into an operating system variable referred to as {{env:PAVE_YOUR_NAME}}, refer to it from the request as a "
                        + "{{placeholder}} in Postman, and export again.",
                "The saved example \"issued\" of the request \"Tokens / token with secrets in the url and the body\" holds a credential, "
                        + "in its header Authorization and in the field access_token of its body. It sits in the file " + file + " in plain "
                        + "text: the collection is kept exactly as it was uploaded, value included. This tool masks it wherever it shows the "
                        + "example. A saved example is never sent. If it is a real credential, have it revoked.",
                "The authentication helper bearer set on the request \"Tokens / token with secrets in the url and the body\" holds a "
                        + "credential typed into the collection, in its field token. "
                        + "It sits in the file " + file + " in plain text: the collection is kept exactly as it was uploaded, value "
                        + "included. This tool does not apply the helper, so it never sends or shows that value. To keep it out of that "
                        + "file, move the value into a secret variable of the environment, or into an operating system variable referred "
                        + "to as {{env:PAVE_YOUR_NAME}}, refer to it from the request as a {{placeholder}} in Postman, and export again.");
        // The helpers of the collection and of the folder are in the file too, though the request sets its own.
        assertThat(warnings).anyMatch(warning -> warning.startsWith("The authentication helper basic set on the collection holds a "
                + "credential typed into the collection, in its field password."));
        assertThat(warnings).anyMatch(warning -> warning.startsWith("The authentication helper apikey set on the folder \"Tokens\" holds "
                + "a credential typed into the collection, in its field value."));
        // The file is kept as uploaded, value included, which is what the warnings say.
        assertThat(fixture.store.directory().resolve(upload.collection().file())).content().contains("ZZLIT-URLSECRET-15");
    }

    @Test
    void aCredentialInAUrlencodedOrFormdataBodyIsNamedThoughTheBodyIsNotLoaded() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        String forms = """
                {"info": {"name": "zz forms", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [{"name": "token by form", "request": {"method": "POST", "url": "{{baseUrl}}/oauth2/token",
                     "body": {"mode": "urlencoded", "urlencoded": [{"key": "grant_type", "value": "client_credentials"},
                       {"key": "client_secret", "value": "ZZLIT-BODYSECRET-17"}, {"key": "client_id", "value": "{{clientId}}"},
                       {"key": "password", "value": "{{password}}"}]}}},
                   {"name": "upload by form", "request": {"method": "POST", "url": "{{baseUrl}}/upload",
                     "body": {"mode": "formdata", "formdata": [{"key": "api_key", "value": "ZZLIT-FORMKEY-25", "type": "text"},
                       {"key": "file", "src": "/tmp/x.pdf", "type": "file"}]}}}]}
                """;
        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("forms.json", forms.getBytes(StandardCharsets.UTF_8));
        String all = String.join(" ", upload.warnings());
        assertThat(all).doesNotContain(CANARY);
        assertThat(upload.warnings()).anyMatch(warning -> warning.startsWith("The request \"token by form\" holds a credential typed into "
                + "the collection, in the field client_secret of its urlencoded body. It sits in the file "));
        assertThat(upload.warnings()).anyMatch(warning -> warning.startsWith("The request \"upload by form\" holds a credential typed into "
                + "the collection, in the field api_key of its formdata body."));
        assertThat(all).doesNotContain("field password").doesNotContain("field client_id").doesNotContain("field grant_type");
        assertThat(upload.collection().content().requests().get(0).formCredentials()).containsExactly("client_secret");
        assertThat(upload.collection().content().requests().get(0).toString()).doesNotContain(CANARY);
    }

    @Test
    void theHelpersOfAFolderAndOfTheCollectionAreNamedByTheFieldThatHoldsTheCredential() {
        PostmanCollection parsed = PostmanParser.collection(COLLECTION.replace("\"auth\": {\"type\": \"bearer\"", "\"auth0\": {\"type\": \"bearer\""));
        assertThat(parsed.requests().get(0).auth().type()).isEqualTo("apikey");
        assertThat(parsed.requests().get(0).auth().literals()).containsExactly("value");
        assertThat(parsed.requests().get(0).auth().toString()).doesNotContain(CANARY);
        PostmanCollection basic = PostmanParser.collection(COLLECTION.replace("\"auth\": {\"type\": \"apikey\"", "\"auth0\": {\"type\": \"apikey\"")
                .replace("\"auth\": {\"type\": \"bearer\"", "\"auth0\": {\"type\": \"bearer\""));
        assertThat(basic.requests().get(0).auth().literals()).containsExactly("password");
    }

    @Test
    void noViewOfTheConfigurationShowsTheValueAndACallSendsItAsWritten() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", "https://sandbox.example.invalid/fhir");
        StoredCollection collection = fixture.store.uploadCollection("literal.json", COLLECTION.getBytes(StandardCharsets.UTF_8)).collection();
        RequestRef ref = ConfigFixture.ref(collection, "token with secrets in the url and the body");

        ConfigViews.RequestDetail detail = fixture.service.request(collection.id(), ref.requestId());
        ConfigViews.Resolved resolved = fixture.service.resolve(EpaOperation.UNMAPPED, ref, Map.of(), Map.of(), null);
        for (Object view : List.of(fixture.service.collections(), detail, resolved, fixture.service.effective(null),
                fixture.service.environments())) {
            assertThat(json(view)).as(view.getClass().getSimpleName()).doesNotContain(CANARY);
        }
        assertThat(detail.request().urlTemplate()).isEqualTo("{{baseUrl}}/oauth2/token?client_secret=[secret client_secret as typed]"
                + "&api_key=[secret api_key as typed]&access_token=[secret access_token as typed]&scope=read");
        // The body is handed out with a placeholder where the credential was typed, so that it can be loaded and sent.
        assertThat(detail.body()).isEqualTo("{\"resourceType\": \"Parameters\", \"password\": \"{{password as typed}}\"}");
        assertThat(resolved.body()).isEqualTo("{\"resourceType\": \"Parameters\", \"password\": \"[secret password as typed]\"}");

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref));
        assertThat(call.url().value()).isEqualTo("https://sandbox.example.invalid/fhir/oauth2/token?client_secret=ZZLIT-URLSECRET-15"
                + "&api_key=ZZLIT-URLKEY-16&access_token=ZZLIT-URLTOKEN-18&scope=read");
        assertThat(call.url().display()).doesNotContain(CANARY).contains("client_secret=[secret client_secret as typed]");
        assertThat(call.url().template()).doesNotContain(CANARY);
        assertThat(json(call.url())).doesNotContain(CANARY);
        // The body as the request view handed it out, and the body as the collection holds it, both send the value as written.
        String asWritten = "{\"resourceType\": \"Parameters\", \"password\": \"ZZLIT-JSONPASSWORD-24\"}";
        for (String body : List.of(detail.body(), asWritten)) {
            ResolvedText text = call.resolve(body);
            assertThat(text.value()).isEqualTo(asWritten);
            assertThat(text.display()).doesNotContain(CANARY);
            assertThat(text.template()).doesNotContain(CANARY);
            assertThat(json(text)).doesNotContain(CANARY);
        }
    }

    @Test
    void theCollectionAsUploadedIsStillOnDiskAndOnlyTheMaskIsInTheStoreState() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.store.uploadCollection("literal.json", COLLECTION.getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(fixture.store.directory().resolve(ConfigStore.STATE_FILE))).doesNotContain(CANARY);
    }

    @Test
    void aCredentialsNameOfMillionsOfCharactersIsQuotedByItsStartAndItsLength() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        String key = "k".repeat(1_999_992) + "password";
        String header = "X".repeat(100_000) + "-Api-Key";
        String collection = "{\"info\": {\"name\": \"Long names\", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": [{\"name\": \"Submit\", "
                + "\"request\": {\"method\": \"POST\", \"header\": [{\"key\": \"" + header + "\", \"value\": \"ZZLONGNAME-KEY-7\"}], "
                + "\"url\": \"https://{{base}}/Claim/$submit\", \"body\": {\"mode\": \"raw\", \"raw\": "
                + JsonSafe.mapper().writeValueAsString("{\"" + key + "\": \"ZZLONGNAME-SECRET-7\", \"note\": \"short\"}") + "}}}]}";
        List<String> warnings = fixture.store.uploadCollection("names.json", collection.getBytes(StandardCharsets.UTF_8)).warnings();

        // Still warned about, in the place it was, but the name is given by its first 60 characters and its length.
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The request \"Submit\" holds a credential typed into the "
                + "collection, in the field " + "k".repeat(60) + "... (a name of 2000000 characters) of its body. It sits in the file "));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The header \"" + "X".repeat(60) + "... (a name of 100008 "
                + "characters)\" holds a credential typed into the collection in 1 request"));
        assertThat(warnings).allSatisfy(warning -> assertThat(warning.length()).isLessThan(1_000));
        assertThat(String.join(" ", warnings)).doesNotContain("ZZLONGNAME-SECRET-7").doesNotContain("ZZLONGNAME-KEY-7");
        // A name of ordinary length is quoted whole, as before.
        assertThat(org.p32h.interop.epa.pave.postman.Names.shown("k".repeat(60))).isEqualTo("k".repeat(60));
        assertThat(org.p32h.interop.epa.pave.postman.Names.shown("client_secret")).isEqualTo("client_secret");
    }
}
