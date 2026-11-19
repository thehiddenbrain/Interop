package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.OperationDetector.Detection;

class OperationDetectorTest {

    /** The thirteen request names of the vendor's collection and the operation each must be detected as. */
    static final Map<String, EpaOperation> VENDOR_TABLE = vendorTable();

    private static Map<String, EpaOperation> vendorTable() {
        Map<String, EpaOperation> table = new LinkedHashMap<>();
        table.put("$questionnaire-package Happy path", EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        table.put("$questionnaire-package Error", EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        table.put("next-question Happy Path", EpaOperation.DTR_NEXT_QUESTION);
        table.put("submit Happy Path", EpaOperation.PAS_SUBMIT);
        table.put("inquiry Happy Path", EpaOperation.PAS_INQUIRE);
        table.put("order-sign Happy Path", EpaOperation.CRD_ORDER_SIGN);
        table.put("order-sign Error", EpaOperation.CRD_ORDER_SIGN);
        table.put("subscription-get Happy Path", EpaOperation.SUBSCRIPTION_GET);
        table.put("subscription-get Error", EpaOperation.SUBSCRIPTION_GET);
        table.put("subscription-create Happy Path", EpaOperation.SUBSCRIPTION_CREATE);
        table.put("subscription-update Happy Path", EpaOperation.SUBSCRIPTION_UPDATE);
        table.put("subscription-delete Happy Path", EpaOperation.SUBSCRIPTION_DELETE);
        table.put("subscription-notification Happy Path", EpaOperation.SUBSCRIPTION_NOTIFICATION);
        return table;
    }

    @Test
    void everyRequestOfTheVendorCollectionIsDetectedAsTheOperationInTheTable() {
        PostmanCollection collection = PostmanParser.collection(PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION));

        assertThat(collection.requests()).extracting(PostmanRequest::name).containsExactlyElementsOf(VENDOR_TABLE.keySet());
        assertThat(VENDOR_TABLE).hasSize(13);
        for (PostmanRequest request : collection.requests()) {
            Detection detection = OperationDetector.detect(request.name(), request.method(), request.url(), request.folderPath());

            assertThat(detection.operation()).as(request.name()).isEqualTo(VENDOR_TABLE.get(request.name()));
            assertThat(detection.basis()).as(request.name()).startsWith("The name contains \"");
        }
    }

    @Test
    void theVendorNamesAloneAreEnoughWhateverUrlTheVendorGaveThem() {
        // The real urls are not known. A request that exists to show an error may carry a wrong one on purpose.
        for (Map.Entry<String, EpaOperation> row : VENDOR_TABLE.entrySet()) {
            assertThat(OperationDetector.detect(row.getKey(), null).operation()).as(row.getKey()).isEqualTo(row.getValue());
            assertThat(OperationDetector.detect(row.getKey(), "{{someUrl}}").operation()).as(row.getKey()).isEqualTo(row.getValue());
            assertThat(OperationDetector.detect(row.getKey(), "POST", "https://example.invalid/wrong/on/purpose", List.of("Samples"))
                    .operation()).as(row.getKey()).isEqualTo(row.getValue());
        }
    }

    @Test
    void orderSelectAndDiscoveryAreDetectedThoughTheVendorCollectionHasNeither() {
        assertThat(OperationDetector.detect("order-select Happy Path", "{{crdUrl}}/cds-services/{{id}}").operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(OperationDetector.detect("orderSelect", null).operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(OperationDetector.detect("CDS Services discovery", null).operation()).isEqualTo(EpaOperation.CRD_DISCOVERY);
        assertThat(OperationDetector.detect("list the hooks", "GET", "{{crdUrl}}/cds-services", List.of()).operation())
                .isEqualTo(EpaOperation.CRD_DISCOVERY);
        assertThat(OperationDetector.detect("list the hooks", "GET", "https://crd.example.invalid/r4/cds-services/?x=1", List.of()).operation())
                .isEqualTo(EpaOperation.CRD_DISCOVERY);
        assertThat(OperationDetector.detect("hook", "POST", "{{crdUrl}}/cds-services/p32h-order-select-v2", List.of()).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(OperationDetector.detect("hook", "POST", "{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}", List.of()).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SIGN);
    }

    @Test
    void namesWrittenInOtherStylesReadTheSame() {
        assertThat(OperationDetector.detect("Order_Sign happy", null).operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(OperationDetector.detect("orderSign", null).operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(OperationDetector.detect("QuestionnairePackage", null).operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(OperationDetector.detect("NEXT QUESTION", null).operation()).isEqualTo(EpaOperation.DTR_NEXT_QUESTION);
        assertThat(OperationDetector.detect("Delete a Subscription", null).operation()).isEqualTo(EpaOperation.SUBSCRIPTION_DELETE);
        assertThat(OperationDetector.detect("PAS Inquire", null).operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(OperationDetector.detect("Claim submission", null).operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        // Whole words only: "forget" is not "get", and "resubscription" is not "subscription".
        assertThat(OperationDetector.detect("forget subscription", null).operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(OperationDetector.detect("signal the order", null).operation()).isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void whenTheNameSaysNothingTheUrlIsRead() {
        assertThat(OperationDetector.detect("Happy path", "POST", "{{baseUrl}}/Claim/$submit", List.of()).operation())
                .isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(OperationDetector.detect("Happy path", "POST", "{{baseUrl}}/Claim/$inquire?_format=json", List.of()).operation())
                .isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(OperationDetector.detect("Happy path", "POST", "{{baseUrl}}/Questionnaire/$questionnaire-package", List.of()).operation())
                .isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(OperationDetector.detect("Happy path", "POST", "{{baseUrl}}/Questionnaire/$next-question", List.of()).operation())
                .isEqualTo(EpaOperation.DTR_NEXT_QUESTION);
        Detection detection = OperationDetector.detect("Happy path", "POST", "{{baseUrl}}/Claim/$submit", List.of());
        assertThat(detection.basis()).isEqualTo("The name does not say, and the url has a $submit segment.");
        assertThat(detection.conflict()).isFalse();
    }

    @Test
    void theMethodTellsTheSubscriptionOperationsApartWhenTheNameDoesNot() {
        String one = "{{baseUrl}}/Subscription/{{id}}";
        assertThat(OperationDetector.detect("s", "GET", one, List.of()).operation()).isEqualTo(EpaOperation.SUBSCRIPTION_GET);
        assertThat(OperationDetector.detect("s", "PUT", one, List.of()).operation()).isEqualTo(EpaOperation.SUBSCRIPTION_UPDATE);
        assertThat(OperationDetector.detect("s", "delete", one, List.of()).operation()).isEqualTo(EpaOperation.SUBSCRIPTION_DELETE);
        assertThat(OperationDetector.detect("s", "POST", "{{baseUrl}}/Subscription", List.of()).operation())
                .isEqualTo(EpaOperation.SUBSCRIPTION_CREATE);
        assertThat(OperationDetector.detect("s", "GET", "{{baseUrl}}/Subscription", List.of()).operation())
                .isEqualTo(EpaOperation.SUBSCRIPTION_GET);
        // Without a method the url cannot say which of the four it is, and nothing is guessed.
        Detection unknown = OperationDetector.detect("s", one);
        assertThat(unknown.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(unknown.basis()).contains("could be any of the four subscription operations").endsWith("Choose one.");
    }

    @Test
    void aHookUrlThatDoesNotSayWhichHookIsNotGuessed() {
        Detection detection = OperationDetector.detect("Happy path", "POST", "{{crdUrl}}/cds-services/{{serviceId}}", List.of());

        assertThat(detection.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(detection.basis()).isEqualTo("The url addresses a CDS Hooks service whose id does not say which hook it is, and "
                + "nothing in the name says which operation that is. Choose one.");
    }

    @Test
    void whenTheNameAndTheUrlDisagreeTheNameIsFollowedAndTheDisagreementIsStated() {
        Detection detection = OperationDetector.detect("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$submit", List.of("PAS"));

        assertThat(detection.operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(detection.conflict()).isTrue();
        assertThat(detection.basis()).isEqualTo("The name contains \"inquiry\", which reads as PAS inquire, but the url has a $submit "
                + "segment, which reads as PAS submit. The name was followed. Check this mapping and correct it if the url is the one to believe.");

        Detection agree = OperationDetector.detect("submit Happy Path", "POST", "{{baseUrl}}/Claim/$submit", List.of("PAS"));
        assertThat(agree.conflict()).isFalse();
        assertThat(agree.basis()).isEqualTo("The name contains \"submit\", and the url has a $submit segment.");
    }

    @Test
    void theFolderIsConsultedOnlyWhenNeitherTheNameNorTheUrlSays() {
        Detection byFolder = OperationDetector.detect("Create", "POST", "{{subscriptionUrl}}", List.of("FHIR", "Subscription"));
        assertThat(byFolder.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_CREATE);
        assertThat(byFolder.basis()).startsWith("Neither the name nor the url says on its own.");

        // The name says inquiry. A folder that mentions submit does not change that.
        assertThat(OperationDetector.detect("inquiry Happy Path", "POST", "{{u}}", List.of("submit and inquiry")).operation())
                .isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(OperationDetector.detect("Happy path", "POST", "{{u}}", List.of("Misc")).operation()).isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void aRequestNothingIsKnownAboutIsUnmappedWhichIsNotAnError() {
        Detection detection = OperationDetector.detect("Capability statement", "GET", "{{baseUrl}}/metadata", List.of());

        assertThat(detection.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(detection.basis()).isEqualTo("Neither the name nor the url matches an operation this tool knows.");
        assertThat(detection.conflict()).isFalse();
        assertThat(OperationDetector.detect(null, null).operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(OperationDetector.detect("  ", "  ").operation()).isEqualTo(EpaOperation.UNMAPPED);
    }

    // ------------------------------------------------------------------------------------------
    // What the guardrail asks: every operation the name or the url reads as
    // ------------------------------------------------------------------------------------------

    @Test
    void theSignalsHoldEveryReadingOfTheNameAndOfTheUrl() {
        assertThat(OperationDetector.signals("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$submit"))
                .containsExactlyInAnyOrder(EpaOperation.PAS_INQUIRE, EpaOperation.PAS_SUBMIT);
        assertThat(OperationDetector.signals("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire"))
                .containsExactly(EpaOperation.PAS_INQUIRE);
        assertThat(OperationDetector.signals("submit then inquiry", "POST", null))
                .containsExactlyInAnyOrder(EpaOperation.PAS_SUBMIT, EpaOperation.PAS_INQUIRE);
        assertThat(OperationDetector.signals("Capability statement", "GET", "{{baseUrl}}/metadata")).isEmpty();
        assertThat(OperationDetector.signals(null, null, null)).isEmpty();
    }

    @Test
    void aWriteHiddenInTheUrlIsReadHoweverItIsWritten() {
        for (String url : new String[] {"https://x.example.invalid/fhir/Claim/$submit", "https://x.example.invalid/fhir/Claim/%24submit",
                "https://x.example.invalid/fhir/Claim/%24SUBMIT", "https://x.example.invalid/fhir/claim/$Submit/",
                "https://x.example.invalid/fhir/Claim/$submit?async=true", "https://x.example.invalid/fhir/Claim/$submit;v=1#top",
                "{{baseUrl}}/Claim/$submit", "/Claim/$submit"}) {
            assertThat(OperationDetector.signals("anything", "POST", url)).as(url).contains(EpaOperation.PAS_SUBMIT);
        }
        // A host called like an operation is not an operation.
        assertThat(OperationDetector.signals("anything", "GET", "https://subscription/fhir/metadata")).isEmpty();
        assertThat(OperationDetector.signals("anything", "GET", "https://cds-services/fhir/metadata")).isEmpty();
    }

    @Test
    void aFhirOperationTheToolDoesNotKnowIsReadAsUnknownAndNotAsNothing() {
        // The name says inquiry. The url ends in an operation that may do anything.
        assertThat(OperationDetector.signals("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$submit-attachment"))
                .containsExactlyInAnyOrder(EpaOperation.PAS_INQUIRE, EpaOperation.UNMAPPED);
        assertThat(OperationDetector.signals("x", "POST", "{{baseUrl}}/Subscription/1/$status")).contains(EpaOperation.UNMAPPED);
        assertThat(OperationDetector.signals("x", "POST", "{{baseUrl}}/Subscription/1")).containsExactly(EpaOperation.UNMAPPED);
        assertThat(OperationDetector.signals("x", "TRACE", "{{baseUrl}}/Subscription/1")).containsExactly(EpaOperation.UNMAPPED);
        // A lone dollar sign is not an operation.
        assertThat(OperationDetector.signals("x", "GET", "{{baseUrl}}/prices/$/list")).isEmpty();
    }

    @Test
    void theFolderIsNeverASignalSoAFolderNameCannotMakeAnInquiryAWrite() {
        PostmanRequest request = PostmanRequest.builder().name("inquiry Happy Path").folderPath(List.of("submit and inquiry"))
                .method("POST").url("{{baseUrl}}/Claim/$inquire").build();

        assertThat(OperationDetector.signals(request.name(), request.method(), request.url())).containsExactly(EpaOperation.PAS_INQUIRE);
    }

    @Test
    void theVendorReadsAreNeverSignalledAsWrites() {
        PostmanCollection collection = PostmanParser.collection(PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION));

        for (PostmanRequest request : collection.requests()) {
            EpaOperation expected = VENDOR_TABLE.get(request.name());
            boolean signalledWrite = OperationDetector.signals(request.name(), request.method(), request.url()).stream()
                    .anyMatch(operation -> operation.writesWith(request.method()));
            // A notification is judged a write for a POST, because nothing is known about posting one to a vendor url.
            boolean expectedWrite = expected.write() || expected == EpaOperation.SUBSCRIPTION_NOTIFICATION;
            assertThat(signalledWrite).as(request.name()).isEqualTo(expectedWrite);
        }
    }
}
