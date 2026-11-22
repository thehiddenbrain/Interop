package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The CRD request in the shape the vendor's sandbox accepted (R-37, history V7), held against a
 * placeholder fixture written from that accepted request, for both hooks and every setting of the
 * draftOrders shape with its source.
 */
class VendorShapesCrdTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09.750Z"), ZoneOffset.UTC);
    private static final String FHIR = "https://fhir.vendor.example.invalid";
    private static final Function<String, ResolvedText> NOTHING_SET = template -> new FakeGateway().resolve(template, Map.of());
    private static final Function<String, ResolvedText> WITH_FHIR_SERVER =
            template -> new FakeGateway().resolve(template, Map.of("fhirServer", FHIR + "/"));
    private static final List<String> NAMED = List.of("patientBundle", "coverageBundle", "practitionerBundle", "practitionerRoleBundle",
            "serviceRequestBundle");

    private static CrdRunRequest.Builder request(String hook) {
        return CrdRunRequest.builder().hook(hook)
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").coverageStatus("active").vendorPatientId("PATIENT-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().firstName("Pat").lastName("Provider").vendorPractitionerId("P123").build())
                .procedureCodes(List.of(CodeInput.of("E0601"), new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService("2026-03-20");
    }

    private static CrdRequestBuilder.Built built(CrdRunRequest request, Function<String, ResolvedText> variables, RequestShapes shapes) {
        return new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", variables, shapes);
    }

    private static JsonNode json(CrdRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = VendorShapesCrdTest.class.getResourceAsStream("/vendor-shapes/" + name)) {
            assertThat(in).as(name).isNotNull();
            return JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).node();
        }
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        JsonSafe.properties(object).forEach(property -> out.add(property.getKey()));
        return out;
    }

    /** Every place in the JSON that holds an empty array, an empty object or an empty string, by its path. Walked with a stack, not by recursion. */
    static List<String> empties(JsonNode root) {
        List<String> found = new ArrayList<>();
        Deque<Map.Entry<String, JsonNode>> stack = new ArrayDeque<>();
        stack.push(Map.entry("", root));
        while (!stack.isEmpty()) {
            Map.Entry<String, JsonNode> at = stack.pop();
            JsonNode node = at.getValue();
            if (node.isContainer() && node.isEmpty()) {
                found.add(at.getKey());
            } else if (node.isString() && node.stringValue().isEmpty()) {
                found.add(at.getKey());
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    stack.push(Map.entry(at.getKey() + "/" + i, node.get(i)));
                }
            } else if (node.isObject()) {
                JsonSafe.properties(node).forEach(property -> stack.push(Map.entry(at.getKey() + "/" + property.getKey(), property.getValue())));
            }
        }
        return found;
    }

    /** Every bundle entry of a CRD request: the draft orders and each named prefetch Bundle. */
    private static List<JsonNode> entries(JsonNode json) {
        List<JsonNode> out = new ArrayList<>(JsonSafe.elements(json.at("/context/draftOrders"), "entry"));
        JsonSafe.properties(json.get("prefetch")).forEach(bundle -> out.addAll(JsonSafe.elements(bundle.getValue(), "entry")));
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-sign", "order-select"})
    void theRequestHasTheShapeTheVendorAccepted(String hook) throws IOException {
        JsonNode accepted = fixture("crd-order-sign-accepted.json");
        JsonNode json = json(built(request(hook).build(), WITH_FHIR_SERVER,
                RequestShapes.DEFAULTS.with(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShapes.Choice.ofSample(RequestShape.LEFT_OUT, "Order sign"))));

        // The prefetch keys are the five named Bundles of the accepted request, each of type collection.
        assertThat(names(json.get("prefetch"))).isEqualTo(names(accepted.get("prefetch")));
        for (String name : NAMED) {
            assertThat(json.at("/prefetch/" + name + "/resourceType").asString()).as(name).isEqualTo("Bundle");
            assertThat(json.at("/prefetch/" + name + "/type").asString()).as(name).isEqualTo("collection");
        }
        assertThat(json.at("/prefetch/patientBundle/entry/0/resource/resourceType").asString()).isEqualTo("Patient");
        assertThat(json.at("/prefetch/coverageBundle/entry/0/resource/resourceType").asString()).isEqualTo("Coverage");
        assertThat(json.at("/prefetch/practitionerBundle/entry/0/resource/resourceType").asString()).isEqualTo("Practitioner");
        assertThat(json.at("/prefetch/practitionerRoleBundle/entry/0/resource/resourceType").asString()).isEqualTo("PractitionerRole");
        assertThat(json.at("/prefetch/serviceRequestBundle/entry")).hasSize(2);

        // draftOrders is exactly {resourceType, entry}, as accepted, when the setting leaves type out.
        assertThat(names(json.at("/context/draftOrders"))).isEqualTo(names(accepted.at("/context/draftOrders")));

        // userId and every requester are the Practitioner under the vendor's own id; patientId is bare.
        assertThat(json.at("/context/userId").asString()).isEqualTo(accepted.at("/context/userId").asString());
        assertThat(json.at("/context/patientId").asString()).isEqualTo(accepted.at("/context/patientId").asString());
        for (JsonNode entry : JsonSafe.elements(json.at("/context/draftOrders"), "entry")) {
            assertThat(entry.at("/resource/requester/reference").asString()).isEqualTo("Practitioner/P123");
            assertThat(entry.at("/resource/subject/reference").asString()).isEqualTo("Patient/PATIENT-PLACEHOLDER-1");
        }
        assertThat(json.at("/prefetch/practitionerRoleBundle/entry/0/resource/practitioner/reference").asString()).isEqualTo("Practitioner/P123");

        // An absolute fullUrl on every entry of every bundle, the FHIR server's address with no doubled slash.
        List<JsonNode> entries = entries(json);
        assertThat(entries).hasSize(1 + 1 + 1 + 1 + 2 + 2);
        for (JsonNode entry : entries) {
            String type = entry.at("/resource/resourceType").asString();
            String id = entry.at("/resource/id").asString();
            assertThat(entry.get("fullUrl").asString()).isEqualTo(FHIR + "/" + type + "/" + id);
        }
        assertThat(json.at("/prefetch/patientBundle/entry/0/fullUrl").asString())
                .isEqualTo(accepted.at("/prefetch/patientBundle/entry/0/fullUrl").asString());

        // No empty array, object or string anywhere: the vendor's validator refused each one it met.
        assertThat(empties(json)).isEmpty();
        assertThat(json.get("fhirServer").asString()).isEqualTo(FHIR + "/");
        if ("order-select".equals(hook)) {
            assertThat(json.at("/context/selections")).hasSize(2);
        } else {
            assertThat(json.at("/context").has("selections")).isFalse();
        }
    }

    @Test
    void withNoFhirServerEveryFullUrlIsAUrnUuidAndOneResourceKeepsOneFullUrl() {
        CrdRequestBuilder.Built built = built(request("order-sign").build(), NOTHING_SET, RequestShapes.DEFAULTS);
        JsonNode json = json(built);
        for (JsonNode entry : entries(json)) {
            assertThat(entry.get("fullUrl").asString()).startsWith("urn:uuid:").hasSize("urn:uuid:".length() + 36);
        }
        // The order in draftOrders and the same order in serviceRequestBundle are one resource, so one fullUrl.
        assertThat(json.at("/prefetch/serviceRequestBundle/entry/0/fullUrl").asString())
                .isEqualTo(json.at("/context/draftOrders/entry/0/fullUrl").asString());
        assertThat(built.notes()).contains("Every bundle entry carries a fullUrl of the form urn:uuid: because the variable fhirServer is "
                + "not set; with it set, each would be the FHIR server, the resource type and its id.");
        assertThat(empties(json)).isEmpty();
    }

    @Test
    void byDefaultTheTypeIsSentAndTheNoteSaysItIsTheDefault() {
        CrdRequestBuilder.Built built = built(request("order-sign").build(), NOTHING_SET, RequestShapes.DEFAULTS);
        assertThat(json(built).at("/context/draftOrders/type").asString()).isEqualTo("collection");
        assertThat(built.notes()).contains("draftOrders type: Sent, default. context.draftOrders was sent with type collection, as FHIR "
                + "requires. The vendor's sandbox refused context.draftOrders.type with HTTP 400; Left out is set for an environment in "
                + "Configuration > Environments.");
        // The builder without shapes, as the comparison calls it, is the default.
        assertThat(new CrdRequestBuilder(null, CLOCK).build(request("order-sign").build(), "hook-instance-1", NOTHING_SET).json())
                .isEqualTo(built.json());
    }

    @Test
    void eachSettingValueAndSourceDecidesTheTypeAndIsNamedInTheNotes() {
        CrdRequestBuilder.Built leftHere = built(request("order-sign").build(), NOTHING_SET,
                RequestShapes.DEFAULTS.with(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShapes.Choice.ofEnvironment(RequestShape.LEFT_OUT)));
        assertThat(json(leftHere).at("/context/draftOrders").has("type")).isFalse();
        assertThat(leftHere.notes()).contains("draftOrders type: Left out, from this environment. context.draftOrders was sent without type, "
                + "as the vendor's sandbox accepted it; FHIR requires Bundle.type, so a service that follows FHIR may refuse it.");

        CrdRequestBuilder.Built leftBySample = built(request("order-select").build(), NOTHING_SET,
                RequestShapes.DEFAULTS.with(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShapes.Choice.ofSample(RequestShape.LEFT_OUT, "Order sign")));
        assertThat(json(leftBySample).at("/context/draftOrders").has("type")).isFalse();
        assertThat(leftBySample.notes()).contains("draftOrders type: Left out, from the collection's sample (the request \"Order sign\"). "
                + "context.draftOrders was sent without type, as the vendor's sandbox accepted it; FHIR requires Bundle.type, so a service "
                + "that follows FHIR may refuse it.");

        CrdRequestBuilder.Built sentHere = built(request("order-sign").build(), NOTHING_SET,
                RequestShapes.DEFAULTS.with(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShapes.Choice.ofEnvironment(RequestShape.SENT)));
        assertThat(json(sentHere).at("/context/draftOrders/type").asString()).isEqualTo("collection");
        assertThat(sentHere.notes()).anyMatch(note -> note.startsWith("draftOrders type: Sent, from this environment. "));

        CrdRequestBuilder.Built sentBySample = built(request("order-sign").build(), NOTHING_SET,
                RequestShapes.DEFAULTS.with(RequestShape.CRD_DRAFT_ORDERS_TYPE, RequestShapes.Choice.ofSample(RequestShape.SENT, "Order sign")));
        assertThat(json(sentBySample).at("/context/draftOrders/type").asString()).isEqualTo("collection");
        assertThat(sentBySample.notes()).anyMatch(note -> note.startsWith("draftOrders type: Sent, from the collection's sample (the request "
                + "\"Order sign\"). "));
        // Exactly one note about the shape in each request.
        for (CrdRequestBuilder.Built one : List.of(leftHere, leftBySample, sentHere, sentBySample)) {
            assertThat(one.notes().stream().filter(note -> note.startsWith("draftOrders type: "))).hasSize(1);
        }
    }

    @Test
    void quantityAndUnitAreLeftOutWithOneNote() {
        CrdRunRequest request = request("order-sign")
                .procedureCodes(List.of(new CodeInput("E0601", null, null, new BigDecimal("2"), "FL"),
                        new CodeInput("95810", null, null, new BigDecimal("1"), null)))
                .build();
        CrdRequestBuilder.Built built = built(request, NOTHING_SET, RequestShapes.DEFAULTS);
        assertThat(built.json()).doesNotContain("quantity").doesNotContain("\"FL\"");
        assertThat(built.notes().stream().filter(note -> note.contains("quantity"))).containsExactly("The quantity and unit given on the "
                + "codes were left out of this request: Submit sends them, the orders of a CRD or DTR request do not.");
        assertThat(built(request("order-sign").build(), NOTHING_SET, RequestShapes.DEFAULTS).notes())
                .noneMatch(note -> note.contains("quantity"));
    }

    @Test
    void aProviderKnownByAnOrganizationNameIsSentInAnOrganizationBundleWithANote() {
        CrdRunRequest request = request("order-sign")
                .provider(ProviderDetail.builder().npi("1234567893").organizationName("Placeholder Sleep Clinic").build()).build();
        CrdRequestBuilder.Built built = built(request, WITH_FHIR_SERVER, RequestShapes.DEFAULTS);
        JsonNode json = json(built);
        assertThat(names(json.get("prefetch"))).containsExactlyInAnyOrder("patientBundle", "coverageBundle", "organizationBundle",
                "practitionerRoleBundle", "serviceRequestBundle");
        assertThat(json.at("/prefetch/organizationBundle/entry/0/resource/resourceType").asString()).isEqualTo("Organization");
        // No Practitioner is sent for an organization, so the user and the requesters name the role that names it.
        assertThat(json.at("/context/userId").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(json.at("/context/draftOrders/entry/0/resource/requester/reference").asString()).isEqualTo("PractitionerRole/practitioner-role-1");
        assertThat(built.notes()).anyMatch(note -> note.contains("organizationBundle stands in place of practitionerBundle"));
        assertThat(empties(json)).isEmpty();
    }
}
