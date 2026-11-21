package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A name an uploaded file gives can be of any length, and no sentence of the tool grows with it.
 *
 * <p>A request name of a million characters once made one upload warning a million characters
 * long and the upload answer two megabytes. Every sentence that quotes a name a file supplies, on
 * the upload and on every screen that reads the uploaded file, now quotes a long one by its first
 * 60 characters and its length. Where a name is the tester's own label (the name field of a
 * collection, a request, an environment, an example or a variable) it is given whole: that is what
 * tells two of them apart, and the screen shows it.
 */
class LongNamesInAnUploadTest {

    private static final int LONG = 1_000_000;

    private static final String COLLECTION = "C".repeat(LONG);
    private static final String FOLDER = "F".repeat(LONG);
    private static final String REQUEST = "R".repeat(LONG);
    private static final String OTHER_REQUEST = "Q".repeat(LONG);
    private static final String EXAMPLE = "E".repeat(LONG);
    /** The parser of an upload refuses a JSON key of more than 50,000 characters, and a helper's type is also the key of its fields. */
    private static final String AUTH_TYPE = "t".repeat(40_000);
    private static final String BODY_MODE = "w".repeat(LONG);
    private static final String SECRET_VARIABLE = "V".repeat(LONG) + "_secret";
    private static final String MEMBER_VARIABLE = "M".repeat(LONG) + "_member_id";
    private static final String TWICE_VARIABLE = "D".repeat(LONG);
    private static final String ENVIRONMENT = "N".repeat(LONG) + " prod";
    private static final String PRODUCTION = "P".repeat(LONG);
    private static final String URL_VARIABLE = "U".repeat(LONG) + "_url";
    private static final String PLACEHOLDER = "H".repeat(LONG);

    /** The longest sentence any of these may give: the longest the tool writes about a short name is under 900. */
    private static final int LONGEST_SENTENCE = 2_000;

    @TempDir
    Path directory;

    private static String shown(String name, char letter) {
        return String.valueOf(letter).repeat(60) + "... (a name of " + name.length() + " characters)";
    }

    private static byte[] collection(boolean withRequest) {
        ObjectNode root = JsonSafe.mapper().createObjectNode();
        ObjectNode info = root.putObject("info");
        info.put("name", COLLECTION);
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        ArrayNode variables = root.putArray("variable");
        variables.addObject().put("key", SECRET_VARIABLE).put("value", "ZZLONG-VARIABLE-1").put("type", "secret");
        variables.addObject().put("key", MEMBER_VARIABLE).put("value", "S123456789");
        variables.addObject().put("key", TWICE_VARIABLE).put("value", "one");
        variables.addObject().put("key", TWICE_VARIABLE).put("value", "two");
        ObjectNode folder = root.putArray("item").addObject();
        folder.put("name", FOLDER);
        ObjectNode folderAuth = folder.putObject("auth");
        folderAuth.put("type", "basic");
        folderAuth.putArray("basic").addObject().put("key", "password").put("value", "ZZLONG-BASIC-2");
        ArrayNode items = folder.putArray("item");
        if (withRequest) {
            ObjectNode item = items.addObject();
            item.put("name", REQUEST);
            ObjectNode request = item.putObject("request");
            request.put("method", "POST");
            request.putArray("header").addObject().put("key", "Authorization").put("value", "Bearer ZZLONG-HEADER-3");
            request.putObject("url").put("raw", "{{baseUrl}}/Claim/$submit");
            request.putObject("body").put("mode", "raw").put("raw", "{\"resourceType\": \"Bundle\", \"password\": \"ZZLONG-BODY-4\"}");
            ObjectNode auth = request.putObject("auth");
            auth.put("type", AUTH_TYPE);
            auth.putArray(AUTH_TYPE).addObject().put("key", "password").put("value", "ZZLONG-HELPER-5");
            ObjectNode example = item.putArray("response").addObject();
            example.put("name", EXAMPLE).put("code", 200).put("status", "OK");
            example.putArray("header").addObject().put("key", "Authorization").put("value", "Bearer ZZLONG-EXAMPLE-6");
        }
        ObjectNode other = items.addObject();
        other.put("name", OTHER_REQUEST);
        ObjectNode request = other.putObject("request");
        request.put("method", "POST");
        request.putObject("url").put("raw", "{{baseUrl}}/Claim/$inquire");
        request.putObject("body").put("mode", BODY_MODE);
        return JsonSafe.mapper().writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] environment(String name, String... keysAndValues) {
        ObjectNode root = JsonSafe.mapper().createObjectNode();
        root.put("name", name);
        ArrayNode values = root.putArray("values");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.addObject().put("key", keysAndValues[i]).put("value", keysAndValues[i + 1]).put("enabled", true);
        }
        return JsonSafe.mapper().writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every text of the answer longer than {@link #LONGEST_SENTENCE} is one of the long names, whole,
     * where the answer gives it as a label. None is a sentence that quotes one.
     */
    private static void onlyLabelsAreLong(Object answer, Set<String> labels) {
        JsonNode root = JsonSafe.mapper().valueToTree(answer);
        Deque<Map.Entry<String, JsonNode>> next = new ArrayDeque<>();
        next.push(Map.entry("", root));
        List<String> tooLong = new ArrayList<>();
        while (!next.isEmpty()) {
            Map.Entry<String, JsonNode> at = next.pop();
            JsonNode node = at.getValue();
            if (node.isString()) {
                String text = node.asString();
                if (text.length() > LONGEST_SENTENCE && !labels.contains(text)) {
                    tooLong.add(at.getKey() + ": " + text.substring(0, 80) + "... (" + text.length() + ")");
                }
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    next.push(Map.entry(at.getKey() + "[" + i + "]", node.get(i)));
                }
            } else if (node.isObject()) {
                node.properties().forEach(entry -> next.push(Map.entry(at.getKey() + "." + entry.getKey(), entry.getValue())));
            }
        }
        assertThat(tooLong).as("long texts of the answer that are not a name given whole as a label").isEmpty();
    }

    @Test
    void everySentenceOfACollectionUploadQuotesALongNameByItsStartAndItsLength() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        ConfigViews.CollectionUploaded uploaded = fixture.service.uploadCollection("long.json", collection(true));

        List<String> warnings = uploaded.warnings();
        assertThat(warnings).allSatisfy(warning -> assertThat(warning.length()).isLessThan(LONGEST_SENTENCE));
        String all = String.join("\n", warnings);
        assertThat(all).doesNotContain("ZZLONG");
        String request = shown(FOLDER, 'F') + " / " + shown(REQUEST, 'R');
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The variable \"" + shown(SECRET_VARIABLE, 'V')
                + "\" holds a secret in plain text"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The header \"Authorization\" holds a credential typed "
                + "into the collection in 1 request, the first being \"" + request + "\""));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The request \"" + request + "\" holds a credential"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The saved example \"" + shown(EXAMPLE, 'E')
                + "\" of the request \"" + request + "\" holds a credential, in its header Authorization."));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The authentication helper basic set on the folder \""
                + shown(FOLDER, 'F') + "\" holds a credential"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The authentication helper " + shown(AUTH_TYPE, 't')
                + " set on the request \"" + request + "\" holds a credential"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The variable \"" + shown(MEMBER_VARIABLE, 'M')
                + "\" reads as member data"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The request \"" + request + "\" carries a saved example"));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The variable \"" + shown(TWICE_VARIABLE, 'D')
                + "\" is defined 2 times."));

        String collectionId = uploaded.collection().id();
        // The value of a secret is shown as the mask that names its variable, in the place of the value: a label too.
        String mask = "[secret " + SECRET_VARIABLE + "]";
        onlyLabelsAreLong(uploaded, Set.of(COLLECTION, REQUEST, OTHER_REQUEST, FOLDER, EXAMPLE, SECRET_VARIABLE, MEMBER_VARIABLE,
                TWICE_VARIABLE, AUTH_TYPE, mask));
        onlyLabelsAreLong(fixture.service.collections(), Set.of(COLLECTION, REQUEST, OTHER_REQUEST, FOLDER, SECRET_VARIABLE,
                MEMBER_VARIABLE, TWICE_VARIABLE, AUTH_TYPE, mask));

        // The request in full: its notes and its helper are sentences; its name and its example's name are labels.
        StoredCollection stored = fixture.store.snapshot().collection(collectionId);
        String otherId = stored.requests().stream().filter(each -> each.request().name().equals(OTHER_REQUEST)).findFirst().orElseThrow()
                .request().id();
        String requestId = stored.requests().stream().filter(each -> each.request().name().equals(REQUEST)).findFirst().orElseThrow()
                .request().id();
        ConfigViews.RequestDetail other = fixture.service.request(collectionId, otherId);
        assertThat(other.request().notes()).contains("The body is in Postman's " + shown(BODY_MODE, 'w') + " mode. This tool loads raw "
                + "bodies only, so this one is not loaded.");
        onlyLabelsAreLong(other, Set.of(OTHER_REQUEST, FOLDER, COLLECTION, BODY_MODE));
        ConfigViews.RequestDetail detail = fixture.service.request(collectionId, requestId);
        assertThat(detail.request().authHelper()).isEqualTo(shown(AUTH_TYPE, 't') + ", set on this request");
        onlyLabelsAreLong(detail, Set.of(REQUEST, FOLDER, EXAMPLE, AUTH_TYPE, COLLECTION));

        // A mapping correction made on the request, then dropped because the file no longer has it.
        fixture.service.correctMapping(collectionId, requestId, EpaOperation.PAS_INQUIRE);
        List<String> again = fixture.service.uploadCollection("long.json", collection(false)).warnings();
        assertThat(again).anySatisfy(warning -> assertThat(warning).isEqualTo("The correction that mapped \"" + request + "\" to "
                + EpaOperation.PAS_INQUIRE.label() + " was dropped, because the new file has no request with that name in that folder."));
        assertThat(again).allSatisfy(warning -> assertThat(warning.length()).isLessThan(LONGEST_SENTENCE));
    }

    @Test
    void everySentenceOfAnEnvironmentUploadAndOfWhatIsInForceQuotesALongNameByItsStartAndItsLength() {
        ConfigFixture fixture = ConfigFixture.on(directory).allowProdWrites().build();
        byte[] sandbox = environment(ENVIRONMENT, URL_VARIABLE, "https://prod.example.invalid/fhir", "token_secret", "ZZLONG-ENV-7");

        // Declared sandbox, it reads as production: the refusal names the environment and the variable.
        ValidationException refused = catchThrowableOfType(ValidationException.class,
                () -> fixture.service.uploadEnvironment("long.json", sandbox, EnvironmentKind.SANDBOX, false));
        assertThat(refused.fieldErrors()).extracting(FieldError::message).singleElement().satisfies(message -> {
            assertThat(message).startsWith("It looks like production because its name is \"" + shown(ENVIRONMENT, 'N') + "\", and the "
                    + "variable " + shown(URL_VARIABLE, 'U') + " holds a url that contains \"prod\".");
            assertThat(message.length()).isLessThan(LONGEST_SENTENCE);
        });
        ConfigViews.EnvironmentUploaded uploaded = fixture.service.uploadEnvironment("long.json", sandbox, EnvironmentKind.SANDBOX, true);
        assertThat(uploaded.warnings()).allSatisfy(warning -> assertThat(warning.length()).isLessThan(LONGEST_SENTENCE));
        onlyLabelsAreLong(uploaded, Set.of(ENVIRONMENT, URL_VARIABLE));

        // A production environment of a long name, active: every write it blocks names it shortly.
        fixture.service.uploadEnvironment("prod.json", environment(PRODUCTION, "baseUrl", "https://pas.example.invalid/fhir"),
                EnvironmentKind.PROD, false);
        fixture.service.uploadCollection("long.json", collection(true));
        fixture.store.activate(PRODUCTION);
        ConfigViews.Effective effective = fixture.service.effective(null);
        assertThat(effective.writeAccess().reason()).isEqualTo("\"" + shown(PRODUCTION, 'P') + "\" is production and writes are locked "
                + "for this session. Unlock them by typing the environment name.");
        assertThat(effective.readiness().toString()).doesNotContain("P".repeat(61));
        assertThat(effective.operations()).anySatisfy(row -> assertThat(row.blocked()).contains("is blocked: \"" + shown(PRODUCTION, 'P')
                + "\" is a production environment"));
        onlyLabelsAreLong(effective, Set.of(PRODUCTION, REQUEST, OTHER_REQUEST, COLLECTION, FOLDER));
        onlyLabelsAreLong(fixture.service.environments(), Set.of(ENVIRONMENT, URL_VARIABLE, PRODUCTION));
    }

    /**
     * A placeholder's name is the file's too: a url and a header that need a value for one of a
     * million characters once gave two notes of "In force now" a million characters each.
     */
    @Test
    void theSentencesOfWhatIsInForceQuoteALongPlaceholderByItsStartAndItsLength() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.service.uploadEnvironment("sandbox.json", environment("Sandbox", "baseUrl", "https://pas.example.invalid/fhir"),
                EnvironmentKind.SANDBOX, true);
        fixture.store.activate("Sandbox");
        ObjectNode root = JsonSafe.mapper().createObjectNode();
        root.putObject("info").put("name", "Placeholders").put("schema",
                "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        ObjectNode item = root.putArray("item").addObject();
        item.put("name", "PAS submit");
        ObjectNode request = item.putObject("request");
        request.put("method", "POST");
        request.putArray("header").addObject().put("key", "X-Tenant").put("value", "{{" + PLACEHOLDER + "}}");
        request.putObject("url").put("raw", "{{baseUrl}}/{{" + PLACEHOLDER + "}}/Claim/$submit");
        request.putObject("body").put("mode", "raw").put("raw", "{\"resourceType\": \"Bundle\"}");
        fixture.service.uploadCollection("placeholders.json", JsonSafe.mapper().writeValueAsString(root).getBytes(StandardCharsets.UTF_8));

        ConfigViews.Effective effective = fixture.service.effective(null);
        String braced = "{{" + shown(PLACEHOLDER, 'H') + "}}";
        assertThat(effective.operations()).anySatisfy(row -> assertThat(row.note()).startsWith("The url still needs a value for "
                + braced + ", the header X-Tenant still needs a value for " + braced + ", and the active environment is \"Sandbox\".")
                .contains(" Give " + braced + " a value in an environment on the Config tab"));
        // The list of what is missing keeps each name whole: it is the label a value is given against.
        assertThat(effective.operations()).filteredOn(row -> row.target() != null).singleElement()
                .satisfies(row -> assertThat(row.target().unresolved()).containsExactly(PLACEHOLDER));
        String url = "https://pas.example.invalid/fhir/{{" + PLACEHOLDER + "}}/Claim/$submit";
        onlyLabelsAreLong(effective, Set.of(PLACEHOLDER, url, "{{baseUrl}}/{{" + PLACEHOLDER + "}}/Claim/$submit", "{{" + PLACEHOLDER + "}}"));
    }

    @Test
    void aNameOfOrdinaryLengthIsQuotedWholeAndALongOneByItsStartAndItsLength() {
        assertThat(Names.shown(null)).isNull();
        assertThat(Names.shown("Submit a claim")).isEqualTo("Submit a claim");
        assertThat(Names.shown("x".repeat(60))).isEqualTo("x".repeat(60));
        assertThat(Names.shown("x".repeat(61))).isEqualTo("x".repeat(60) + "... (a name of 61 characters)");
        // A character outside the basic plane is never cut in half.
        String face = new String(Character.toChars(0x1F600));
        assertThat(Names.shown("x".repeat(59) + face + "y")).isEqualTo("x".repeat(59) + "... (a name of 62 characters)");
        assertThat(Names.path(List.of("PAS", "Claims"), "Submit")).isEqualTo("PAS / Claims / Submit");
        // Each name of a path is held on its own, so ordinary names deep in folders read as before.
        String folder = "Folder of an ordinary length, forty-nine character";
        assertThat(Names.path(List.of(folder, folder), "Submit")).isEqualTo(folder + " / " + folder + " / Submit");
        assertThat(Names.path(List.of(), "y".repeat(70))).isEqualTo("y".repeat(60) + "... (a name of 70 characters)");
    }
}
