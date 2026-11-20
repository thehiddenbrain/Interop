package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The application as it starts on the live profile with no code list configured, sending a CRD
 * request to a service that answers.
 *
 * <p>The tests of the conformance checker build it by hand and tell it which profile it is on. This
 * is what shows that the started application gives it the profile it really runs on and the list
 * it really loaded: on live, with {@code codelist.file} blank, a result must never carry a finding
 * that the code list and the service disagree.
 *
 * <p>The service is a socket on the loopback interface that gives every request the same answer.
 * Nothing leaves the machine.
 */
@SpringBootTest
@ActiveProfiles("live")
class PlaceholderOnTheLiveProfileTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    /**
     * No authorization needed, for each of two orders, in the shape CRD 2.0.1 requires (coverage,
     * covered, date and coverage-assertion-id once each), so that the only findings left are about
     * the code list. The placeholder list says both codes need one for Medicare.
     */
    private static final String NO_AUTH_FOR_BOTH = "{\"cards\":[],\"systemActions\":[" + noAuthFor(1) + "," + noAuthFor(2) + "]}";

    private static final AtomicInteger RECEIVED = new AtomicInteger();

    @TempDir
    static Path home;

    private static HttpServer service;

    private static String noAuthFor(int order) {
        return "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-" + order + "\","
                + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":["
                + "{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/coverage-1\"}},"
                + "{\"url\":\"covered\",\"valueCode\":\"covered\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"},"
                + "{\"url\":\"date\",\"valueDate\":\"2026-03-20\"},"
                + "{\"url\":\"coverage-assertion-id\",\"valueString\":\"assertion-" + order + "\"}]}]}}";
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) throws IOException {
        service = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        service.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            RECEIVED.incrementAndGet();
            byte[] body = NO_AUTH_FOR_BOTH.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        service.start();
        // The fallback layer of application.yml, which is where a url comes from when no environment has been uploaded.
        registry.add("onyx.crd.url", () -> "http://127.0.0.1:" + service.getAddress().getPort());
        registry.add("onyx.variables[crdOrderSignServiceId]", () -> "placeholder-service");
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @AfterAll
    static void stop() {
        service.stop(0);
    }

    @Autowired WebApplicationContext context;
    @Autowired EpaProperties epa;
    @Autowired ProcedureCodeDirectory codeList;

    private JsonNode json(RequestBuilder request) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        return JsonSafe.parse(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).node();
    }

    @Test
    void aRunOnLiveWithNoCodeListConfiguredSaysOnceThatNothingWasComparedAndNeverReportsADisagreement() throws Exception {
        assertThat(epa.live()).isTrue();
        assertThat(codeList.selfCheck().placeholder()).isTrue();
        // The placeholder states a position on both codes, so there would be something to disagree with.
        assertThat(codeList.rowsFor("95810", "Medicare", null)).isNotEmpty();
        assertThat(codeList.rowsFor("E0470", "Medicare", null)).isNotEmpty();

        int before = RECEIVED.get();
        JsonNode result = json(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content("{\"member\":{\"memberId\":\"S123456789\","
                + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\"},{\"code\":\"E0470\"}],\"dateOfService\":\"2026-03-20\"}"));

        // The request really went out through the live client and the answer really came back.
        assertThat(RECEIVED.get()).isEqualTo(before + 1);
        assertThat(result.get("profile").stringValue()).isEqualTo("live");
        assertThat(result.get("wire").get("request").get("sent").booleanValue()).isTrue();
        assertThat(result.get("wire").get("request").get("url").stringValue()).endsWith("/cds-services/placeholder-service");
        assertThat(result.get("verdict").get("outcomeCode").stringValue()).isEqualTo("NO_AUTH_REQUIRED");

        List<JsonNode> aboutTheCodeList = new ArrayList<>();
        result.get("findings").forEach(finding -> {
            if (finding.get("kind").stringValue().startsWith("CODE_LIST_")) {
                aboutTheCodeList.add(finding);
            }
        });
        // One for the run. Not one for each code, and not a disagreement.
        assertThat(aboutTheCodeList).hasSize(1);
        JsonNode finding = aboutTheCodeList.get(0);
        assertThat(finding.get("kind").stringValue()).isEqualTo("CODE_LIST_NOT_COMPARED");
        assertThat(finding.get("title").stringValue()).isEqualTo("The code list was not compared with the service: no P32H code list is loaded");
        assertThat(finding.get("detail").stringValue()).isEqualTo("No P32H code list is loaded. The tool is using the synthetic placeholder "
                + "list that ships with it, which says nothing about real codes, so nothing was compared with the service's answer. Set "
                + "codelist.file to the P32H export and restart the tool.");
        // As the browser gets it: no code is named and neither side is given a position.
        assertThat(finding.propertyNames()).containsExactlyInAnyOrder("kind", "title", "detail", "group", "groupLabel");

        // The ledger line of that run says the same, so history and a suite count cannot say otherwise.
        JsonNode line = json(get("/api/runs/" + result.get("runId").stringValue()));
        // The member ID alone was sent, so the request also went without what CRD 2.0.1 requires of a Patient, and says so.
        assertThat(JsonSafe.compact(line.get("findings"))).isEqualTo("[\"CODE_LIST_NOT_COMPARED\",\"REQUEST_INCOMPLETE\"]");
    }

    @Test
    void thePickerOnLiveSaysItsCodesAreThePlaceholders() throws Exception {
        JsonNode answer = json(get("/api/procedure-codes").param("q", "95810").param("lob", "Medicare"));
        assertThat(answer.get("placeholder").booleanValue()).isTrue();
        // The row is still offered with what it states. The flag is what the screen has to show beside it.
        assertThat(answer.get("codes").get(0).get("authRequired").stringValue()).isEqualTo("REQUIRED");
    }
}
