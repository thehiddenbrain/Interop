package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * Files of the tester's own as Submit attachments (R-13): checked strictly before anything is built,
 * each refusal at its own field, sent as PasDocumentReference data with their size, and never kept
 * in the ledger or printed.
 */
class PasAttachmentFilesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("UTC"));
    private static final String CONTENT = "P32H placeholder document, synthetic bytes";
    private static final String DATA = Base64.getEncoder().encodeToString(CONTENT.getBytes(StandardCharsets.UTF_8));
    private static final long SIZE = CONTENT.getBytes(StandardCharsets.UTF_8).length;

    private static PasAttachment file(String contentType, String data, Long size) {
        return PasAttachment.file("Placeholder study", contentType, data, size);
    }

    private static List<FieldError> errors(PasAttachment... attachments) {
        RunInputs inputs = new RunInputs(CLOCK);
        PasAttachmentChecks.check(List.of(attachments), inputs);
        return inputs.errors();
    }

    /** Base64 of a file of the given size, without allocating its bytes twice. */
    private static String dataOf(long bytes) {
        return "A".repeat((int) (bytes / 3 * 4)) + (bytes % 3 == 1 ? "AA==" : bytes % 3 == 2 ? "AAA=" : "");
    }

    // ---- strict base64 ----

    @Test
    void strictBase64IsCountedAndAnythingElseIsRefused() {
        assertThat(PasAttachmentChecks.decodedLength(DATA)).isEqualTo(SIZE);
        assertThat(PasAttachmentChecks.decodedLength("QQ==")).isEqualTo(1);
        assertThat(PasAttachmentChecks.decodedLength("QUI=")).isEqualTo(2);
        assertThat(PasAttachmentChecks.decodedLength("QUJD")).isEqualTo(3);
        for (String bad : List.of("", "QQ", "QQ=", "Q===", "QQ=A", "QU JD", "QUJD\n", "QUJ-", "QUJ_", "=QUJ")) {
            assertThat(PasAttachmentChecks.decodedLength(bad)).as(bad).isEqualTo(-1);
        }
        assertThat(PasAttachmentChecks.decodedLength(dataOf(1_000_001))).isEqualTo(1_000_001);
    }

    // ---- the checks, each at its field ----

    @Test
    void aFileWithItsDataItsSizeAndAKindSubmitSendsPasses() {
        assertThat(errors(file("application/pdf", DATA, SIZE))).isEmpty();
        assertThat(errors(file("IMAGE/PNG", DATA, SIZE))).isEmpty();
        assertThat(errors(new PasAttachment("Typed note", "text/plain", CONTENT, null))).isEmpty();
        assertThat(errors(new PasAttachment("Linked", "application/pdf", null, "https://documents.example.invalid/1.pdf"))).isEmpty();
    }

    @Test
    void dataThatIsNotBase64IsRefusedAtTheData() {
        assertThat(errors(file("application/pdf", "not base64!", 8L))).extracting(FieldError::field).containsExactly("attachments[0].data");
    }

    @Test
    void aSizeThatDoesNotMatchTheDataOrIsMissingIsRefusedAtTheSize() {
        List<FieldError> mismatch = errors(file("application/pdf", DATA, SIZE - 1));
        assertThat(mismatch).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("attachments[0].size");
            assertThat(error.message()).isEqualTo("The file's data holds " + SIZE + " bytes, not the " + (SIZE - 1) + " stated: it may have "
                    + "been cut short on the way. Choose the file again.");
        });
        assertThat(errors(file("application/pdf", DATA, null))).extracting(FieldError::field).containsExactly("attachments[0].size");
        assertThat(errors(new PasAttachment("Typed note", "text/plain", CONTENT, null, null, null, null, 4L)))
                .extracting(FieldError::field).containsExactly("attachments[0].size");
    }

    @Test
    void aKindSubmitDoesNotSendIsRefusedAtTheContentType() {
        assertThat(errors(file("application/zip", DATA, SIZE))).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("attachments[0].contentType");
            assertThat(error.message()).isEqualTo("Submit does not send a file of this kind. It sends PDF, PNG, JPEG, TIFF, GIF, plain text "
                    + "and Word documents.");
        });
        // Every kind the options serve passes.
        for (PasOptions.Kind kind : PasOptions.CURRENT.attachments().kinds()) {
            assertThat(errors(file(kind.contentType(), DATA, SIZE))).as(kind.extension()).isEmpty();
        }
    }

    @Test
    void aFileOverFiveMegabytesAndFilesOverTenTogetherAreRefused() {
        long over = PasOptions.PER_FILE_BYTES + 1;
        assertThat(errors(file("application/pdf", dataOf(over), over))).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("attachments[0].data");
            assertThat(error.message()).isEqualTo("This file holds 5242881 bytes. Submit sends at most 5 MB a file (5242880 bytes).");
        });
        assertThat(errors(file("application/pdf", dataOf(PasOptions.PER_FILE_BYTES), PasOptions.PER_FILE_BYTES))).isEmpty();

        long four = 4L * 1024 * 1024;
        String data = dataOf(four);
        List<FieldError> together = errors(file("application/pdf", data, four), file("image/png", data, four), file("image/gif", data, four));
        assertThat(together).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("attachments");
            assertThat(error.message()).isEqualTo("These attachments hold 12582912 bytes together. Submit sends at most 10 MB in all "
                    + "(10485760 bytes): leave a file out.");
        });
        assertThat(errors(file("application/pdf", data, four), file("image/png", data, four))).isEmpty();
    }

    @Test
    void anAttachmentCarriesExactlyOneOfDataTextAndUrl() {
        String message = "An attachment carries its file as data, its text, or a url, and exactly one of the three.";
        assertThat(errors(new PasAttachment("Both", "text/plain", CONTENT, "https://documents.example.invalid/1.txt")))
                .extracting(FieldError::message).containsExactly(message);
        assertThat(errors(new PasAttachment("None", "text/plain", null, null))).extracting(FieldError::message).containsExactly(message);
        assertThat(errors(new PasAttachment("Data and text", "text/plain", CONTENT, null, null, null, DATA, SIZE)))
                .extracting(FieldError::field).containsExactly("attachments[0]");
        assertThat(errors(file("application/pdf", DATA, SIZE), new PasAttachment("None", "text/plain", null, null)))
                .extracting(FieldError::field).containsExactly("attachments[1]");
    }

    @Test
    void noRefusalRepeatsTheContentOrTheTitle() {
        for (FieldError error : errors(file("application/zip", DATA, SIZE - 1), file("application/pdf", "!!!!", 3L))) {
            assertThat(error.message()).doesNotContain(DATA).doesNotContain(CONTENT).doesNotContain("Placeholder study");
        }
    }

    // ---- what is built ----

    private static PasSubmitRequest.Builder request(PasAttachment... attachments) {
        return PasSubmitRequest.builder().member(MemberDetail.builder().memberId("P32H-PAS-APPROVED").firstName("Given")
                        .lastName("Placeholder-Surname").gender("female").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).attachments(List.of(attachments));
    }

    private static JsonNode built(PasRequestBuilder.Built built, String id) {
        for (JsonNode entry : JsonSafe.parse(built.json()).node().get("entry")) {
            if (id.equals(entry.at("/resource/id").asString())) {
                return entry.get("resource");
            }
        }
        return null;
    }

    private static PasRequestBuilder.Built build(PasSubmitRequest request) {
        return new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_FHIR, CLOCK, UUID::randomUUID).under(PasGuide.V221)
                .submit(request, "claim-test-0001", true, "urn:uuid:00000000-0000-4000-8000-0000000000b1",
                        name -> new ResolvedText(name, null, null, List.of(), List.of(name)));
    }

    @Test
    void aFileGoesAsItsDataAndSizeATextAsItsBase64AndAUrlWithANoteAndACount() {
        PasRequestBuilder.Built built = build(request(file("application/pdf", DATA, SIZE),
                new PasAttachment("Typed note", "text/plain", CONTENT, null),
                new PasAttachment("Linked", "application/pdf", null, "https://documents.example.invalid/1.pdf")).build());
        JsonNode first = built(built, "document-reference-1").at("/content/0/attachment");
        assertThat(first.get("data").asString()).isEqualTo(DATA);
        assertThat(first.get("size").asLong()).isEqualTo(SIZE);
        JsonNode second = built(built, "document-reference-2").at("/content/0/attachment");
        assertThat(second.get("data").asString()).isEqualTo(DATA);
        assertThat(second.get("size").asLong()).isEqualTo(SIZE);
        JsonNode third = built(built, "document-reference-3").at("/content/0/attachment");
        assertThat(third.get("url").asString()).isEqualTo("https://documents.example.invalid/1.pdf");
        assertThat(third.has("data")).isFalse();
        assertThat(built.leftOut()).contains("PASDocumentReference content.attachment.data (attachment 3)")
                .doesNotContain("PASDocumentReference content.attachment.data (attachment 1)");
        assertThat(built.notes()).contains("Attachment 3 was sent by its url, as it was given. PAS 2.2.1 PASDocumentReference requires the "
                + "document inline as data and forbids a url, so the service may refuse the request for that alone. Choose the file on "
                + "the Submit step to send it inline.");
        assertThat(built.notes()).contains("Attachment 1 (\"Placeholder study\") travels in the bundle as DocumentReference/document-reference-1, "
                + "with its content inline.");
    }

    @Test
    void inX12ModeAFileIsNamedByItsControlNumberAndItsDataStaysOutOfTheBundle() {
        PasRequestBuilder.Built built = new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_X12_275, CLOCK, UUID::randomUUID).under(PasGuide.V221)
                .submit(request(file("application/pdf", DATA, SIZE)).build(), "claim-test-0001", true,
                        "urn:uuid:00000000-0000-4000-8000-0000000000b1",
                        name -> "{{pasReportType}}".equals(name) ? ResolvedText.plain("09")
                                : new ResolvedText(name, null, null, List.of(), List.of(name)));
        assertThat(built.json()).doesNotContain(DATA);
        JsonNode info = JsonSafe.parse(built.json()).node().at("/entry/0/resource/supportingInfo/0");
        assertThat(info.at("/valueReference/identifier/value").asString()).isEqualTo("claim-test-0001-A1");
        JsonNode parts = info.at("/extension/0/extension");
        assertThat(parts.get(2).get("url").asString()).isEqualTo("controlNumber");
        assertThat(parts.get(2).get("valueString").asString()).isEqualTo("claim-test-0001-A1");
    }

    // ---- through the service: refused at the field, never in the ledger ----

    private final InMemoryLedger ledger = new InMemoryLedger();

    private PasService service() {
        AppProperties app = AppProperties.of(Map.of("mock-latency-enabled", "false"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        ObjectProvider<RunLedger> provider = beans.getBeanProvider(RunLedger.class);
        OnyxProperties onyx = OnyxProperties.of(Map.of());
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), onyx, provider, CLOCK);
        return new PasService(onyx, FakeGateway.unconfigured().pasGuide("2.2.1"),
                new MockOnyxPasClient(new ScenarioRepository(app), new MockExchanges(app)), new FakeCodeList(), support, provider);
    }

    @Test
    void theServiceRefusesABadFileAtItsFieldBeforeAnythingIsSentOrRecorded() {
        assertThatThrownBy(() -> service().runSubmit(request(file("application/pdf", DATA, SIZE + 3)).build(), null, null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field)
                        .containsExactly("attachments[0].size"));
        assertThatThrownBy(() -> service().previewSubmit(request(file("application/pdf", "####", 3L)).build(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field)
                        .containsExactly("attachments[0].data"));
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theLedgerLineOfASubmissionWithAFileHoldsNoneOfItsContent() {
        service().runSubmit(request(file("application/pdf", DATA, SIZE), new PasAttachment("Typed note", "text/plain", CONTENT, null)).build(),
                null, null);
        RunRecord record = ledger.only();
        assertThat(record.attachmentMode()).isEqualTo("fhir");
        String line = JsonSafe.mapper().writeValueAsString(record);
        assertThat(line).doesNotContain(DATA).doesNotContain(CONTENT);
    }

    @Test
    void anAttachmentAndARequestHoldingOneAreNeverPrintedWithTheirContent() {
        PasAttachment attachment = file("application/pdf", DATA, SIZE);
        assertThat(attachment.toString()).doesNotContain(DATA).doesNotContain("Placeholder study").contains("size=" + SIZE);
        assertThat(new PasAttachment("Typed note", "text/plain", CONTENT, null).toString()).doesNotContain(CONTENT);
        assertThat(request(attachment).build().toString()).doesNotContain(DATA).doesNotContain("Placeholder-Surname");
    }
}
