package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * I-28 and I-29: a token url is a destination too, and an address is compared with production as
 * a server reads it, every percent-escape of its path decoded once. Every spelling of the
 * production address that was refused before stays refused.
 */
class TokenUrlAndEscapeGuardrailTest {

    private static final String HOST = "http://127.0.0.1:18950";
    private static final String SUBMIT = HOST + "/fhir/Claim/$submit";

    @TempDir
    Path directory;

    /** The spellings of the production submit address the look refused before (21 of them), each with the values it needs. */
    private static final List<String[]> REFUSED_BEFORE = List.of(
            new String[] {SUBMIT},
            new String[] {"HTTP://127.0.0.1:18950/fhir/Claim/$submit"},
            new String[] {"http://127.0.0.1.:18950/fhir/Claim/$submit"},
            new String[] {"http://localhost:18950/fhir/Claim/$submit"},
            new String[] {"http://LocalHost:18950/fhir/Claim/$submit"},
            new String[] {"http://127.1:18950/fhir/Claim/$submit"},
            new String[] {"http://[::ffff:127.0.0.1]:18950/fhir/Claim/$submit"},
            new String[] {"http://2130706433:18950/fhir/Claim/$submit"},
            new String[] {"http://0x7f000001:18950/fhir/Claim/$submit"},
            new String[] {"http://127.0.0.1:018950/fhir/Claim/$submit"},
            new String[] {"http://tester@127.0.0.1:18950/fhir/Claim/$submit"},
            new String[] {HOST + "/%66hir/Claim/$submit"},
            new String[] {HOST + "/fhir/x/../Claim/$submit"},
            new String[] {HOST + "//fhir/Claim/$submit"},
            new String[] {HOST + "/fhir/Claim/$submit/"},
            new String[] {HOST + "/fhir/Claim/$submit?x=1"},
            new String[] {HOST + "/fhir/Claim/$submit;x=1"},
            new String[] {HOST + "/FHIR/Claim/$submit"},
            new String[] {HOST + "/fhir/Claim/$%73ubmit"},
            new String[] {"{{base}}/Claim/$submit", "base", HOST + "/fhir"},
            new String[] {"http://{{h}}/fhir/Claim/$submit", "h", "127.0.0.1:18950"});

    /** The escaped dollar sign of I-29, in its mixed-case forms. */
    private static final List<String> ESCAPED_DOLLAR = List.of(HOST + "/fhir/Claim/%24submit", HOST + "/fhir/Claim/%24Submit",
            HOST + "/fhir/Claim/%24SUBMIT", HOST + "/FHIR/claim/%24submit", HOST + "/fhir/Claim/%24%73ubmit");

    private static MockHttpServletResponse save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse();
    }

    private static ObjectNode sandbox(String name) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("name", name);
        body.put("kind", "sandbox");
        body.put("acknowledgeNotProd", true);
        return body;
    }

    private static ObjectNode clientCredentials(ObjectNode body, String tokenUrl) {
        ObjectNode signIn = body.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", tokenUrl);
        signIn.put("clientId", "placeholder-client");
        signIn.put("clientSecret", "placeholder-client-secret");
        signIn.put("clientAuthentication", "BODY");
        return body;
    }

    private static String field(MockHttpServletResponse response, String name) throws Exception {
        JsonNode errors = JsonSafe.parse(response.getContentAsString()).node().get("fieldErrors");
        for (JsonNode error : errors == null ? List.<JsonNode>of() : errors) {
            if (name.equals(error.get("field").stringValue())) {
                return error.get("message").stringValue();
            }
        }
        return null;
    }

    private static String title(MockHttpServletResponse response) throws Exception {
        return JsonSafe.parse(response.getContentAsString()).node().get("title").stringValue();
    }

    /** A production environment "vendor-live" whose typed PAS submit address is the one production destination. */
    private static void production(MockMvc mvc) throws Exception {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        body.put("name", "vendor-live");
        body.put("kind", "prod");
        body.putObject("addresses").put("PAS_SUBMIT", SUBMIT);
        assertThat(save(mvc, body).getStatus()).isEqualTo(200);
    }

    @Test
    void anEscapedDollarIsTheProductionSubmitAndEverySpellingRefusedBeforeStaysRefused() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        production(mvc);
        List<String> stored = new ArrayList<>();
        int n = 0;
        List<String[]> all = new ArrayList<>(REFUSED_BEFORE);
        ESCAPED_DOLLAR.forEach(each -> all.add(new String[] {each}));
        for (String[] spelling : all) {
            ObjectNode body = sandbox("sbx-" + (++n));
            body.putObject("addresses").put("PAS_SUBMIT", spelling[0]);
            if (spelling.length > 1) {
                body.putObject("values").put(spelling[1], spelling[2]);
            }
            MockHttpServletResponse answer = save(mvc, body);
            if (answer.getStatus() != 422) {
                stored.add(spelling[0]);
            }
        }
        assertThat(stored).isEmpty();
        assertThat(fixture.store.snapshot().environments()).hasSize(1);

        ObjectNode escaped = sandbox("sbx-pct-dollar");
        escaped.putObject("addresses").put("PAS_SUBMIT", HOST + "/fhir/Claim/%24submit");
        assertThat(title(save(mvc, escaped))).isEqualTo("This environment was declared as sandbox, and its address "
                + HOST + "/fhir/Claim/%24submit is an address of the production environment \"vendor-live\", or lies above or beneath one. "
                + "It was not stored.");

        // The comparison form itself, in both directions.
        UrlNormal.Destination production = UrlNormal.destination(SUBMIT);
        for (String each : ESCAPED_DOLLAR) {
            assertThat(production.covers(UrlNormal.destination(each))).as(each).isTrue();
            assertThat(UrlNormal.destination(each).covers(production)).as(each).isTrue();
        }
        // Another base on the same server is still not production.
        ObjectNode other = sandbox("sbx-other-base");
        other.putObject("addresses").put("PAS_SUBMIT", HOST + "/other/Claim/%24submit");
        assertThat(save(mvc, other).getStatus()).isEqualTo(200);
    }

    @Test
    void anOlderStoreHoldingTheEscapedDollarIsBlockedWhenItWouldSend() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        production(mvc);
        ObjectNode body = sandbox("sbx-older");
        body.putObject("addresses").put("PAS_INQUIRE", HOST + "/sbx/Claim/$inquire");
        assertThat(save(mvc, body).getStatus()).isEqualTo(200);
        // Written underneath, as an older store would hold it.
        StoredEnvironment held = fixture.store.snapshot().byName("sbx-older");
        StoredEnvironment older = held.withSettings(new EnvironmentSettings(null, null,
                Map.of(EpaOperation.PAS_SUBMIT, HOST + "/fhir/Claim/%24submit"), null, null, null));
        ConfigSnapshot snapshot = new ConfigSnapshot(fixture.store.epoch(), List.of(fixture.store.snapshot().byName("vendor-live"), older),
                older.name(), List.of(), fixture.store.snapshot().remembered());
        CallPlan plan = fixture.gateway.describe(snapshot, CallIntent.of(EpaOperation.PAS_SUBMIT));
        assertThat(plan.refusal()).isNotNull();
        assertThat(plan.refusal().reason()).contains("the address it would go to belongs to the production environment \"vendor-live\"");
    }

    @Test
    void anEscapeOfAnEscapeIsRefusedAsAnAddressAtSaveAndWhenItWouldSend() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        int n = 0;
        for (String address : List.of(HOST + "/fhir/Claim/%2524submit", HOST + "/fhir/Claim/%2524Submit", HOST + "/fhir/%25zz/x",
                HOST + "/fhir/a%zz", HOST + "/fhir/a%2")) {
            ObjectNode body = sandbox("sbx-esc-" + (++n));
            body.putObject("addresses").put("PAS_SUBMIT", address);
            MockHttpServletResponse answer = save(mvc, body);
            assertThat(answer.getStatus()).as(address).isEqualTo(422);
            assertThat(field(answer, "addresses.PAS_SUBMIT")).as(address).isEqualTo("An address holds no percent-escape of an escape.");
        }
        ObjectNode token = clientCredentials(sandbox("sbx-esc-token"), HOST + "/oauth/%252e%252e/token");
        assertThat(field(save(mvc, token), "signIn.tokenUrl")).isEqualTo("An address holds no percent-escape of an escape.");
        // A query keeps whatever escapes it carries.
        ObjectNode query = sandbox("sbx-esc-query");
        query.putObject("addresses").put("PAS_INQUIRE", HOST + "/sbx/Claim/$inquire?note=50%2525");
        assertThat(save(mvc, query).getStatus()).isEqualTo(200);

        // An export variable holding one is refused when it would be sent, naming the variable.
        fixture.activeEnvironmentOf("Escaped Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "http://127.0.0.1:18745/sb%2524x");
        fixture.collectionOf("Escaped", ConfigFixture.items(ConfigFixture.item("submit escaped", "POST", "{{baseUrl}}/Claim/$submit")));
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)))
                .isInstanceOf(BadInputException.class)
                .hasMessage("PAS submit was not sent: the path of its url holds a percent-escape of an escape (%25), which came from the "
                        + "variable \"baseUrl\" (the active environment). An address holds no percent-escape of an escape. A server that decodes "
                        + "the path twice would read another address than the one this tool judged.");
        assertThat(UrlNormal.escapeOfEscape(HOST + "/fhir/Claim/%24submit")).isFalse();
        assertThat(UrlNormal.escapeOfEscape(HOST + "/fhir/Claim/$submit?x=%2525")).isFalse();
    }

    @Test
    void aSandboxTokenUrlAtAProductionCallAddressIsRefusedAtItsFieldInEverySpelling() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        production(mvc);
        int n = 0;
        List<String> spellings = List.of(SUBMIT, "HTTP://127.0.0.1:18950/fhir/Claim/$submit", "http://127.0.0.1.:18950/fhir/Claim/$submit",
                "http://localhost:18950/fhir/Claim/$submit", "http://[::ffff:127.0.0.1]:18950/fhir/Claim/$submit",
                "http://2130706433:18950/fhir/Claim/$submit", "http://127.0.0.1:018950/fhir/Claim/$submit",
                "http://tester@127.0.0.1:18950/fhir/Claim/$submit", HOST + "/fhir/Claim/$submit?x=1", HOST + "/fhir/x/../Claim/$submit",
                HOST + "/fhir/Claim/%24submit", HOST + "/FHIR/Claim/$submit/", HOST + "/fhir/Claim/$submit/beneath");
        for (String spelling : spellings) {
            MockHttpServletResponse answer = save(mvc, clientCredentials(sandbox("sbx-token-" + (++n)), spelling));
            assertThat(answer.getStatus()).as(spelling).isEqualTo(422);
            assertThat(field(answer, "signIn.tokenUrl")).as(spelling).isNotNull();
        }
        MockHttpServletResponse exact = save(mvc, clientCredentials(sandbox("sbx-tokenurl"), SUBMIT));
        assertThat(title(exact)).isEqualTo("This environment was declared as sandbox, and its token url " + SUBMIT + " is an address of "
                + "the production environment \"vendor-live\", or lies beneath one. It was not stored.");
        assertThat(field(exact, "signIn.tokenUrl")).isEqualTo("A sign-in sends the client secret to the token url, so a token url at an "
                + "address a production environment uses for a call is a write to that production environment. Use the token url of the "
                + "sandbox service, or the one the production environment signs in with.");
        assertThat(fixture.store.snapshot().environments()).hasSize(1);
        // A token url above the production address, or beside it, is not refused.
        assertThat(save(mvc, clientCredentials(sandbox("sbx-token-beside"), HOST + "/token")).getStatus()).isEqualTo(200);
    }

    @Test
    void aSandboxSharingTheProductionEnvironmentsOwnTokenUrlStaysAllowed() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode body = clientCredentials(JsonSafe.mapper().createObjectNode(), HOST + "/fhir/oauth/token");
        body.put("name", "vendor-live");
        body.put("kind", "prod");
        body.putObject("addresses").put("PAS_SUBMIT", HOST + "/fhir");
        assertThat(save(mvc, body).getStatus()).isEqualTo(200);

        assertThat(save(mvc, clientCredentials(sandbox("sbx-shared"), "http://LOCALHOST:18950/fhir/oauth/token")).getStatus())
                .isEqualTo(200);
        MockHttpServletResponse beneath = save(mvc, clientCredentials(sandbox("sbx-beneath"), HOST + "/fhir/Claim"));
        assertThat(beneath.getStatus()).isEqualTo(422);
        assertThat(field(beneath, "signIn.tokenUrl")).isNotNull();

        // At sending, the same rule: the shared one passes, one beneath a production address does not.
        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/fhir/oauth/token")).isNull();
        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/fhir/Claim/$submit")).isEqualTo("Nothing was sent: the token url, at "
                + HOST + ", is an address of the production environment \"vendor-live\", or lies beneath one, and a sign-in sends the "
                + "client secret to it. A token url is never an address a production environment uses for a call. Set the token url "
                + "under Sign-in in Configuration > Environments.");
        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/token")).isNull();
    }

    /**
     * features-8: a production environment that shared the sandbox's token url, removed, takes its
     * token url with it, while the addresses it called stay remembered. The refusal then keeps the
     * guardrail's words and says what changed and the ways out, instead of only asking for another
     * token url when the one typed is right.
     */
    @Test
    void aTokenUrlRefusedAfterItsProductionEnvironmentWasRemovedSaysSoAndGivesTheWaysOut() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode body = clientCredentials(JsonSafe.mapper().createObjectNode(), HOST + "/token");
        body.put("name", "vendor-live");
        body.put("kind", "prod");
        body.putObject("addresses").put("PAS_SUBMIT", HOST);
        assertThat(save(mvc, body).getStatus()).isEqualTo(200);
        String words = "Nothing was sent: the token url, at " + HOST + ", is an address of the production environment \"vendor-live\", "
                + "or lies beneath one, and a sign-in sends the client secret to it. A token url is never an address a production "
                + "environment uses for a call. Set the token url under Sign-in in Configuration > Environments.";
        // Stored, it shares its token url with the sandbox, which is allowed.
        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/token")).isNull();

        fixture.service.removeEnvironment("vendor-live");

        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/token")).isEqualTo(words + " \"vendor-live\" is no longer stored, so this "
                + "tool no longer knows the token url it signed in with. To sign in at a token url shared with it, store it again. If it "
                + "was stored as prod by mistake, press Forget this production address beside each address of \"vendor-live\" in "
                + "Configuration > Production writes, and this environment signs in again.");

        // Release 2's Forget is that way out: once the address is forgotten, the sandbox signs in again.
        fixture.service.forgetDestination(HOST, "vendor-live");
        assertThat(fixture.gateway.tokenUrlRefusal(HOST + "/token")).isNull();
    }
}
