package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts;
import org.p32h.interop.epa.pave.service.error.FileGuard;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * "Fill the form from these files" (R-07, R-08): any file answers 200 with a line about that file,
 * a secret is filled only as a reference, an undefined variable stays as written, nothing is
 * stored. The broken files are the small files of the upload hunt, all placeholders, kept in one
 * zip so that their encodings reach the test as they are. Every member ID here is S123456789 and
 * every host ends in .invalid.
 */
class DraftControllerTest {

    private static final String DRAFT = "/api/config/environments/draft";

    /** A literal secret typed into a file: it must not appear in any answer. */
    private static final String LITERAL_SECRET = "placeholder-literal-secret-value";

    private static final String WITHOUT_TYPE = """
            {"hook": "order-sign", "context": {"patientId": "S123456789",
             "draftOrders": {"resourceType": "Bundle", "entry": [{"resource": {"resourceType": "ServiceRequest"}}]}}}
            """;

    private static final String WITH_TYPE = """
            {"hook": "order-sign", "context": {"patientId": "S123456789",
             "draftOrders": {"resourceType": "Bundle", "type": "collection", "entry": [{"resource": {"resourceType": "ServiceRequest"}}]}}}
            """;

    @TempDir
    Path directory;

    // ------------------------------------------------------------------------------------------
    // R-07: no file ends in an internal error
    // ------------------------------------------------------------------------------------------

    @Test
    void everyFileOfTheHuntAnswers200WithALineAboutItInEitherSlot() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);
        List<String> failures = new ArrayList<>();
        int files = 0;
        for (Map.Entry<String, byte[]> entry : huntFiles().entrySet()) {
            for (String text : texts(entry.getValue())) {
                for (String slot : List.of(EnvironmentDrafts.ENVIRONMENT_SLOT, EnvironmentDrafts.COLLECTION_SLOT)) {
                    MvcResult result = mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON)
                            .content(body(slot, entry.getKey(), text, null, null, null))).andReturn();
                    assertThat(result.getResponse().getStatus()).as(entry.getKey() + " in " + slot).isEqualTo(200);
                    JsonNode answer = json(result);
                    JsonNode file = answer.get("files").get(0);
                    assertThat(answer.get("files").size()).isEqualTo(1);
                    assertThat(file.get("slot").asString()).isEqualTo(slot);
                    String line = file.get("line").asString();
                    assertThat(line).as(entry.getKey()).matches("(Read|Not read): .+");
                    assertThat(file.get("read").asBoolean()).isEqualTo(line.startsWith("Read: "));
                    if (file.has("reference")) {
                        failures.add(entry.getKey() + " in " + slot + ": " + file.get("cause").asString());
                    }
                }
            }
            files++;
        }
        assertThat(files).isGreaterThan(100);
        // Each of these files is refused for a reason of its own, never as a failure of this tool.
        assertThat(failures).isEmpty();
    }

    @Test
    void aStackOverflowWhileReadingOneFileIsThatFilesLineAndTheOtherFileIsStillRead() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        EnvironmentDrafts drafts = new EnvironmentDrafts(fixture.service::environments, text -> {
            throw new StackOverflowError();
        }, PostmanParser::collection);
        MockMvc mvc = TestMvc.standalone(new DraftController(drafts));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "env.json",
                        PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT), EnvironmentDrafts.COLLECTION_SLOT, "collection.json",
                        PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].slot").value("environmentExport"))
                .andExpect(jsonPath("$.files[0].read").value(false))
                .andExpect(jsonPath("$.files[0].line").value(org.hamcrest.Matchers.startsWith("Not read: " + FileGuard.COULD_NOT_READ)))
                .andExpect(jsonPath("$.files[0].reference").isString())
                .andExpect(jsonPath("$.files[0].cause").value(org.hamcrest.Matchers.startsWith("java.lang.StackOverflowError")))
                .andExpect(jsonPath("$.files[1].slot").value("collectionExport"))
                .andExpect(jsonPath("$.files[1].read").value(true))
                .andExpect(jsonPath("$.files[1].line").value(org.hamcrest.Matchers.startsWith("Read: 13 requests")))
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void aLinkageErrorWhileReadingAFileIsThatFilesLineWithTheMissingClassAsItsCause() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        EnvironmentDrafts drafts = new EnvironmentDrafts(fixture.service::environments, PostmanParser::environment, text -> {
            throw new NoClassDefFoundError("org/p32h/interop/epa/pave/postman/Placeholders");
        });
        MockMvc mvc = TestMvc.standalone(new DraftController(drafts));

        JsonNode answer = json(mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(
                EnvironmentDrafts.COLLECTION_SLOT, "collection.json", PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION), null, null, null)))
                .andExpect(status().isOk()).andReturn());

        JsonNode file = answer.get("files").get(0);
        assertThat(file.get("read").asBoolean()).isFalse();
        assertThat(file.get("line").asString()).startsWith("Not read: " + FileGuard.COULD_NOT_READ);
        assertThat(file.get("cause").asString()).startsWith("java.lang.NoClassDefFoundError").contains("Placeholders");
        assertThat(answer.get("operations").size()).isZero();
        assertThat(answer.get("shapes").size()).isZero();
    }

    @Test
    void aFileTheParserRefusesKeepsTheParsersOwnWords() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "c.json",
                        PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].line").value("Not read: This file is a Postman collection, not an environment export. "
                        + "Choose it as the Collection export, or upload it in Configuration > Collections."))
                .andExpect(jsonPath("$.files[0].reference").doesNotExist())
                .andExpect(jsonPath("$.files[0].cause").doesNotExist());
    }

    // ------------------------------------------------------------------------------------------
    // R-08: what is filled, and what is not
    // ------------------------------------------------------------------------------------------

    @Test
    void theEnvironmentExportGivesItsNameVariablesMaskedAndSignInFromVariableNamesAsReferences() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        MvcResult result = mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT,
                        "Onyx Sandbox.postman_environment.json", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].line").value("Read: 12 variables, 2 secrets."))
                .andExpect(jsonPath("$.name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.nameTaken").value(false))
                .andExpect(jsonPath("$.signIn.method").value("CLIENT_CREDENTIALS"))
                .andExpect(jsonPath("$.signIn.tokenUrl").value("{{tokenUrl}}"))
                .andExpect(jsonPath("$.signIn.clientId").value("{{clientId}}"))
                .andExpect(jsonPath("$.signIn.clientSecret.set").value(true))
                .andExpect(jsonPath("$.signIn.clientSecret.reference").value("{{clientSecret}}"))
                .andExpect(jsonPath("$.signIn.clientAuthentication").doesNotExist())
                .andExpect(jsonPath("$.signIn.from").value("the environment's variable names"))
                .andExpect(jsonPath("$.variables[?(@.name == 'clientSecret')].secret").value(true))
                .andExpect(jsonPath("$.variables[?(@.name == 'crdUrl')].display").value("https://sandbox.onyx.example.invalid"))
                .andExpect(jsonPath("$.operations").isEmpty())
                .andExpect(jsonPath("$.epoch").isNumber())
                .andReturn();

        String answer = result.getResponse().getContentAsString();
        assertThat(answer).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
    }

    @Test
    void theCollectionsOAuth2SignInWinsOverVariableNamesAndALiteralSecretIsNeverFilled() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));
        String collection = collection(oauth2("client_credentials", "{{authEndpoint}}", "{{clientId}}", LITERAL_SECRET, "header"),
                WITHOUT_TYPE);

        MvcResult result = mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(
                        EnvironmentDrafts.ENVIRONMENT_SLOT, "env.json", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT),
                        EnvironmentDrafts.COLLECTION_SLOT, "collection.json", collection)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn.method").value("CLIENT_CREDENTIALS"))
                .andExpect(jsonPath("$.signIn.from").value("the collection's sign-in"))
                .andExpect(jsonPath("$.signIn.tokenUrl").value("{{authEndpoint}}"))
                .andExpect(jsonPath("$.signIn.clientId").value("{{clientId}}"))
                .andExpect(jsonPath("$.signIn.clientAuthentication").value("BASIC"))
                .andExpect(jsonPath("$.signIn.clientSecret").doesNotExist())
                .andReturn();

        JsonNode answer = json(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(LITERAL_SECRET);
        assertThat(warnings(answer)).contains("The collection's sign-in holds a client secret typed into the file. It was not filled: type "
                + "it in Client secret, or a {{variable}} or {{env:PAVE_NAME}} reference.");
        // An undefined variable stays as written in the field, with one line.
        assertThat(warnings(answer)).contains("Sign-in refers to {{authEndpoint}}, which the environment \"Onyx Sandbox\" does not "
                + "define. Give it a value in Postman and export again, or type the value.");
        assertThat(warnings(answer).stream().filter(line -> line.contains("{{clientId}}"))).isEmpty();
    }

    @Test
    void aSecretThatIsWhollyAReferenceIsFilledAsThatReferenceAndBodyAuthenticationIsRead() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));
        String collection = collection(oauth2("client_credentials", "https://auth.example.invalid/token", "{{clientId}}",
                "{{env:PAVE_ONYX_CLIENT_SECRET}}", "body"), WITHOUT_TYPE);

        JsonNode answer = json(mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(
                        EnvironmentDrafts.COLLECTION_SLOT, "collection.json", collection, null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn.clientSecret.reference").value("{{env:PAVE_ONYX_CLIENT_SECRET}}"))
                .andExpect(jsonPath("$.signIn.clientAuthentication").value("BODY"))
                .andExpect(jsonPath("$.signIn.tokenUrl").value("https://auth.example.invalid/token"))
                .andReturn());

        // No environment in this draft: the one name nothing defines is said once, the machine's own reference never.
        assertThat(warnings(answer)).containsExactly("Sign-in refers to {{clientId}}. Choose the environment export that defines it, or "
                + "type the value.");
    }

    @Test
    void aGrantOtherThanClientCredentialsFillsNoSignInAndSaysWhy() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));
        String collection = collection(oauth2("authorization_code", "{{tokenUrl}}", "{{clientId}}", "{{clientSecret}}", null), WITHOUT_TYPE);

        JsonNode answer = json(mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(
                        EnvironmentDrafts.COLLECTION_SLOT, "collection.json", collection, null, null, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signIn").doesNotExist()).andReturn());

        assertThat(warnings(answer)).contains("The collection's OAuth2 sign-in uses the grant type authorization_code, so sign-in was not "
                + "filled from it. PAVE signs in with client credentials.");
    }

    @Test
    void aBearerHelperFillsABearerTokenOnlyAsAReference() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));
        Map<String, Object> asReference = Map.of("type", "bearer", "bearer", List.of(Map.of("key", "token", "value", "{{accessToken}}")));
        Map<String, Object> literal = Map.of("type", "bearer", "bearer", List.of(Map.of("key", "token", "value", LITERAL_SECRET)));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT, "c.json",
                        collection(asReference, WITHOUT_TYPE), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn.method").value("BEARER_TOKEN"))
                .andExpect(jsonPath("$.signIn.bearerToken.reference").value("{{accessToken}}"));

        MvcResult refused = mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT,
                        "c.json", collection(literal, WITHOUT_TYPE), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signIn").doesNotExist())
                .andReturn();
        assertThat(refused.getResponse().getContentAsString()).doesNotContain(LITERAL_SECRET).contains("holds a bearer token typed into "
                + "the file");
    }

    @Test
    void theShapeTheOrderSignSampleShowsIsGivenWithItsEvidence() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT, "c.json",
                        collection(null, WITHOUT_TYPE), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shapes.length()").value(1))
                .andExpect(jsonPath("$.shapes[0].key").value("CRD_DRAFT_ORDERS_TYPE"))
                .andExpect(jsonPath("$.shapes[0].value").value("LEFT_OUT"))
                .andExpect(jsonPath("$.shapes[0].from").value("the collection's sample"))
                .andExpect(jsonPath("$.shapes[0].requestName").value("order-sign Happy Path"))
                .andExpect(jsonPath("$.operations[0].operation").value("CRD_ORDER_SIGN"))
                .andExpect(jsonPath("$.operations[0].short").value("order-sign"))
                .andExpect(jsonPath("$.operations[0].label").value("CRD order-sign"))
                .andExpect(jsonPath("$.operations[0].requestName").value("order-sign Happy Path"))
                .andExpect(jsonPath("$.operations[0].template").value("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}"))
                .andExpect(jsonPath("$.operations[0].scripted").value(false));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT, "c.json",
                        collection(null, WITH_TYPE), null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shapes[0].value").value("SENT"));
    }

    @Test
    void theVendorCollectionMapsEachOperationOnceFromItsFirstRequest() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        JsonNode answer = json(mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT,
                "c.json", PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION), null, null, null))).andExpect(status().isOk()).andReturn());

        List<String> operations = new ArrayList<>();
        answer.get("operations").forEach(row -> operations.add(row.get("operation").asString()));
        assertThat(operations).doesNotHaveDuplicates().contains("CRD_ORDER_SIGN", "PAS_SUBMIT", "DTR_QUESTIONNAIRE_PACKAGE");
        assertThat(operations).doesNotContain("UNMAPPED");
        JsonNode orderSign = rowOf(answer.get("operations"), "CRD_ORDER_SIGN");
        assertThat(orderSign.get("requestName").asString()).isEqualTo("order-sign Happy Path");
    }

    @Test
    void nameTakenIsTrueOnlyForANewEnvironmentWhoseNameIsStored() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        String storedId = fixture.service.environments().environments().get(0).id();
        MockMvc mvc = mvc(fixture);
        String sandbox = PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT);

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json", sandbox,
                        null, null, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameTaken").value(true));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json", sandbox,
                        null, null, null, storedId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameTaken").value(false)).andExpect(jsonPath("$.warnings").isEmpty());

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json",
                        PostmanFiles.text(PostmanFiles.UAT_ENVIRONMENT), null, null, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameTaken").value(false));

        // Editing with another environment's export: the name stays, and a line says so.
        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json",
                        PostmanFiles.text(PostmanFiles.UAT_ENVIRONMENT), null, null, null, storedId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nameTaken").value(false))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("The environment keeps its name \"Onyx "
                        + "Sandbox\": a name cannot change.")));
    }

    @Test
    void editingWithOnlyACollectionChecksPlaceholdersAgainstTheStoredEnvironment() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        String storedId = fixture.service.environments().environments().get(0).id();
        MockMvc mvc = mvc(fixture);
        String collection = collection(oauth2("client_credentials", "{{tokenUrl}}", "{{clientId}}", "{{clientSecret}}", null), WITHOUT_TYPE);

        JsonNode answer = json(mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.COLLECTION_SLOT,
                "c.json", collection, null, null, null, storedId))).andExpect(status().isOk()).andReturn());

        assertThat(warnings(answer)).isEmpty();
        assertThat(answer.get("signIn").get("clientSecret").get("reference").asString()).isEqualTo("{{clientSecret}}");
    }

    @Test
    void aDraftStoresNothing() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);
        List<Path> before = listing();

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json",
                        PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT), EnvironmentDrafts.COLLECTION_SLOT, "c.json",
                        PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION))))
                .andExpect(status().isOk());

        assertThat(listing()).isEqualTo(before);
        assertThat(fixture.service.environments().environments()).isEmpty();
        assertThat(fixture.service.collections().collections()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Refusals of the request itself
    // ------------------------------------------------------------------------------------------

    @Test
    void aRequestWithNoFileIsTheOnly422() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("environmentExport"));
    }

    @Test
    void aMisspeltPropertyIsRefusedByName() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content("{\"environmentExpor\": {\"fileName\": \"e.json\", "
                        + "\"text\": \"{}\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("environmentExpor"));
    }

    @Test
    void anIdThatNamesNoStoredEnvironmentIs404() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content(body(EnvironmentDrafts.ENVIRONMENT_SLOT, "e.json",
                        PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT), null, null, null, "no-such-environment")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anEmptyTextOrNoTextIsThatFilesLine() throws Exception {
        MockMvc mvc = mvc(ConfigFixture.mock(directory));

        mvc.perform(post(DRAFT).contentType(MediaType.APPLICATION_JSON).content("{\"environmentExport\": {\"fileName\": \"e.json\"}, "
                        + "\"collectionExport\": {\"fileName\": \"c.json\", \"text\": \"\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].read").value(false))
                .andExpect(jsonPath("$.files[0].reference").doesNotExist())
                .andExpect(jsonPath("$.files[1].read").value(false))
                .andExpect(jsonPath("$.files[1].reference").doesNotExist());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static MockMvc mvc(ConfigFixture fixture) {
        return TestMvc.standalone(new DraftController(new EnvironmentDrafts(fixture.service)));
    }

    private List<Path> listing() throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.sorted().toList();
        }
    }

    private static String body(String slot, String fileName, String text, String otherSlot, String otherName, String otherText) {
        return body(slot, fileName, text, otherSlot, otherName, otherText, null);
    }

    private static String body(String slot, String fileName, String text, String otherSlot, String otherName, String otherText,
                               String forId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(slot, file(fileName, text));
        if (otherSlot != null) {
            body.put(otherSlot, file(otherName, otherText));
        }
        if (forId != null) {
            body.put("forId", forId);
        }
        return JsonSafe.mapper().writeValueAsString(body);
    }

    private static Map<String, Object> file(String fileName, String text) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("fileName", fileName);
        file.put("text", text);
        return file;
    }

    private static Map<String, Object> oauth2(String grant, String tokenUrl, String clientId, String clientSecret, String authentication) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("key", "tokenName", "value", "Placeholder token"));
        fields.add(Map.of("key", "grant_type", "value", grant));
        fields.add(Map.of("key", "accessTokenUrl", "value", tokenUrl));
        fields.add(Map.of("key", "clientId", "value", clientId));
        fields.add(Map.of("key", "clientSecret", "value", clientSecret));
        if (authentication != null) {
            fields.add(Map.of("key", "client_authentication", "value", authentication));
        }
        return Map.of("type", "oauth2", "oauth2", fields);
    }

    /** A placeholder collection with one order-sign request and one PAS submit request, and the auth helper given. */
    private static String collection(Map<String, Object> auth, String orderSignBody) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", Map.of("name", "Placeholder vendor collection",
                "schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"));
        if (auth != null) {
            root.put("auth", auth);
        }
        root.put("item", List.of(
                Map.of("name", "CRD", "item", List.of(Map.of("name", "order-sign Happy Path", "request", Map.of("method", "POST",
                        "url", "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}",
                        "body", Map.of("mode", "raw", "raw", orderSignBody))))),
                Map.of("name", "PAS", "item", List.of(Map.of("name", "submit Happy Path", "request", Map.of("method", "POST",
                        "url", "{{baseUrl}}/Claim/$submit", "body", Map.of("mode", "raw", "raw", "{}")))))));
        return JsonSafe.mapper().writeValueAsString(root);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JsonSafe.mapper().readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> warnings(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.get("warnings").forEach(line -> out.add(line.asString()));
        return out;
    }

    private static JsonNode rowOf(JsonNode rows, String operation) {
        for (JsonNode row : rows) {
            if (operation.equals(row.get("operation").asString())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + operation);
    }

    /**
     * The texts a file can reach the server as: decoded as UTF-8 with bad bytes replaced, as a
     * client that does not check would send it, and, for a file that starts with a UTF-16 byte
     * order mark, decoded as UTF-16, as the page does.
     */
    private static List<String> texts(byte[] bytes) {
        List<String> texts = new ArrayList<>();
        texts.add(new String(bytes, StandardCharsets.UTF_8));
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
            texts.add(new String(bytes, StandardCharsets.UTF_16));
        }
        return texts;
    }

    private static Map<String, byte[]> huntFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream in = DraftControllerTest.class.getResourceAsStream("/draft/hunt-files.zip");
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                files.put(entry.getName(), out.toByteArray());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
