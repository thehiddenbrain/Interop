package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.pas.NotificationReceiver;
import org.p32h.interop.epa.pave.service.pas.PasAttachment;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.pas.SubscriptionService;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The fields Submit gained for Da Vinci PAS 2.2.1, read as the running application reads them: each
 * binds under its documented name, and each misspelt is a 422 that names it, at the top of the body
 * and inside a code, an attachment and the servicing provider. Also the preview's {@code toComplete}
 * on the wire and the redaction of an attachment that carries a file.
 */
class PasNewFieldsReadingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));
    private static final String BASE = "\"member\":{\"memberId\":\"P32H-PAS-APPROVED\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
            + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"dateOfService\":\"2026-03-20\"";
    private static final String CODES = "\"procedureCodes\":[{\"code\":\"95810\"}]";

    /** Every new field, spelt as documented, with a file of four bytes ("PAVE"). */
    private static final String FULL = "{" + BASE + ",\"procedureCodes\":[{\"code\":\"95810\",\"quantity\":1.50,\"unit\":\"HS\"}],"
            + "\"placeOfService\":\"11\",\"levelOfService\":\"U\",\"certificationType\":\"I\",\"requestType\":\"HS\",\"serviceType\":\"1\","
            + "\"servicingProvider\":{\"npi\":\"1234567893\"},"
            + "\"attachments\":[{\"title\":\"Report\",\"contentType\":\"application/pdf\",\"data\":\"UEFWRQ==\",\"size\":4}]}";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private MockMvc mvc() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK);
        PasService pas = new PasService(ONYX, gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support,
                ledgerProvider());
        SubscriptionService subscriptions = new SubscriptionService(ONYX, gateway, new MockSubscriptionClient(scenarios, new MockExchanges(APP)), support);
        NotificationReceiver receiver = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios);
        return TestMvc.standalone(new PasController(pas, subscriptions, receiver, scenarios, epa));
    }

    private static void refusedNaming(MockMvc mvc, String body, String field) throws Exception {
        for (String path : List.of("/api/pas/submit", "/api/pas/submit/preview")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
        }
    }

    @Test
    void everyNewFieldBindsUnderItsDocumentedName() {
        PasSubmitRequest request = TestMvc.mapper().readValue(FULL, PasSubmitRequest.class);
        assertThat(request.unrecognised()).isEmpty();
        assertThat(request.placeOfService()).isEqualTo("11");
        assertThat(request.levelOfService()).isEqualTo("U");
        assertThat(request.certificationType()).isEqualTo("I");
        assertThat(request.requestType()).isEqualTo("HS");
        assertThat(request.serviceType()).isEqualTo("1");
        assertThat(request.servicingProvider().npi()).isEqualTo("1234567893");
        CodeInput code = request.procedureCodes().get(0);
        // Read as a decimal, so 1.50 hours is not turned into a binary fraction or 1.5 on the way.
        assertThat(code.quantity()).isEqualTo(new BigDecimal("1.50"));
        assertThat(code.unit()).isEqualTo("HS");
        PasAttachment file = request.attachments().get(0);
        assertThat(file.data()).isEqualTo("UEFWRQ==");
        assertThat(file.size()).isEqualTo(4L);
        assertThat(file.text()).isNull();
        assertThat(file.url()).isNull();
    }

    @Test
    void leftOutTheNewFieldsStayNotStated() {
        PasSubmitRequest request = TestMvc.mapper().readValue("{" + BASE + "," + CODES + "}", PasSubmitRequest.class);
        assertThat(request.placeOfService()).isNull();
        assertThat(request.levelOfService()).isNull();
        assertThat(request.certificationType()).isNull();
        assertThat(request.requestType()).isNull();
        assertThat(request.serviceType()).isNull();
        assertThat(request.servicingProvider()).isNull();
        assertThat(request.procedureCodes().get(0).quantity()).isNull();
        assertThat(request.procedureCodes().get(0).unit()).isNull();
        // The builder carries them too, so the services and suites can set them without the JSON.
        PasSubmitRequest built = PasSubmitRequest.builder().placeOfService("12").levelOfService("E").certificationType("S")
                .requestType("AR").serviceType("2").build();
        assertThat(List.of(built.placeOfService(), built.levelOfService(), built.certificationType(), built.requestType(),
                built.serviceType())).containsExactly("12", "E", "S", "AR", "2");
    }

    @Test
    void aSubmissionWithEveryNewCaseFieldIsAcceptedAndPreviewed() throws Exception {
        // Without the file: a file given as data is accepted only once the request builder sends it, so it is never dropped in silence.
        String withoutFile = FULL.substring(0, FULL.indexOf(",\"attachments\"")) + "}";
        mvc().perform(post("/api/pas/submit/preview").contentType(MediaType.APPLICATION_JSON).content(withoutFile))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").value(EpaOperation.PAS_SUBMIT.name()));
    }

    @Test
    void eachNewTopLevelFieldMisspeltIsRefusedByName() throws Exception {
        MockMvc mvc = mvc();
        for (String misspelt : List.of("placeOfServise", "levelOfServices", "certificationTyp", "requestTipe", "serviceTyp",
                "servicingProvidr")) {
            refusedNaming(mvc, "{" + BASE + "," + CODES + ",\"" + misspelt + "\":\"x\"}", misspelt);
        }
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aMisspeltFieldInsideACodeAnAttachmentOrTheServicingProviderIsRefusedByName() throws Exception {
        MockMvc mvc = mvc();
        refusedNaming(mvc, "{" + BASE + ",\"procedureCodes\":[{\"code\":\"95810\",\"quantiy\":2}]}", "quantiy");
        refusedNaming(mvc, "{" + BASE + ",\"procedureCodes\":[{\"code\":\"95810\",\"unti\":\"DY\"}]}", "unti");
        refusedNaming(mvc, "{" + BASE + "," + CODES + ",\"attachments\":[{\"title\":\"Report\",\"datta\":\"UEFWRQ==\"}]}", "datta");
        refusedNaming(mvc, "{" + BASE + "," + CODES + ",\"attachments\":[{\"title\":\"Report\",\"data\":\"UEFWRQ==\",\"sise\":4}]}", "sise");
        refusedNaming(mvc, "{" + BASE + "," + CODES + ",\"servicingProvider\":{\"npii\":\"1234567893\"}}", "npii");
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aQuantityOrASizeThatIsNotANumberIsRefusedAtItsPlace() throws Exception {
        MockMvc mvc = mvc();
        refusedNaming(mvc, "{" + BASE + ",\"procedureCodes\":[{\"code\":\"95810\",\"quantity\":\"two\"}]}", "procedureCodes[0].quantity");
        refusedNaming(mvc, "{" + BASE + "," + CODES + ",\"attachments\":[{\"title\":\"Report\",\"data\":\"UEFWRQ==\",\"size\":\"four\"}]}",
                "attachments[0].size");
    }

    @Test
    void toCompleteIsLeftOutWhenNotCheckedAndAnEmptyListWhenNothingIsMissing() {
        RequestPreview unchecked = new RequestPreview(EpaOperation.PAS_INQUIRE, "POST", null, null, null, false, "{}", List.of(), null, null);
        JsonNode plain = JsonSafe.parse(TestMvc.mapper().writeValueAsString(unchecked)).node();
        assertThat(plain.has("toComplete")).isFalse();

        JsonNode complete = JsonSafe.parse(TestMvc.mapper().writeValueAsString(unchecked.withToComplete(List.of()))).node();
        assertThat(complete.get("toComplete").isArray()).isTrue();
        assertThat(complete.get("toComplete").size()).isZero();

        RequestPreview gaps = unchecked.withToComplete(List.of(new RequestPreview.ToComplete("values.payerName",
                "Insurer: none. PAS 2.2.1 requires the payer: add Payer name and Payer identifier under Values in Configuration > Environments.")));
        JsonNode listed = JsonSafe.parse(TestMvc.mapper().writeValueAsString(gaps.withBodySource("supplied"))).node();
        // Kept through the other copy method, so a later step cannot lose it.
        assertThat(listed.at("/toComplete/0/field").asString()).isEqualTo("values.payerName");
        assertThat(listed.at("/toComplete/0/line").asString()).startsWith("Insurer: none.");
        assertThat(listed.get("bodySource").asString()).isEqualTo("supplied");
    }

    @Test
    void anAttachmentWithAFileRedactsItsContentAndTheRequestStaysRedacted() {
        PasAttachment file = PasAttachment.file("Report", "application/pdf", "UEFWRQ==", 4L);
        assertThat(file.toString()).doesNotContain("UEFWRQ==").doesNotContain("Report").contains("size=4");
        PasSubmitRequest request = TestMvc.mapper().readValue(FULL, PasSubmitRequest.class);
        assertThat(request.toString()).doesNotContain("UEFWRQ==").doesNotContain("Placeholder-Surname").doesNotContain("P32H-PAS-APPROVED");
    }
}
