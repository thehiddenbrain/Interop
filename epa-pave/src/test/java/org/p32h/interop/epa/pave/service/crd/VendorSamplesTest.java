package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanParser;

/**
 * The draftOrders shape read from a vendor's own order-sign sample (R-37). Every collection here
 * is a placeholder written in the shape of history V7: hosts end in .invalid and the only member
 * ID is S123456789.
 */
class VendorSamplesTest {

    private static final String WITHOUT_TYPE = """
            {"hook": "order-sign", "hookInstance": "{{hookInstance}}", "fhirServer": "{{fhirServer}}",
             "context": {"userId": "Practitioner/{{practitionerId}}", "patientId": "S123456789",
                         "draftOrders": {"resourceType": "Bundle", "entry": [{"resource": {"resourceType": "ServiceRequest"}}]}}}
            """;

    private static final String WITH_TYPE = """
            {"hook": "order-sign",
             "context": {"patientId": "S123456789",
                         "draftOrders": {"resourceType": "Bundle", "type": "collection", "entry": []}}}
            """;

    @Test
    void anOrderSignSampleWithoutTypeIsLeftOutWithTheRequestsName() {
        PostmanCollection collection = collection(request("order-sign Happy Path", "{{crdUrl}}/cds-services/order-sign", WITHOUT_TYPE));

        List<ShapeEvidence> evidence = VendorSamples.read(collection);

        assertThat(evidence).containsExactly(new ShapeEvidence(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShape.LEFT_OUT,
                "order-sign Happy Path"));
    }

    @Test
    void anOrderSignSampleWithTypeIsSent() {
        PostmanCollection collection = collection(request("order-sign Happy Path", "{{crdUrl}}/cds-services/order-sign", WITH_TYPE));

        assertThat(VendorSamples.read(collection)).containsExactly(new ShapeEvidence(RequestShape.CRD_DRAFT_ORDERS_TYPE,
                RequestShape.SENT, "order-sign Happy Path"));
    }

    @Test
    void noOrderSignSampleGivesNothing() {
        PostmanCollection collection = collection(request("order-select Happy Path", "{{crdUrl}}/cds-services/order-select", WITHOUT_TYPE),
                request("Questionnaire package", "{{dtrUrl}}/Questionnaire/$questionnaire-package", "{}"));

        assertThat(VendorSamples.read(collection)).isEmpty();
        assertThat(VendorSamples.read(null)).isEmpty();
    }

    @Test
    void anOrderSignSampleWhoseBodyIsNotJsonGivesNothing() {
        PostmanCollection collection = collection(request("order-sign Happy Path", "{{crdUrl}}/cds-services/order-sign",
                "hook=order-sign&context=draftOrders"));

        assertThat(VendorSamples.read(collection)).isEmpty();
    }

    @Test
    void anOrderSignSampleWithoutABodyGivesNothing() {
        PostmanCollection collection = collection(request("order-sign Happy Path", "{{crdUrl}}/cds-services/order-sign", null));

        assertThat(VendorSamples.read(collection)).isEmpty();
    }

    @Test
    void theSampleReadIsTheOneARunUsesNotASampleOfAnError() {
        // The error sample comes first and sends type to show the refusal; a run uses the next one.
        PostmanCollection collection = collection(
                request("order-sign draftOrders type error", "{{crdUrl}}/cds-services/order-sign", WITH_TYPE),
                request("order-sign Happy Path", "{{crdUrl}}/cds-services/order-sign", WITHOUT_TYPE),
                request("order-sign Second", "{{crdUrl}}/cds-services/order-sign", WITH_TYPE));

        assertThat(VendorSamples.read(collection)).containsExactly(new ShapeEvidence(RequestShape.CRD_DRAFT_ORDERS_TYPE,
                RequestShape.LEFT_OUT, "order-sign Happy Path"));
    }

    @Test
    void onlyErrorSamplesGiveNothing() {
        PostmanCollection collection = collection(request("order-sign error", "{{crdUrl}}/cds-services/order-sign", WITHOUT_TYPE));

        assertThat(VendorSamples.read(collection)).isEmpty();
    }

    @Test
    void fromBodyReadsTheSavedBody() {
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, WITHOUT_TYPE)).isEqualTo(RequestShape.LEFT_OUT);
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, WITH_TYPE)).isEqualTo(RequestShape.SENT);
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, null)).isNull();
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, "  ")).isNull();
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, "not json")).isNull();
        assertThat(VendorSamples.fromBody(null, WITHOUT_TYPE)).isNull();
    }

    @Test
    void aBodyThatDoesNotShowDraftOrdersSaysNothing() {
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, "{\"hook\": \"order-sign\", \"context\": {}}")).isNull();
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE,
                "{\"context\": {\"draftOrders\": [\"not an object\"]}}")).isNull();
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, "[1, 2]")).isNull();
    }

    @Test
    void aTypeKeyHoldingNullStillSendsTheKey() {
        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE,
                "{\"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": null}}}")).isEqualTo(RequestShape.SENT);
    }

    @Test
    void aBarePlaceholderOutsideAStringDoesNotHideTheEvidence() {
        String body = "{\"context\": {\"quantity\": {{quantity}}, \"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": {{entries}}},"
                + " \"note\": \"a {{brace}} in a string, an escaped \\\" quote and }} here\"}}";

        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, body)).isEqualTo(RequestShape.LEFT_OUT);
        assertThat(VendorSamples.placeholdersAsNull(body)).contains("\"quantity\": null").contains("\"entry\": null")
                .contains("a {{brace}} in a string");
    }

    @Test
    void aLongBodyIsReadInOnePass() {
        // The placeholder walk must not grow its depth with the body (R-23).
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            entries.append(i == 0 ? "" : ",").append("{{e").append(i).append("}}");
        }
        String body = "{\"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": [" + entries + "]}}}";

        assertThat(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, body)).isEqualTo(RequestShape.LEFT_OUT);
    }

    @Test
    void evidencePrintsNoRequestName() {
        assertThat(new ShapeEvidence(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShape.LEFT_OUT, "order-sign Happy Path").toString())
                .doesNotContain("Happy").contains("LEFT_OUT");
    }

    private static Map<String, Object> request(String name, String url, String body) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("method", "POST");
        request.put("url", url);
        if (body != null) {
            request.put("body", Map.of("mode", "raw", "raw", body, "options", Map.of("raw", Map.of("language", "json"))));
        }
        return Map.of("name", name, "request", request);
    }

    @SafeVarargs
    private static PostmanCollection collection(Map<String, Object>... requests) {
        Map<String, Object> file = Map.of(
                "info", Map.of("name", "Placeholder vendor collection",
                        "schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"),
                "item", new ArrayList<>(List.of(requests)));
        return PostmanParser.collection(JsonSafe.mapper().writeValueAsString(file));
    }
}
