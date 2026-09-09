package com.thehiddenbrain.interop.cms1500.api.soap;

import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.GenerateClaimBundleRequest;
import com.thehiddenbrain.interop.cms1500.contract.ObjectFactory;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import com.thehiddenbrain.interop.cms1500.support.TestFiles;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

import javax.xml.transform.Source;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.RequestCreators.withSoapEnvelope;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

@SpringBootTest
class ClaimSoapEndpointTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-soap");
    private static final Map<String, String> NS = Map.of("c", ClaimSoapEndpoint.NAMESPACE);

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
    }

    @Autowired
    ApplicationContext context;
    MockWebServiceClient client;

    @BeforeAll
    static void attachments() throws Exception {
        TestFiles.pdf(DIRS.attachments.resolve("CLM-S1_1.pdf"), 3, "Notes");
        TestFiles.image(DIRS.attachments.resolve("CLM-2026-000124_1.png"), "png", 500, 700);
    }

    @BeforeEach
    void client() {
        client = MockWebServiceClient.createClient(context);
    }

    @Test
    void generatesBundleOverSoap() throws Exception {
        client.sendRequest(withPayload(request(ClaimFixtures.fullClaim("CLM-S1"))))
                .andExpect(noFault())
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:status", NS).evaluatesTo("GENERATED"))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:claimNumber", NS).evaluatesTo("CLM-S1"))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:fileName", NS).evaluatesTo("CLM-S1.pdf"))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:totalPages", NS).evaluatesTo(4))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:attachmentCount", NS).evaluatesTo(1))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:attachments/c:pages", NS).evaluatesTo(3))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:replacedExisting", NS).evaluatesTo("false"));
        assertThat(DIRS.output.resolve("CLM-S1.pdf")).exists();
    }

    @Test
    void validationFailureIsAClientFaultWithTheSameDetailAsRest() throws Exception {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-S2");
        claim.getBillingProvider().setNpi(ClaimFixtures.NPI_INVALID_CHECK);
        client.sendRequest(withPayload(request(claim)))
                .andExpect(clientOrSenderFault())
                .andExpect(xpath("//c:generateClaimBundleFault/c:status", NS).evaluatesTo("ERROR"))
                .andExpect(xpath("//c:generateClaimBundleFault/c:code", NS).evaluatesTo("VALIDATION_ERROR"))
                .andExpect(xpath("//c:generateClaimBundleFault/c:claimNumber", NS).evaluatesTo("CLM-S2"))
                .andExpect(xpath("//c:generateClaimBundleFault/c:details/c:field", NS).evaluatesTo("billingProvider.npi"))
                .andExpect(xpath("count(//c:generateClaimBundleFault/c:details)", NS).evaluatesTo(1));
        assertThat(DIRS.output.resolve("CLM-S2.pdf")).doesNotExist();
    }

    @Test
    void attachmentProblemIsAClientFault() throws Exception {
        Files.writeString(DIRS.attachments.resolve("CLM-S3_1.xlsx"), "x");
        client.sendRequest(withPayload(request(ClaimFixtures.fullClaim("CLM-S3"))))
                .andExpect(clientOrSenderFault())
                .andExpect(xpath("//c:generateClaimBundleFault/c:code", NS).evaluatesTo("UNSUPPORTED_ATTACHMENT"));
    }

    @Test
    void requestNotMatchingTheSchemaIsRejectedBeforeTheEndpoint() throws Exception {
        String xml = "<c:generateClaimBundleRequest xmlns:c=\"" + ClaimSoapEndpoint.NAMESPACE + "\">"
                + "<c:claim><c:claimNumber>X</c:claimNumber></c:claim></c:generateClaimBundleRequest>";
        client.sendRequest(withPayload(new StringSource(xml)))
                .andExpect(clientOrSenderFault());
    }

    @Test
    void sampleEnvelopeFromTheRepositoryIsAccepted() throws Exception {
        String envelope = Files.readString(Path.of("samples/claim-soap-request.xml"));
        client.sendRequest(withSoapEnvelope(new StringSource(envelope)))
                .andExpect(noFault())
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:claimNumber", NS).evaluatesTo("CLM-2026-000124"))
                .andExpect(xpath("/c:generateClaimBundleResponse/c:result/c:attachmentCount", NS).evaluatesTo(1));
    }

    static Source request(Cms1500Claim claim) throws Exception {
        GenerateClaimBundleRequest request = new GenerateClaimBundleRequest();
        request.setClaim(claim);
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        StringWriter out = new StringWriter();
        marshaller.marshal(request, out);
        return new StringSource(out.toString());
    }
}
