package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every setting of plan 5.1 is stored and read back as saved, over the endpoints the form uses
 * (plan 5.3, 5.4); a stored entry this tool cannot read is unreadable, listed and never used, and
 * never stops the start.
 */
class EnvironmentSettingsTest {

    @TempDir
    Path directory;

    private static MockMvc mvc(ConfigFixture fixture) {
        return TestMvc.standalone(new ConfigController(fixture.service));
    }

    private static MockHttpServletResponse save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonSafe.parse(response.getContentAsString()).node();
    }

    private static ObjectNode body() {
        return JsonSafe.mapper().createObjectNode();
    }

    /** Every setting, typed once, with the sandbox export: what the form posts for a new environment. */
    private static ObjectNode everySetting() {
        ObjectNode body = body();
        body.put("kind", "sandbox");
        ObjectNode export = body.putObject("environmentExport");
        export.put("fileName", "sandbox.json");
        export.put("text", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "{{tokenUrl}}");
        signIn.put("clientId", "{{clientId}}");
        signIn.put("clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}");
        signIn.put("scope", "system/*.read");
        signIn.put("clientAuthentication", "BODY");
        ObjectNode timeouts = body.putObject("timeouts");
        timeouts.put("connectMs", 3000);
        timeouts.put("readMs", 60000);
        ObjectNode addresses = body.putObject("addresses");
        addresses.put("PAS_SUBMIT", "https://vendor.example.invalid/fhir/Claim/$submit");
        addresses.put("SUBSCRIPTION", "{{baseUrl}}/notify");
        body.put("memberIdRule", "SPACED");
        body.putObject("shapes").put("CRD_DRAFT_ORDERS_TYPE", "LEFT_OUT");
        ObjectNode values = body.putObject("values");
        values.put("payerName", "Placeholder Payer");
        values.put("pasLocalCode", "S123");
        return body;
    }

    @Test
    void everySettingIsStoredAndReadBackAfterARestart() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockHttpServletResponse saved = save(mvc(fixture), everySetting());
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        JsonNode answer = json(saved);
        assertThat(answer.get("environment").get("name").stringValue()).isEqualTo("Onyx Sandbox");
        assertThat(answer.get("epoch").longValue()).isEqualTo(fixture.store.epoch());
        // Saving never makes an environment active.
        assertThat(fixture.store.snapshot().activeName()).isNull();

        ConfigFixture restarted = ConfigFixture.mock(directory);
        EnvironmentSettings settings = restarted.store.snapshot().byName("Onyx Sandbox").settings();
        assertThat(settings.signIn().method()).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        assertThat(settings.signIn().tokenUrl()).isEqualTo("{{tokenUrl}}");
        assertThat(settings.signIn().clientSecret()).isEqualTo("{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(settings.signIn().clientAuthentication()).isEqualTo(SignInSettings.ClientAuthentication.BODY);
        assertThat(settings.timeouts().connectMs()).isEqualTo(3000);
        assertThat(settings.timeouts().readMs()).isEqualTo(60000);
        assertThat(settings.addresses()).containsEntry(EpaOperation.PAS_SUBMIT, "https://vendor.example.invalid/fhir/Claim/$submit")
                .containsEntry(EpaOperation.SUBSCRIPTION_CREATE, "{{baseUrl}}/notify").hasSize(2);
        assertThat(settings.memberIdRule()).isEqualTo(MemberIdRule.SPACED);
        assertThat(settings.shapes()).containsExactly(Map.entry(RequestShape.CRD_DRAFT_ORDERS_TYPE, "LEFT_OUT"));
        assertThat(settings.values()).containsExactly(Map.entry("payerName", "Placeholder Payer"), Map.entry("pasLocalCode", "S123"));

        String id = restarted.store.snapshot().byName("Onyx Sandbox").id();
        JsonNode view = json(mvc(restarted).perform(get("/api/config/environments/" + id + "/settings")).andReturn().getResponse());
        assertThat(view.get("kind").stringValue()).isEqualTo("sandbox");
        assertThat(view.get("active").booleanValue()).isFalse();
        assertThat(view.get("profile").get("words").stringValue()).isEqualTo("Mock, chosen when PAVE starts");
        assertThat(view.get("export").get("fileName").stringValue()).isEqualTo("sandbox.json");
        assertThat(view.get("signIn").get("method").stringValue()).isEqualTo("CLIENT_CREDENTIALS");
        assertThat(view.get("signIn").get("clientSecret").get("reference").stringValue()).isEqualTo("{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(view.get("signIn").get("bearerToken").get("set").booleanValue()).isFalse();
        assertThat(view.get("signIn").get("inForceWords").stringValue()).isEqualTo("Client credentials, this environment");
        JsonNode timeouts = view.get("timeouts");
        assertThat(timeouts.get("readMs").intValue()).isEqualTo(60000);
        assertThat(timeouts.get("inForce").get("readFrom").stringValue()).isEqualTo("this environment");
        assertThat(timeouts.get("defaults").get("readMs").intValue()).isEqualTo(restarted.gateway.protocolTimeouts().readMs());
        assertThat(view.get("memberIdRule").get("rule").stringValue()).isEqualTo("SPACED");
        assertThat(view.get("memberIdRule").get("from").stringValue()).isEqualTo("this environment");
        assertThat(view.get("memberIdRule").get("options")).hasSize(3);
        JsonNode shape = view.get("shapes").get(0);
        assertThat(shape.get("key").stringValue()).isEqualTo("CRD_DRAFT_ORDERS_TYPE");
        assertThat(shape.get("value").stringValue()).isEqualTo("LEFT_OUT");
        assertThat(shape.get("from").stringValue()).isEqualTo("this environment");
        assertThat(shape.get("why").stringValue()).isEqualTo(RequestShape.CRD_DRAFT_ORDERS_TYPE.why());

        List<String> names = new ArrayList<>();
        view.get("values").forEach(value -> names.add(value.get("name").stringValue()));
        // The known values first, in their order, then a name typed here.
        assertThat(names).startsWith("fhirServer", "payerName").endsWith("callbackUrl", "pasLocalCode");
        JsonNode payer = view.get("values").get(1);
        assertThat(payer.get("label").stringValue()).isEqualTo("Payer name");
        assertThat(payer.get("inForce").get("source").stringValue()).isEqualTo("THIS_ENVIRONMENT");
        assertThat(payer.get("inForce").get("sourceLabel").stringValue()).isEqualTo("typed here");
        JsonNode callback = view.get("values").get(names.indexOf("callbackUrl"));
        // Blank in the export and absent from application.yml: not set, and nothing is put in its place.
        assertThat(callback.get("inForce").get("source").stringValue()).isEqualTo("NOT_SET");
        assertThat(callback.get("inForce").has("display")).isFalse();

        JsonNode submit = address(view, "PAS_SUBMIT");
        assertThat(submit.get("typed").stringValue()).isEqualTo("https://vendor.example.invalid/fhir/Claim/$submit");
        assertThat(submit.get("shortLabel").stringValue()).isEqualTo("PAS submit");
        assertThat(submit.get("inForce").get("source").stringValue()).isEqualTo("THIS_ENVIRONMENT");
        JsonNode subscription = address(view, "SUBSCRIPTION");
        // The subscription base, with what create appends, resolved against this environment as if it were active.
        assertThat(subscription.get("inForce").get("display").stringValue()).isEqualTo("https://sandbox.onyx.example.invalid/fhir/notify/Subscription");
        assertThat(address(view, "CRD_ORDER_SIGN").get("inForce").get("source").stringValue()).isEqualTo("NOT_SET");
        assertThat(view.get("addresses")).hasSize(8);
    }

    private static JsonNode address(JsonNode view, String key) {
        for (JsonNode each : view.get("addresses")) {
            if (key.equals(each.get("operation").stringValue())) {
                return each;
            }
        }
        throw new AssertionError("no address row " + key);
    }

    @Test
    void whatASaveLeavesOutIsKeptAndEmptyTextOrNullClearsIt() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        save(mvc(fixture), everySetting());
        String id = fixture.store.snapshot().byName("Onyx Sandbox").id();

        ObjectNode change = body();
        change.put("id", id);
        change.putObject("addresses").put("PAS_SUBMIT", "");
        change.putObject("values").put("payerName", "");
        change.putObject("timeouts").putNull("readMs");
        change.put("memberIdRule", "");
        MockHttpServletResponse saved = save(mvc(fixture), change);
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);

        EnvironmentSettings settings = fixture.store.snapshot().byName("Onyx Sandbox").settings();
        assertThat(settings.addresses()).containsOnlyKeys(EpaOperation.SUBSCRIPTION_CREATE);
        assertThat(settings.values()).containsOnlyKeys("pasLocalCode");
        assertThat(settings.timeouts().readMs()).isNull();
        assertThat(settings.timeouts().connectMs()).isEqualTo(3000);
        assertThat(settings.memberIdRule()).isNull();
        // Not in the save, so kept.
        assertThat(settings.signIn().clientSecret()).isEqualTo("{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(settings.shapes()).containsKey(RequestShape.CRD_DRAFT_ORDERS_TYPE);
    }

    @Test
    void anEnvironmentMadeOnScreenNeedsNoExportAndANameIsNeverTakenTwiceOrChanged() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        ObjectNode typed = body();
        typed.put("name", "Payer UAT");
        typed.put("kind", "uat");
        assertThat(save(mvc(fixture), typed).getStatus()).isEqualTo(200);
        StoredEnvironment made = fixture.store.snapshot().byName("Payer UAT");
        assertThat(made.content().variables()).isEmpty();
        assertThat(directory.resolve(made.file())).exists();
        // Read back at the next start like any uploaded environment.
        assertThat(ConfigFixture.mock(directory).store.snapshot().byName("Payer UAT").kind()).isEqualTo(EnvironmentKind.UAT);

        JsonNode taken = json(save(mvc(fixture), typed));
        assertThat(taken.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("name");

        ObjectNode rename = body();
        rename.put("id", made.id());
        rename.put("name", "Payer UAT 2");
        MockHttpServletResponse refused = save(mvc(fixture), rename);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(json(refused).get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("name");

        ObjectNode unknown = body();
        unknown.put("id", "no-such-environment");
        assertThat(save(mvc(fixture), unknown).getStatus()).isEqualTo(404);
        assertThat(mvc(fixture).perform(get("/api/config/environments/no-such-environment/settings")).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }

    @Test
    void eachFieldIsRefusedAtItsNameAndNothingIsStored() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        ObjectNode body = everySetting();
        ((ObjectNode) body.get("signIn")).put("method", "PASSWORD");
        ((ObjectNode) body.get("timeouts")).put("readMs", 600_001);
        ((ObjectNode) body.get("timeouts")).put("connectMs", 99);
        ((ObjectNode) body.get("addresses")).put("PAS_INQUIRE", "vendor.example.invalid/inquire");
        ((ObjectNode) body.get("addresses")).put("CRD_ORDER_SIGN", "https://user:pw@vendor.example.invalid/cds");
        ((ObjectNode) body.get("addresses")).put("LOOKUP", "https://vendor.example.invalid/lookup");
        body.put("memberIdRule", "CARD");
        ((ObjectNode) body.get("shapes")).put("CRD_DRAFT_ORDERS_TYPE", "MAYBE");
        ((ObjectNode) body.get("values")).put("clientSecret", "x");
        ((ObjectNode) body.get("values")).put("payer name", "x");
        ((ObjectNode) body.get("values")).put("pasNote", "y".repeat(2001));

        MockHttpServletResponse refused = save(mvc(fixture), body);
        assertThat(refused.getStatus()).isEqualTo(422);
        List<String> fields = new ArrayList<>();
        json(refused).get("fieldErrors").forEach(error -> fields.add(error.get("field").stringValue()));
        assertThat(fields).containsExactlyInAnyOrder("signIn.method", "timeouts.connectMs", "timeouts.readMs", "addresses.PAS_INQUIRE",
                "addresses.CRD_ORDER_SIGN", "addresses.LOOKUP", "memberIdRule", "shapes.CRD_DRAFT_ORDERS_TYPE", "values.clientSecret",
                "values.payer name", "values.pasNote");
        assertThat(refused.getContentAsString()).contains("This looks like a secret. Put it in Sign-in, or keep it in the Postman "
                + "environment, where it is masked.");
        assertThat(fixture.store.snapshot().environments()).isEmpty();
        assertThat(directory.resolve(ConfigStore.STATE_FILE)).doesNotExist();

        // A property the form does not have is refused by name, never dropped.
        ObjectNode misspelt = body();
        misspelt.put("kind", "sandbox");
        misspelt.put("name", "Typed");
        misspelt.put("acknowledgeNotProduction", true);
        MockHttpServletResponse strict = save(mvc(fixture), misspelt);
        assertThat(strict.getStatus()).isEqualTo(422);
        assertThat(json(strict).get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("acknowledgeNotProduction");

        ObjectNode broken = body();
        broken.put("kind", "sandbox");
        broken.putObject("environmentExport").put("fileName", "env.json").put("text", "{ not json");
        MockHttpServletResponse unreadable = save(mvc(fixture), broken);
        assertThat(unreadable.getStatus()).isEqualTo(422);
        assertThat(json(unreadable).get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("environmentExport");
    }

    /** A version 2 store whose one settings entry is changed by hand, as an editor or a newer build would leave it. */
    private ConfigFixture storeWith(Consumer<ObjectNode> breakSettings) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        save(mvc(fixture), everySetting());
        fixture.store.activate("Onyx Sandbox");
        Path file = directory.resolve(ConfigStore.STATE_FILE);
        ObjectNode root = (ObjectNode) JsonSafe.parse(Files.readString(file)).node();
        breakSettings.accept((ObjectNode) root.get("environments").get(0).get("settings"));
        Files.writeString(file, JsonSafe.pretty(root));
        return ConfigFixture.mock(directory);
    }

    @Test
    void anEntryThisToolCannotReadIsListedAndNeverUsedAndNeverStopsTheStart() throws Exception {
        List<Consumer<ObjectNode>> breaks = List.of(
                settings -> ((ObjectNode) settings.get("signIn")).put("method", "MUTUAL_TLS"),
                settings -> ((ObjectNode) settings.get("addresses")).put("CRD_ORDER_DISPATCH", "https://vendor.example.invalid"),
                settings -> settings.put("memberIdRule", "SHORT"),
                settings -> ((ObjectNode) settings.get("shapes")).put("PAS_TIMESTAMP", "LEFT_OUT"),
                settings -> ((ObjectNode) settings.get("values")).put("api key", "S123"));
        for (Consumer<ObjectNode> each : breaks) {
            deleteStore();
            ConfigFixture restarted = storeWith(each);
            StoredEnvironment sandbox = restarted.store.snapshot().byName("Onyx Sandbox");
            assertThat(sandbox.settingsProblem()).isNotNull();
            assertThat(sandbox.settings().empty()).isTrue();
            assertThat(restarted.store.problems()).anySatisfy(problem -> assertThat(problem)
                    .startsWith("The settings of the environment \"Onyx Sandbox\" in ").contains("cannot be read: ")
                    .endsWith("nothing is sent under that environment until they are saved again in Configuration > Environments."));
            JsonNode view = json(mvc(restarted).perform(get("/api/config/environments/" + sandbox.id() + "/settings")).andReturn()
                    .getResponse());
            assertThat(view.get("problem").stringValue()).isEqualTo(sandbox.settingsProblem());
            // Not guessed: nothing is sent as if the environment stated nothing.
            assertThatThrownBy(() -> restarted.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY)))
                    .isInstanceOf(BadInputException.class).hasMessageContaining("cannot be read");

            // Another change keeps the entry exactly as it was stored, so nothing typed is lost.
            JsonNode storedBefore = stored();
            restarted.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThat(stored()).isEqualTo(storedBefore);

            // Saved again from the form, the settings are read and used.
            ObjectNode again = body();
            again.put("id", sandbox.id());
            again.put("memberIdRule", "SPLIT");
            assertThat(save(mvc(restarted), again).getStatus()).isEqualTo(200);
            StoredEnvironment repaired = restarted.store.snapshot().byName("Onyx Sandbox");
            assertThat(repaired.settingsProblem()).isNull();
            assertThat(repaired.settings().memberIdRule()).isEqualTo(MemberIdRule.SPLIT);
        }
    }

    private JsonNode stored() throws Exception {
        return JsonSafe.parse(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).node().get("environments").get(0).get("settings");
    }

    private void deleteStore() throws Exception {
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).filter(path -> !path.equals(directory)).forEach(path -> path.toFile().delete());
        }
    }
}
