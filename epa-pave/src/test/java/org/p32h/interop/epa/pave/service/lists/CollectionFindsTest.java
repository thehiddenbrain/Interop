package org.p32h.interop.epa.pave.service.lists;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Finds;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundCode;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundMember;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundProvider;

/**
 * The reading of a collection: what each request states, where it was found, and what was seen and
 * not used with why. Every collection here is written for the test, with placeholders only.
 */
class CollectionFindsTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
    static final VariableResolver RESOLVER = new VariableResolver(name -> null, CLOCK, () -> "00000000-0000-0000-0000-000000000000");

    /** One request of a test collection. */
    record Req(String name, String method, String url, String body) {
    }

    static String collection(String schema, List<Req> requests, Map<String, String> variables) {
        StringBuilder items = new StringBuilder();
        for (Req request : requests) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("{\"name\":").append(quote(request.name())).append(",\"request\":{\"method\":\"").append(request.method())
                    .append("\",\"url\":{\"raw\":").append(quote(request.url())).append('}');
            if (request.body() != null) {
                items.append(",\"body\":{\"mode\":\"raw\",\"raw\":").append(quote(request.body()))
                        .append(",\"options\":{\"raw\":{\"language\":\"json\"}}}");
            }
            items.append("}}");
        }
        StringBuilder vars = new StringBuilder();
        variables.forEach((key, value) -> vars.append(vars.isEmpty() ? "" : ",").append("{\"key\":").append(quote(key))
                .append(",\"value\":").append(quote(value)).append('}'));
        return "{\"info\":{\"name\":\"Vendor sample\",\"schema\":\"" + schema + "\"},\"item\":[" + items + "],\"variable\":[" + vars + "]}";
    }

    static String collection(List<Req> requests) {
        return collection("https://schema.getpostman.com/json/collection/v2.1.0/collection.json", requests, Map.of());
    }

    static String quote(String text) {
        return JsonSafe.mapper().writeValueAsString(text);
    }

    static Finds read(String collectionText, Map<String, String> environment) {
        PostmanCollection collection = PostmanParser.collection(collectionText);
        List<VariableLayer> layers = new ArrayList<>();
        Map<String, PostmanVariable> inForce = new java.util.LinkedHashMap<>();
        environment.forEach((key, value) -> inForce.put(key, new PostmanVariable(key, value, true, "default")));
        layers.add(VariableLayer.environment("Sample environment", inForce));
        layers.add(VariableLayer.collection(collection.name(), collection.inForce()));
        return CollectionFinds.read(collection, layers, RESOLVER);
    }

    static final String DRAFT_ORDERS = """
            "draftOrders": {"resourceType": "Bundle", "type": "collection", "entry": [
              {"resource": {"resourceType": "ServiceRequest", "id": "sr-1", "status": "draft",
                "code": {"coding": [{"system": "http://www.ama-assn.org/go/cpt", "code": "97110", "display": "Therapeutic exercise"}]}}},
              {"resource": {"resourceType": "ServiceRequest", "id": "sr-2", "status": "draft",
                "code": {"coding": [{"system": "https://bluebutton.cms.gov/resources/codesystem/hcpcs", "code": "E0601"}],
                         "text": "Continuous airway pressure device"}}}]}""";

    static String orderSign(String patientId, String userId, String prefetch) {
        return "{\"hook\": \"order-sign\", \"hookInstance\": \"{{$guid}}\", \"context\": {\"userId\": \"" + userId + "\", \"patientId\": \""
                + patientId + "\", " + DRAFT_ORDERS + "}" + (prefetch == null ? "" : ", \"prefetch\": " + prefetch) + "}";
    }

    static final String PATIENT_PREFETCH = """
            {"patient": {"resourceType": "Patient", "id": "pat-001",
               "identifier": [
                 {"type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "MR"}]}, "value": "MR-0001"},
                 {"type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "MB"}]}, "value": "S123456789"}],
               "name": [{"use": "usual", "given": ["Sam"]}, {"use": "official", "family": "Placeholder-One", "given": ["Sample", "A"]}],
               "gender": "Female", "birthDate": "1980-01-31"},
             "practitioner": {"resourceType": "Practitioner", "id": "prac-001",
               "identifier": [{"system": "http://hl7.org/fhir/sid/us-npi", "value": "1234567893"}],
               "name": [{"family": "Placeholder-Provider", "given": ["Sample"]}]}}""";

    @Test
    void aCdsHooksOrderSignWithoutPrefetchGivesTheVendorIdsAndTheDraftOrdersCodes() {
        Finds finds = read(collection(List.of(new Req("Order sign", "POST", "{{crdBase}}/cds-services/order-sign-crd",
                orderSign("{{patientId}}", "Practitioner/{{practitionerId}}", null)))),
                Map.of("crdBase", "https://sandbox.example.test", "patientId", "pat-001", "practitionerId", "prac-001"));

        assertThat(finds.members()).containsExactly(new FoundMember(null, "pat-001", null, null, null, null, null, null, null, null, null,
                "Order sign"));
        // The user of a CDS Hooks request orders, so its entry says it is the requester (D-322).
        assertThat(finds.providers()).containsExactly(new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign",
                "Order sign", null));
        assertThat(finds.codes()).containsExactly(
                new FoundCode("97110", "http://www.ama-assn.org/go/cpt", "CPT", "Therapeutic exercise", "Order sign"),
                new FoundCode("E0601", "https://bluebutton.cms.gov/resources/codesystem/hcpcs", "HCPCS", "Continuous airway pressure device",
                        "Order sign"));
        assertThat(finds.notUsed()).isEmpty();
    }

    @Test
    void thePrefetchPatientAndPractitionerJoinTheContextIdsAndTheIdentifierTypedMbIsTheMemberId() {
        Finds finds = read(collection(List.of(new Req("Order sign with prefetch", "POST", "https://sandbox.example.test/cds-services/x",
                orderSign("pat-001", "Practitioner/prac-001", PATIENT_PREFETCH)))), Map.of());

        // The official name, both given names; the gender in the case the lists keep.
        assertThat(finds.members()).containsExactly(new FoundMember("S123456789", "pat-001", "Sample A", "Placeholder-One", "1980-01-31",
                "female", null, null, null, null, null, "Order sign with prefetch"));
        // The context's user is the requester (D-322).
        assertThat(finds.providers()).containsExactly(new FoundProvider("1234567893", "prac-001", "Sample", "Placeholder-Provider", null,
                null, null, "Order sign with prefetch", "Order sign with prefetch", null));
        assertThat(finds.codes()).hasSize(2);
        assertThat(finds.notUsed()).isEmpty();
    }

    static final String PAS_BUNDLE = """
            {"resourceType": "Bundle", "type": "collection", "entry": [
              {"fullUrl": "urn:uuid:11111111-1111-1111-1111-111111111111", "resource": {"resourceType": "Claim", "id": "claim-1",
                 "patient": {"reference": "urn:uuid:22222222-2222-2222-2222-222222222222"},
                 "item": [
                   {"sequence": 1, "productOrService": {"coding": [{"system": "http://www.ama-assn.org/go/cpt", "code": "97110",
                       "display": "Therapeutic exercise"}]},
                    "detail": [{"sequence": 1, "productOrService": {"coding": [{"system": "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                       "code": "A4604", "display": "Tubing"}]}}]},
                   {"sequence": 2, "productOrService": {"coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "1000001"}]}}]}},
              {"fullUrl": "urn:uuid:22222222-2222-2222-2222-222222222222", "resource": {"resourceType": "Patient", "id": "pat-002",
                 "identifier": [{"system": "{{memberIdSystem}}", "value": "S123456788"}],
                 "name": [{"family": "Placeholder-Two", "given": ["Sample"]}], "birthDate": "1975-06", "gender": "male"}},
              {"fullUrl": "urn:uuid:33333333-3333-3333-3333-333333333333", "resource": {"resourceType": "Coverage", "id": "cov-1",
                 "status": "active", "subscriberId": "S123456788",
                 "beneficiary": {"reference": "urn:uuid:22222222-2222-2222-2222-222222222222"},
                 "period": {"start": "2026-01-01", "end": "2026-12-31T23:59:59Z"},
                 "class": [{"type": {"coding": [{"code": "group"}]}, "value": "G-1"},
                           {"type": {"coding": [{"code": "plan"}]}, "value": "P-1", "name": "Sample plan"}]}},
              {"fullUrl": "urn:uuid:44444444-4444-4444-4444-444444444444", "resource": {"resourceType": "Practitioner", "id": "prac-002",
                 "identifier": [{"system": "http://hl7.org/fhir/sid/us-npi", "value": "1234567890"}],
                 "name": [{"family": "Placeholder-Practitioner", "given": ["Sample"]}]}},
              {"resource": {"resourceType": "MedicationRequest", "id": "med-1",
                 "medicationCodeableConcept": {"coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "1000002"}]}}}]}""";

    @Test
    void aPasSubmitBundleGivesThePatientWithItsCoverageThePractitionerAndTheClaimCodesAndSaysWhatItLeftOut() {
        Finds finds = read(collection(List.of(new Req("Claim submit", "POST", "{{pasBase}}/Claim/$submit", PAS_BUNDLE))),
                Map.of("pasBase", "https://sandbox.example.test/fhir", "memberIdSystem", "https://payer.example.test/member-id"));

        // The Coverage names its patient by fullUrl; its period end is a dateTime, its day taken; the plan class gives the plan.
        assertThat(finds.members()).containsExactly(new FoundMember("S123456788", "pat-002", "Sample", "Placeholder-Two", null, "male",
                "S123456788", "active", "2026-01-01", "2026-12-31", "Sample plan", "Claim submit"));
        // The NPI fails its check digit: the practitioner is kept by its id, without the NPI.
        assertThat(finds.providers()).containsExactly(new FoundProvider(null, "prac-002", "Sample", "Placeholder-Practitioner",
                "Claim submit"));
        // A claim item's details are read; a medication, on a claim item or ordered, is not a procedure code.
        assertThat(finds.codes()).extracting(FoundCode::code, FoundCode::systemShort, FoundCode::description)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("97110", "CPT", "Therapeutic exercise"),
                        org.assertj.core.groups.Tuple.tuple("A4604", "HCPCS", "Tubing"));
        assertThat(finds.notUsed()).containsExactly(
                "\"Claim submit\": a medication code (RxNorm) is not a procedure code, so it was left out.",
                "\"Claim submit\": the birth date of a Patient is not a whole date (yyyy-MM-dd), so it was left out.",
                "\"Claim submit\": the NPI of a Practitioner was left out, because its last digit does not match the other nine.");
    }

    static String patient(String id, String identifiers) {
        return "{\"resourceType\": \"Patient\", \"id\": \"" + id + "\", \"identifier\": [" + identifiers + "]}";
    }

    @Test
    void theMemberIdIsTheSystemsThenTheOneTypedMbThenTheOnlyOneAndNeverAGuess() {
        String one = patient("pat-001", "{\"system\": \"https://other.example.test/id\", \"value\": \"S123456789\"}");
        String typed = patient("pat-002", "{\"value\": \"MR-0002\"}, {\"type\": {\"coding\": [{\"code\": \"MB\"}]}, \"value\": \"S123456788\"}");
        String twoUntyped = patient("pat-003", "{\"system\": \"https://a.example.test\", \"value\": \"S123456787\"}, "
                + "{\"system\": \"https://b.example.test\", \"value\": \"MR-0003\"}");
        List<Req> requests = List.of(new Req("One", "POST", "https://x.test/a", one), new Req("Typed", "POST", "https://x.test/b", typed),
                new Req("Two untyped", "POST", "https://x.test/c", twoUntyped));

        Finds noSystem = read(collection(requests), Map.of());
        assertThat(noSystem.members()).extracting(FoundMember::memberId, FoundMember::vendorPatientId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("S123456789", "pat-001"),
                        org.assertj.core.groups.Tuple.tuple("S123456788", "pat-002"),
                        org.assertj.core.groups.Tuple.tuple(null, "pat-003"));
        assertThat(noSystem.notUsed()).containsExactly("\"Two untyped\": a Patient has 2 identifiers and none is typed MB (the environment "
                + "names no member ID system), so its member ID was not taken.");

        // The environment's member ID system tells the two apart.
        Finds withSystem = read(collection(requests), Map.of("memberIdSystem", "https://a.example.test"));
        assertThat(withSystem.members()).extracting(FoundMember::memberId).containsExactly("S123456789", "S123456788", "S123456787");
        assertThat(withSystem.notUsed()).isEmpty();
    }

    @Test
    void aPlaceholderNothingDefinesGivesNoEntryAndOneLineAndADefinedOneIsFilled() {
        List<Req> requests = List.of(
                new Req("Defined", "POST", "https://x.test/a", orderSign("{{patientId}}", "Practitioner/prac-001", null)),
                new Req("Undefined", "POST", "https://x.test/b", orderSign("{{otherPatient}}", "Practitioner/{{otherPractitioner}}", null)));
        Finds finds = read(collection("https://schema.getpostman.com/json/collection/v2.1.0/collection.json", requests,
                Map.of("patientId", "pat-from-collection")), Map.of());

        assertThat(finds.members()).extracting(FoundMember::vendorPatientId).containsExactly("pat-from-collection");
        assertThat(finds.providers()).extracting(FoundProvider::vendorPractitionerId).containsExactly("prac-001");
        assertThat(finds.notUsed()).containsExactly("\"Undefined\": {{otherPractitioner}} and {{otherPatient}} have no value in the "
                + "environment or the collection, so nothing was read from its body.");
    }

    @Test
    void theSamePatientInThreeRequestsIsOneEntryWithWhatEachStates() {
        List<Req> requests = List.of(
                new Req("First", "POST", "https://x.test/a", orderSign("pat-001", "Practitioner/prac-001", null)),
                new Req("Second", "POST", "https://x.test/b", "{\"resourceType\": \"Patient\", \"id\": \"pat-001\", \"birthDate\": "
                        + "\"1980-01-31\", \"identifier\": [{\"value\": \"S123456789\"}]}"),
                new Req("Third", "GET", "https://x.test/fhir/Patient/pat-001", null),
                new Req("Fourth", "POST", "https://x.test/d", "{\"resourceType\": \"Patient\", \"identifier\": [{\"value\": \"S123 456 789\"}], "
                        + "\"birthDate\": \"1981-01-31\", \"gender\": \"female\"}"));
        Finds finds = read(collection(requests), Map.of());

        assertThat(finds.members()).containsExactly(new FoundMember("S123456789", "pat-001", null, null, "1980-01-31", "female", null, null,
                null, null, null, "First"));
        assertThat(finds.notUsed()).containsExactly("\"Fourth\": states another date of birth for a patient that \"First\" states too; the "
                + "one from \"First\" is kept.");
    }

    @Test
    void aBodyThatIsNotJsonAndABodyNestedDeeperThanTheParserReadsAreEachOneLineAndADeepBodyIsRead() {
        String deep = "[".repeat(450) + patient("pat-deep", "{\"value\": \"S123456789\"}") + "]".repeat(450);
        String tooDeep = "[".repeat(1200) + "]".repeat(1200);
        Finds finds = read(collection(List.of(new Req("Text", "POST", "https://x.test/a", "member=S123456789"),
                new Req("Deep", "POST", "https://x.test/b", deep), new Req("Too deep", "POST", "https://x.test/c", tooDeep))), Map.of());

        assertThat(finds.members()).extracting(FoundMember::vendorPatientId).containsExactly("pat-deep");
        assertThat(finds.notUsed()).containsExactly("\"Text\": its body is not JSON, so nothing was read from it.",
                "\"Too deep\": its body is not JSON this tool reads (it nests too deep), so nothing was read from it.");
    }

    @Test
    void aDeepBodyIsWalkedWithoutRecursionInAThreadWithASmallStack() throws Exception {
        String deep = "{\"a\":".repeat(480) + patient("pat-deep", "") + "}".repeat(480);
        String text = collection(List.of(new Req("Deep", "POST", "https://x.test/b", deep)));
        Finds[] found = new Finds[1];
        Throwable[] failed = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                found[0] = read(text, Map.of());
            } catch (Throwable e) {
                failed[0] = e;
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        assertThat(failed[0]).isNull();
        assertThat(found[0].members()).extracting(FoundMember::vendorPatientId).containsExactly("pat-deep");
    }

    @Test
    void contextIdsThatAreNotIdsAndAUserThatIsNoPractitionerAreSaidAndAPatientNamedOnlyInTheUrlIsKept() {
        String body = "{\"hook\": \"order-sign\", \"context\": {\"userId\": \"PractitionerRole/role-1\", \"patientId\": \"pat 001\"}}";
        Finds finds = read(collection(List.of(new Req("Odd context", "POST", "https://x.test/a", body),
                new Req("Read patient", "GET", "{{fhir}}/Patient/{{patientId}}?_format=json", null),
                new Req("Match", "POST", "{{fhir}}/Patient/$match", null))), Map.of("fhir", "https://x.test/fhir", "patientId", "pat-009"));

        assertThat(finds.members()).extracting(FoundMember::vendorPatientId, FoundMember::from)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pat-009", "Read patient"));
        assertThat(finds.providers()).isEmpty();
        assertThat(finds.notUsed()).containsExactly(
                "\"Odd context\": its context.patientId is not an id a FHIR server gives (letters, digits, - and . only), so it was left out.",
                "\"Odd context\": its context.userId names no Practitioner (it does not read Practitioner/<id>), so it was left out.");
    }

    /**
     * A collection in Postman's older format v2.0 (D-307): its url written as text, a header
     * written as text, a helper written as an object and a request written as a bare url. The
     * reading was built when the parser refused such a file whole; it now reads what the parser
     * gives, as for v2.1.
     */
    @Test
    void aCollectionInFormatV20IsReadAsV21Is() {
        String text = """
                {"info": {"name": "Older sample", "schema": "https://schema.getpostman.com/json/collection/v2.0.0/collection.json"},
                 "auth": {"type": "bearer", "bearer": {"token": "{{accessToken}}"}},
                 "item": [
                   {"name": "Order sign", "request": {"method": "POST", "url": "{{crdBase}}/cds-services/order-sign-crd",
                     "header": "Content-Type: application/json\\nAccept: application/json",
                     "body": {"mode": "raw", "raw": %s}}},
                   {"name": "Read patient", "request": "{{fhirBase}}/Patient/pat-002"}]}""".formatted(
                quote(orderSign("{{patientId}}", "Practitioner/prac-001", PATIENT_PREFETCH)));
        Finds finds = read(text, Map.of("crdBase", "https://sandbox.example.test", "fhirBase", "https://fhir.example.test",
                "patientId", "pat-001"));

        assertThat(finds.members()).extracting(FoundMember::vendorPatientId, FoundMember::memberId, FoundMember::from)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pat-001", "S123456789", "Order sign"),
                        org.assertj.core.groups.Tuple.tuple("pat-002", null, "Read patient"));
        assertThat(finds.providers()).extracting(FoundProvider::npi, FoundProvider::vendorPractitionerId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1234567893", "prac-001"));
        assertThat(finds.codes()).extracting(FoundCode::code).containsExactly("97110", "E0601");
        assertThat(finds.notUsed()).isEmpty();
    }

    @Test
    void savedExampleResponsesAreNotRead() {
        String text = """
                {"info": {"name": "Examples", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [{"name": "With example", "request": {"method": "GET", "url": "https://x.test/cds-services"},
                   "response": [{"name": "OK", "code": 200, "body": "{\\"resourceType\\": \\"Patient\\", \\"id\\": \\"pat-example\\"}"}]}]}""";
        Finds finds = read(text, Map.of());
        assertThat(finds.empty()).isTrue();
        assertThat(finds.notUsed()).isEmpty();
    }
}
