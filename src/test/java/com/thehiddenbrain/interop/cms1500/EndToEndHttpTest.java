package com.thehiddenbrain.interop.cms1500;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.cms1500.api.soap.ClaimSoapEndpoint;
import com.thehiddenbrain.interop.cms1500.contract.GenerateClaimBundleRequest;
import com.thehiddenbrain.interop.cms1500.contract.ObjectFactory;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import com.thehiddenbrain.interop.cms1500.support.TestFiles;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.StringWriter;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP against a running server: WSDL, SOAP envelope, REST JSON, download, health, OpenAPI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndHttpTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-e2e");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void attachments() throws Exception {
        TestFiles.pdf(DIRS.attachments.resolve("E2E-REST_1.pdf"), 2, "EOB");
        TestFiles.multiPageTiff(DIRS.attachments.resolve("E2E-REST_2.tiff"), 2);
        TestFiles.pdf(DIRS.attachments.resolve("E2E-SOAP_1.pdf"), 1, "Referral");
    }

    @Test
    void wsdlIsPublished() {
        ResponseEntity<String> wsdl = http.getForEntity("/ws/cms1500.wsdl", String.class);
        assertThat(wsdl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wsdl.getBody()).contains("Cms1500ClaimPort").contains("generateClaimBundle")
                .contains("generateClaimBundleFault").contains(ClaimSoapEndpoint.NAMESPACE).contains("/ws");
    }

    @Test
    void restRoundTrip() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = mapper.writeValueAsString(ClaimFixtures.withServiceLines(ClaimFixtures.fullClaim("E2E-REST"), 7));
        ResponseEntity<String> response = http.postForEntity("/api/v1/claims/cms1500", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.get("status").asText()).isEqualTo("GENERATED");
        assertThat(result.get("formPages").asInt()).isEqualTo(2);
        assertThat(result.get("totalPages").asInt()).isEqualTo(2 + 2 + 2);
        assertThat(Files.exists(DIRS.output.resolve("E2E-REST.pdf"))).isTrue();

        ResponseEntity<byte[]> download = http.getForEntity("/api/v1/claims/E2E-REST/bundle", byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(download.getBody()).isEqualTo(Files.readAllBytes(DIRS.output.resolve("E2E-REST.pdf")));
        try (PDDocument doc = Loader.loadPDF(download.getBody())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(6);
        }
    }

    @Test
    void soapRoundTrip() throws Exception {
        GenerateClaimBundleRequest request = new GenerateClaimBundleRequest();
        request.setClaim(ClaimFixtures.fullClaim("E2E-SOAP"));
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
        StringWriter payload = new StringWriter();
        marshaller.marshal(request, payload);
        String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Header/><soapenv:Body>" + payload + "</soapenv:Body></soapenv:Envelope>";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        headers.set("SOAPAction", "");
        ResponseEntity<String> response = http.postForEntity("/ws", new HttpEntity<>(envelope, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("generateClaimBundleResponse").contains(">GENERATED<")
                .contains(">E2E-SOAP.pdf<").contains(">2<");
        assertThat(Files.exists(DIRS.output.resolve("E2E-SOAP.pdf"))).isTrue();

        // second call with the same claim replaces the bundle
        ResponseEntity<String> again = http.postForEntity("/ws", new HttpEntity<>(envelope, headers), String.class);
        assertThat(again.getBody()).containsPattern("<[A-Za-z0-9]+:replacedExisting>true<").doesNotContain("Fault");

        // a claim that fails validation comes back as a SOAP fault with HTTP 500 (SOAP 1.1 convention)
        String broken = envelope.replace(">" + ClaimFixtures.NPI_BILLING + "<", ">" + ClaimFixtures.NPI_INVALID_CHECK + "<");
        assertThat(broken).isNotEqualTo(envelope);
        ResponseEntity<String> fault = http.postForEntity("/ws", new HttpEntity<>(broken, headers), String.class);
        assertThat(fault.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fault.getBody()).contains("Fault").contains("VALIDATION_ERROR").contains("billingProvider.npi");
    }

    @Test
    void testUiIsServedAtItsRoot() {
        ResponseEntity<String> ui = http.getForEntity("/ui/", String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getHeaders().getContentType()).isNotNull();
        assertThat(ui.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(ui.getBody()).contains("CMS-1500 Test Console").contains("app.js");
        ResponseEntity<String> settings = http.getForEntity("/api/v1/settings", String.class);
        assertThat(settings.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settings.getBody()).contains("\"overridden\":false");
    }

    @Test
    void healthAndOpenApiAreAvailable() {
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        ResponseEntity<String> docs = http.getForEntity("/api-docs", String.class);
        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody()).contains("/api/v1/claims/cms1500").contains("/api/v1/claims/{claimNumber}/bundle");
    }
}
