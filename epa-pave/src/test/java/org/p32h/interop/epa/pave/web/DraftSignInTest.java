package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * "Fill the form from these files" reads the sign-in wherever the usual vendor files show it
 * (D-305): the collection's token request, then the environment's variables by wider names, with
 * the variables that could each be a field offered to choose from, and a line for a reference
 * whose value the export leaves empty. Every file here is written for the test with placeholder
 * values; no value of a secret appears in any answer.
 */
class DraftSignInTest {

    private static final String DRAFT = "/api/config/environments/draft";
    private static final String V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    private static final String V20 = "https://schema.getpostman.com/json/collection/v2.0.0/collection.json";

    /** Values of secrets in the files: none may appear in an answer. */
    private static final String ENV_SECRET = "placeholder-env-secret-value";
    private static final String TYPED_SECRET = "placeholder-typed-secret-value";
    private static final String HEADER_KEY = "placeholder-subscription-key-value";

    @TempDir
    Path directory;

    // ------------------------------------------------------------------------------------------
    // The collection's token request
    // ------------------------------------------------------------------------------------------

    @Test
    void aV21UrlencodedTokenRequestFillsTheSignInAndSaysWhatElseItSends() throws Exception {
        Map<String, Object> request = request("POST", Map.of("raw", "{{authUrl}}/oauth2/v2.0/token", "host", List.of("{{authUrl}}"),
                "path", List.of("oauth2", "v2.0", "token")), List.of(
                header("Content-Type", "application/x-www-form-urlencoded", false),
                header("Ocp-Apim-Subscription-Key", HEADER_KEY, false),
                header("X-Switched-Off", "x", true)),
                Map.of("mode", "urlencoded", "urlencoded", List.of(
                        field("grant_type", "client_credentials", false),
                        field("client_id", "{{uat_client_id}}", false),
                        field("client_secret", "{{uat_client_secret}}", false),
                        field("scope", "{{uat_scope}}", false),
                        field("audience", "placeholder-audience", false),
                        field("resource", "placeholder-resource", true))));
        String collection = collection(V21, null, List.of(folder("Auth", List.of(item("Get token", request))), orderSign()));

        JsonNode answer = draft(environment("UAT", List.of(variable("uat_client_id", "placeholder-client"),
                variable("uat_client_secret", ENV_SECRET), variable("uat_scope", "system/*.read"),
                variable("authUrl", "https://auth.example.invalid"))), collection);

        JsonNode signIn = answer.get("signIn");
        assertThat(signIn.get("method").asString()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(signIn.get("from").asString()).isEqualTo("the collection's token request \"Auth / Get token\"");
        // The url as written, placeholders kept; the client id, secret and scope from the body, so sent in the body.
        assertThat(signIn.get("tokenUrl").asString()).isEqualTo("{{authUrl}}/oauth2/v2.0/token");
        assertThat(signIn.get("clientId").asString()).isEqualTo("{{uat_client_id}}");
        assertThat(signIn.at("/clientSecret/reference").asString()).isEqualTo("{{uat_client_secret}}");
        assertThat(signIn.get("scope").asString()).isEqualTo("{{uat_scope}}");
        assertThat(signIn.get("clientAuthentication").asString()).isEqualTo("BODY");
        assertThat(strings(signIn.get("fields"))).containsExactly("Token url", "Client id", "Client secret", "Scope", "Client authentication");
        assertThat(strings(signIn.get("candidates"))).isEmpty();
        // Said by name, never by value; a switched-off field or header is not sent, so not said.
        assertThat(warnings(answer)).containsExactly("The token request also sends audience and the header Ocp-Apim-Subscription-Key. "
                + "PAVE's sign-in does not send them: if the sign-in is refused, that is the first thing to look at.");
        assertThat(answer.toString()).doesNotContain(ENV_SECRET).doesNotContain(HEADER_KEY).doesNotContain("placeholder-audience");
    }

    @Test
    void aRawFormBodyAndAUrlAsTextAreReadAndAnotherMethodIsSaid() throws Exception {
        Map<String, Object> request = request("GET", "https://auth.example.invalid/token", List.of(),
                Map.of("mode", "raw", "raw", "grant_type=client_credentials&client_id={{clientId}}&client_secret=%7B%7BclientSecret%7D%7D"));
        // D-307: a v2.0 file is read as a whole now, and its token request with it; the shapes v2.0 wrote (a url as text, a helper
        // as an object) also appear in v2.1 files that older exports were converted from, so they are read inside one too.
        JsonNode older = draft(null, collection(V20, null, List.of(item("Token", request))));
        assertThat(older.at("/files/0/line").asString()).startsWith("Read: 1 request");
        assertThat(older.at("/signIn/tokenUrl").asString()).isEqualTo("https://auth.example.invalid/token");
        String collection = collection(V21, null, List.of(item("Token", request), orderSign()));

        JsonNode signIn = draft(null, collection).get("signIn");
        assertThat(signIn.get("tokenUrl").asString()).isEqualTo("https://auth.example.invalid/token");
        assertThat(signIn.get("clientId").asString()).isEqualTo("{{clientId}}");
        // The form is read as a form is sent: percent decoding gives the reference back.
        assertThat(signIn.at("/clientSecret/reference").asString()).isEqualTo("{{clientSecret}}");
        assertThat(signIn.get("clientAuthentication").asString()).isEqualTo("BODY");
        assertThat(signIn.has("scope")).isFalse();
        assertThat(warnings(draft(null, collection))).contains("The token request is sent with the method GET. PAVE's sign-in sends POST: if "
                + "the sign-in is refused, that is the first thing to look at.");

        // A raw body that is JSON is never taken apart, and a grant_type in the url query counts.
        Map<String, Object> json = request("POST", "https://auth.example.invalid/token?grant_type=client_credentials&scope=system%2F*.read",
                List.of(), Map.of("mode", "raw", "raw", "{\"client_id\": \"{{clientId}}\"}"));
        JsonNode fromQuery = draft(null, collection(V21, null, List.of(item("Token", json)))).get("signIn");
        assertThat(fromQuery.get("tokenUrl").asString()).isEqualTo("https://auth.example.invalid/token?grant_type=client_credentials&scope="
                + "system%2F*.read");
        assertThat(fromQuery.get("scope").asString()).isEqualTo("system/*.read");
        assertThat(fromQuery.has("clientId")).isFalse();
        assertThat(fromQuery.has("clientAuthentication")).isFalse();
    }

    @Test
    void theBasicHelperOwnOrInheritedGivesTheClientAndBasicHeader() throws Exception {
        Map<String, Object> basic21 = Map.of("type", "basic", "basic", List.of(Map.of("key", "username", "value", "{{clientId}}"),
                Map.of("key", "password", "value", "{{clientSecret}}")));
        Map<String, Object> own = new LinkedHashMap<>(request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded",
                List.of(field("grant_type", "client_credentials", false), field("scope", "system/*.read", false)))));
        own.put("auth", basic21);
        JsonNode signIn = draft(null, collection(V21, null, List.of(item("Token", own)))).get("signIn");
        assertThat(signIn.get("clientId").asString()).isEqualTo("{{clientId}}");
        assertThat(signIn.at("/clientSecret/reference").asString()).isEqualTo("{{clientSecret}}");
        assertThat(signIn.get("clientAuthentication").asString()).isEqualTo("BASIC");
        assertThat(signIn.get("scope").asString()).isEqualTo("system/*.read");

        // The older form writes the helper as an object; a folder's helper is inherited, and noauth cuts it off.
        Map<String, Object> basic20 = Map.of("type", "basic", "basic", Map.of("username", "placeholder-client", "password", "{{clientSecret}}"));
        Map<String, Object> plain = request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded",
                List.of(field("grant_type", "client_credentials", false))));
        Map<String, Object> folder = new LinkedHashMap<>(folder("Auth", List.of(item("Token", plain))));
        folder.put("auth", basic20);
        JsonNode inherited = draft(null, collection(V21, null, List.of(folder))).get("signIn");
        assertThat(inherited.get("clientId").asString()).isEqualTo("placeholder-client");
        assertThat(inherited.get("clientAuthentication").asString()).isEqualTo("BASIC");
        Map<String, Object> cutOff = new LinkedHashMap<>(plain);
        cutOff.put("auth", Map.of("type", "noauth"));
        Map<String, Object> folderAgain = new LinkedHashMap<>(folder("Auth", List.of(item("Token", cutOff))));
        folderAgain.put("auth", basic20);
        JsonNode none = draft(null, collection(V21, null, List.of(folderAgain))).get("signIn");
        assertThat(none.has("clientId")).isFalse();
        assertThat(none.has("clientAuthentication")).isFalse();
    }

    @Test
    void aSecretTypedIntoTheTokenRequestIsNeverFilledNorShown() throws Exception {
        Map<String, Object> request = request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded", List.of(
                field("grant_type", "client_credentials", false), field("client_id", "{{clientId}}", false),
                field("client_secret", TYPED_SECRET, false))));
        JsonNode answer = draft(null, collection(V21, null, List.of(item("Token", request))));
        assertThat(answer.at("/signIn/clientSecret").isMissingNode()).isTrue();
        assertThat(answer.at("/signIn/clientAuthentication").asString()).isEqualTo("BODY");
        assertThat(warnings(answer)).contains("The collection's sign-in holds a client secret typed into the file. It was not filled: type it "
                + "in Client secret, or a {{variable}} or {{env:PAVE_NAME}} reference.");
        assertThat(answer.toString()).doesNotContain(TYPED_SECRET);
    }

    @Test
    void anotherGrantTypeFillsNothingFromTheRequestAndTheVariablesTakeOver() throws Exception {
        Map<String, Object> request = request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded", List.of(
                field("grant_type", "password", false), field("username", "{{user}}", false))));
        JsonNode answer = draft(environment("UAT", List.of(variable("tokenEndpoint", "https://auth.example.invalid/token"),
                variable("uat_client_id", "placeholder-client"))), collection(V21, null, List.of(item("Token", request))));
        assertThat(warnings(answer)).contains("The collection's token request \"Token\" uses the grant type password, so sign-in was not "
                + "filled from it. PAVE signs in with client credentials.");
        assertThat(answer.at("/signIn/from").asString()).isEqualTo("the environment's variable names");
        assertThat(answer.at("/signIn/tokenUrl").asString()).isEqualTo("{{tokenEndpoint}}");
        assertThat(answer.at("/signIn/clientId").asString()).isEqualTo("{{uat_client_id}}");
    }

    @Test
    void ofSeveralTokenRequestsTheFirstFillsAndALineCountsTheOthers() throws Exception {
        Map<String, Object> first = request("POST", "https://first.example.invalid/token", List.of(), Map.of("mode", "urlencoded",
                "urlencoded", List.of(field("grant_type", "client_credentials", false), field("client_id", "{{firstClient}}", false))));
        Map<String, Object> second = request("POST", "https://second.example.invalid/token", List.of(), Map.of("mode", "urlencoded",
                "urlencoded", List.of(field("grant_type", "client_credentials", false), field("client_id", "{{secondClient}}", false))));
        JsonNode answer = draft(null, collection(V21, null, List.of(folder("Sign in", List.of(item("First token", first))),
                item("Second token", second))));
        assertThat(answer.at("/signIn/tokenUrl").asString()).isEqualTo("https://first.example.invalid/token");
        assertThat(warnings(answer)).contains("The collection holds 1 other token request. Sign-in was filled from \"Sign in / First token\", "
                + "the first in the file.");
    }

    @Test
    void aTokenRequestBeatsABearerHelperThatAScriptWouldFillAndSaysSo() throws Exception {
        Map<String, Object> bearer = Map.of("type", "bearer", "bearer", List.of(Map.of("key", "token", "value", "{{accessToken}}")));
        Map<String, Object> request = request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded", List.of(
                field("grant_type", "client_credentials", false), field("client_id", "{{clientId}}", false),
                field("client_secret", "{{clientSecret}}", false))));
        JsonNode answer = draft(null, collection(V21, bearer, List.of(item("Token", request), orderSign())));
        assertThat(answer.at("/signIn/method").asString()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(answer.at("/signIn/bearerToken").isMissingNode()).isTrue();
        assertThat(warnings(answer)).contains("The collection's bearer helper was not used: PAVE asks for a token itself, with the client "
                + "credentials filled below.");
    }

    @Test
    void aDeeplyNestedCollectionIsWalkedWithoutAFramePerLevel() throws Exception {
        Map<String, Object> request = request("POST", "{{tokenUrl}}", List.of(), Map.of("mode", "urlencoded", "urlencoded", List.of(
                field("grant_type", "client_credentials", false), field("client_id", "{{clientId}}", false))));
        Object deepest = item("Token", request);
        for (int level = 0; level < 150; level++) {
            deepest = folder("Level " + level, List.of(deepest));
        }
        JsonNode answer = draft(null, collection(V21, null, List.of(deepest)));
        assertThat(answer.at("/signIn/clientId").asString()).isEqualTo("{{clientId}}");
        assertThat(answer.at("/signIn/from").asString()).startsWith("the collection's token request \"Level 149 / Level 148 / ");
    }

    // ------------------------------------------------------------------------------------------
    // The environment's variables by name
    // ------------------------------------------------------------------------------------------

    @Test
    void variablesWithAPrefixOrAnotherWordFillEachFieldTheyAloneCouldBe() throws Exception {
        JsonNode answer = draft(environment("UAT", List.of(variable("tokenEndpoint", "https://auth.example.invalid/token"),
                variable("uat_client_id", "placeholder-client"), variable("UAT.Client-Secret", ENV_SECRET),
                variable("uat_scope", "system/*.read"), variable("crdUrl", "https://crd.example.invalid"))), null);
        JsonNode signIn = answer.get("signIn");
        assertThat(signIn.get("method").asString()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(signIn.get("from").asString()).isEqualTo("the environment's variable names");
        assertThat(signIn.get("tokenUrl").asString()).isEqualTo("{{tokenEndpoint}}");
        assertThat(signIn.get("clientId").asString()).isEqualTo("{{uat_client_id}}");
        assertThat(signIn.at("/clientSecret/reference").asString()).isEqualTo("{{UAT.Client-Secret}}");
        assertThat(signIn.get("scope").asString()).isEqualTo("{{uat_scope}}");
        assertThat(strings(signIn.get("fields"))).containsExactly("Token url", "Client id", "Client secret", "Scope");
        assertThat(warnings(answer)).isEmpty();
        assertThat(answer.toString()).doesNotContain(ENV_SECRET);
    }

    @Test
    void anExactNameWinsAndSeveralCandidatesFillNothingButAreOfferedWithALine() throws Exception {
        JsonNode answer = draft(environment("UAT", List.of(variable("oauthUrl", "https://auth.example.invalid/token"),
                variable("uat_client_id", "placeholder-client"), variable("prod_client_id", "placeholder-client-2"),
                variable("clientSecret", ENV_SECRET), variable("uat_client_secret", ENV_SECRET + "-2"))), null);
        JsonNode signIn = answer.get("signIn");
        assertThat(signIn.get("method").asString()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(signIn.get("tokenUrl").asString()).isEqualTo("{{oauthUrl}}");
        assertThat(signIn.has("clientId")).isFalse();
        // clientSecret is an exact name, so it wins over uat_client_secret and nothing is offered for it.
        assertThat(signIn.at("/clientSecret/reference").asString()).isEqualTo("{{clientSecret}}");
        assertThat(signIn.get("candidates").size()).isEqualTo(1);
        assertThat(signIn.at("/candidates/0/field").asString()).isEqualTo("clientId");
        assertThat(strings(signIn.at("/candidates/0/references"))).containsExactly("{{uat_client_id}}", "{{prod_client_id}}");
        assertThat(strings(signIn.get("fields"))).containsExactly("Token url", "Client secret");
        assertThat(warnings(answer)).containsExactly("Several variables could be the client id: {{uat_client_id}}, {{prod_client_id}}. "
                + "Choose one.");
        assertThat(answer.toString()).doesNotContain(ENV_SECRET);
    }

    @Test
    void aFilledReferenceWhoseValueTheExportLeavesEmptyIsSaid() throws Exception {
        JsonNode answer = draft(environment("UAT", List.of(variable("tokenUrl", "https://auth.example.invalid/token"),
                variable("clientId", "placeholder-client"), variable("clientSecret", ""))), null);
        assertThat(answer.at("/signIn/clientSecret/reference").asString()).isEqualTo("{{clientSecret}}");
        assertThat(warnings(answer)).containsExactly("{{clientSecret}} is empty in this export. Postman exports a variable's initial value "
                + "only: type the value here, or set the initial value in Postman and export again.");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private JsonNode draft(String environment, String collection) throws Exception {
        MockMvc mvc = TestMvc.standalone(new DraftController(new EnvironmentDrafts(ConfigFixture.mock(directory).service)));
        Map<String, Object> body = new LinkedHashMap<>();
        if (environment != null) {
            body.put(EnvironmentDrafts.ENVIRONMENT_SLOT, Map.of("fileName", "uat.postman_environment.json", "text", environment));
        }
        if (collection != null) {
            body.put(EnvironmentDrafts.COLLECTION_SLOT, Map.of("fileName", "vendor.postman_collection.json", "text", collection));
        }
        String answer = mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(JsonSafe.mapper().writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonSafe.mapper().readTree(answer);
    }

    private static String environment(String name, List<Map<String, Object>> values) {
        return JsonSafe.mapper().writeValueAsString(Map.of("name", name, "values", values));
    }

    private static Map<String, Object> variable(String key, String value) {
        return Map.of("key", key, "value", value, "enabled", true);
    }

    private static String collection(String schema, Map<String, Object> auth, List<Object> items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection", "schema", schema));
        if (auth != null) {
            root.put("auth", auth);
        }
        root.put("item", items);
        return JsonSafe.mapper().writeValueAsString(root);
    }

    private static Map<String, Object> folder(String name, List<Object> items) {
        return Map.of("name", name, "item", items);
    }

    private static Map<String, Object> item(String name, Map<String, Object> request) {
        return Map.of("name", name, "request", request);
    }

    private static Map<String, Object> request(String method, Object url, List<Map<String, Object>> headers, Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("url", url);
        request.put("header", headers);
        request.put("body", body);
        return request;
    }

    private static Map<String, Object> header(String key, String value, boolean disabled) {
        return Map.of("key", key, "value", value, "disabled", disabled);
    }

    private static Map<String, Object> field(String key, String value, boolean disabled) {
        return Map.of("key", key, "value", value, "disabled", disabled);
    }

    /** A CRD order-sign request, so the collection also maps an operation as a vendor's does. */
    private static Map<String, Object> orderSign() {
        return item("order-sign", request("POST", "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}", List.of(),
                Map.of("mode", "raw", "raw", "{\"hook\": \"order-sign\"}")));
    }

    private static List<String> warnings(JsonNode answer) {
        return strings(answer.get("warnings"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(each -> out.add(each.asString()));
        }
        return out;
    }
}
