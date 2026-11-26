package org.p32h.interop.epa.pave.service.lists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.lists.CollectionFindsTest.collection;
import static org.p32h.interop.epa.pave.service.lists.CollectionFindsTest.read;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.lists.CollectionFindsTest.Req;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Finds;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundCode;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundProvider;

/**
 * The diagnosis codes and the servicing providers a collection's requests state (D-322): the
 * codings of an order's reasonCode and of a claim's diagnosis, and the resource an order names as
 * its performer or a claim's care team as its provider, found where the request carries it. Every
 * collection here is written for the test, with placeholders only, and every expectation says what
 * was found where and what was not used and why. No sentence of the reading quotes a diagnosis code.
 */
class DiagnosisAndPerformerFindsTest {

    private static final String ICD = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";
    private static final String URL = "https://sandbox.example.test/cds-services/order-sign-crd";

    /** An order-sign request whose draft order carries the reasonCode and performers given, and a prefetch Bundle of the resources given. */
    private static String orderSign(String reasonCodes, String performers, String prefetchEntries) {
        return "{\"hook\": \"order-sign\", \"context\": {\"userId\": \"Practitioner/prac-001\", \"patientId\": \"pat-001\", \"draftOrders\": "
                + "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": \"ServiceRequest\", "
                + "\"id\": \"sr-1\", \"status\": \"draft\", \"code\": {\"coding\": [{\"system\": \"http://www.ama-assn.org/go/cpt\", "
                + "\"code\": \"95810\"}]}" + (reasonCodes == null ? "" : ", \"reasonCode\": " + reasonCodes)
                + (performers == null ? "" : ", \"performer\": " + performers) + "}}]}}, \"prefetch\": {\"providers\": {\"resourceType\": "
                + "\"Bundle\", \"type\": \"searchset\", \"entry\": [" + (prefetchEntries == null ? "" : prefetchEntries) + "]}}}";
    }

    private static String entry(String resource) {
        return "{\"resource\": " + resource + "}";
    }

    private static final String FACILITY = "{\"resourceType\": \"Organization\", \"id\": \"org-facility-001\", \"name\": \"Placeholder Sleep "
            + "Laboratory\", \"identifier\": [{\"system\": \"" + NPI + "\", \"value\": \"9999999995\"}], \"telecom\": [{\"system\": "
            + "\"phone\", \"value\": \"617-555-0110\"}, {\"system\": \"fax\", \"value\": \"617-555-0111\"}]}";

    private static final String SERVICING = "{\"resourceType\": \"Practitioner\", \"id\": \"prac-002\", \"identifier\": [{\"system\": \""
            + NPI + "\", \"value\": \"9999999995\"}], \"name\": [{\"family\": \"Placeholder-Servicing\", \"given\": [\"Sample\"]}]}";

    private static Finds one(String name, String body) {
        return read(collection(List.of(new Req(name, "POST", URL, body))), Map.of());
    }

    @Test
    void aReasonCodeGivesEachCodingWithItsSystemAndDescriptionAndATextAloneGivesNothing() {
        String reasons = "[{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"G47.33\", \"display\": \"Obstructive sleep apnea\"}]},"
                + "{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"E11.9\"}, {\"code\": \"R06.83\"}], \"text\": \"Placeholder text\"},"
                + "{\"text\": \"A diagnosis in words\"}, {\"coding\": [{\"system\": \"" + ICD + "\", \"display\": \"No code\"}]}]";
        Finds finds = one("Order sign", orderSign(reasons, null, null));

        assertThat(finds.diagnoses()).containsExactly(
                new FoundCode("G47.33", ICD, "ICD-10-CM", "Obstructive sleep apnea", "Order sign"),
                new FoundCode("E11.9", ICD, "ICD-10-CM", "Placeholder text", "Order sign"),
                new FoundCode("R06.83", null, null, "Placeholder text", "Order sign"));
        // The procedure code of the order stays a procedure code; a diagnosis is never one.
        assertThat(finds.codes()).extracting(FoundCode::code).containsExactly("95810");
        assertThat(finds.notUsed()).containsExactly(
                "\"Order sign\": a diagnosis is stated with no code, so it was left out.",
                "\"Order sign\": a diagnosis is stated with no code, so it was left out.");
        assertThat(String.join(" ", finds.notUsed())).doesNotContain("G47").doesNotContain("A diagnosis in words");
    }

    @Test
    void aPlaceholderDefinedFillsTheCodeAndOneNothingDefinesReadsNothingOfTheBody() {
        String reasons = "[{\"coding\": [{\"system\": \"{{diagnosisSystem}}\", \"code\": \"{{diagnosisCode}}\"}]}]";
        Finds defined = read(collection(List.of(new Req("Order sign", "POST", URL, orderSign(reasons, null, null)))),
                Map.of("diagnosisSystem", ICD, "diagnosisCode", "G47.33"));
        assertThat(defined.diagnoses()).containsExactly(new FoundCode("G47.33", ICD, "ICD-10-CM", null, "Order sign"));

        Finds undefined = read(collection(List.of(new Req("Order sign", "POST", URL, orderSign(reasons, null, null)))),
                Map.of("diagnosisSystem", ICD));
        assertThat(undefined.diagnoses()).isEmpty();
        assertThat(undefined.providers()).isEmpty();
        assertThat(undefined.notUsed()).containsExactly(
                "\"Order sign\": {{diagnosisCode}} has no value in the environment or the collection, so nothing was read from its body.");
    }

    @Test
    void aClaimsDiagnosisAndCareTeamAreReadAndTheSameCodeInThreeRequestsIsOneEntry() {
        String claim = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"fullUrl\": \"urn:uuid:claim-1\", "
                + "\"resource\": {\"resourceType\": \"Claim\", \"id\": \"claim-1\", \"provider\": {\"reference\": \"Practitioner/prac-001\"}, "
                + "\"careTeam\": [{\"sequence\": 1, \"provider\": {\"reference\": \"urn:uuid:org-1\"}}], "
                + "\"diagnosis\": [{\"sequence\": 1, \"diagnosisCodeableConcept\": {\"coding\": [{\"system\": \"" + ICD + "\", "
                + "\"code\": \"G47.33\"}]}}]}}, {\"fullUrl\": \"urn:uuid:org-1\", \"resource\": " + FACILITY + "}, "
                + "{\"resource\": {\"resourceType\": \"Practitioner\", \"id\": \"prac-001\", \"identifier\": [{\"system\": \"" + NPI
                + "\", \"value\": \"1234567893\"}]}}]}";
        String reasons = "[{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"G47.33\", \"display\": \"Obstructive sleep apnea\"}]}]";
        Finds finds = read(collection(List.of(new Req("Submit", "POST", "https://sandbox.example.test/Claim/$submit", claim),
                new Req("Order sign", "POST", URL, orderSign(reasons, null, null)),
                new Req("Order sign again", "POST", URL, orderSign(reasons, null, null)))), Map.of());

        // One entry, from the first request; the description a later request states is taken where the first states none.
        assertThat(finds.diagnoses()).containsExactly(new FoundCode("G47.33", ICD, "ICD-10-CM", "Obstructive sleep apnea", "Submit"));
        assertThat(finds.providers()).containsExactly(
                new FoundProvider("1234567893", "prac-001", null, null, null, null, null, "Submit", "Submit", null),
                new FoundProvider("9999999995", null, null, null, "Placeholder Sleep Laboratory", "617-555-0110", "617-555-0111", "Submit",
                        null, "Submit"));
        assertThat(finds.notUsed()).isEmpty();
    }

    @Test
    void aPerformerThatIsAPractitionerIsReadAsAnyPractitionerAndLabelledAPerformer() {
        Finds finds = one("Order sign", orderSign(null, "[{\"reference\": \"Practitioner/prac-002\"}]", entry(SERVICING)));
        assertThat(finds.providers()).containsExactly(
                new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign", "Order sign", null),
                new FoundProvider("9999999995", "prac-002", "Sample", "Placeholder-Servicing", null, null, null, "Order sign", null,
                        "Order sign"));
        assertThat(finds.notUsed()).isEmpty();
    }

    @Test
    void aPerformerThatIsAPractitionerRoleGivesItsPractitionerAndItsOrganization() {
        String role = "{\"resourceType\": \"PractitionerRole\", \"id\": \"role-1\", \"practitioner\": {\"reference\": \"Practitioner/prac-002\"}, "
                + "\"organization\": {\"reference\": \"Organization/org-facility-001\"}}";
        String facility = FACILITY.replace("9999999995", "1234567893");
        Finds with = one("Order sign", orderSign(null, "[{\"reference\": \"PractitionerRole/role-1\"}]",
                entry(role) + "," + entry(SERVICING) + "," + entry(facility)));
        // The practitioner carries the name of the organization the role names; the facility is an entry of its own.
        assertThat(with.providers()).containsExactly(
                new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign", "Order sign", null),
                new FoundProvider("9999999995", "prac-002", "Sample", "Placeholder-Servicing", "Placeholder Sleep Laboratory", null, null,
                        "Order sign", null, "Order sign"),
                new FoundProvider("1234567893", null, null, null, "Placeholder Sleep Laboratory", "617-555-0110", "617-555-0111",
                        "Order sign", null, "Order sign"));
        assertThat(with.notUsed()).isEmpty();

        Finds without = one("Order sign", orderSign(null, "[{\"reference\": \"PractitionerRole/role-1\"}]", entry(role) + "," + entry(SERVICING)));
        assertThat(without.providers()).extracting(FoundProvider::vendorPractitionerId, FoundProvider::organization, FoundProvider::performerIn)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("prac-001", null, null),
                        org.assertj.core.groups.Tuple.tuple("prac-002", null, "Order sign"));
        assertThat(without.notUsed()).containsExactly("\"Order sign\": the Organization of a PractitionerRole that is the performer of an "
                + "order is not in the request, and a saved provider has no place for an organization's id, so it was not read.");

        String separate = "{\"resourceType\": \"PractitionerRole\", \"id\": \"role-2\", \"practitioner\": {\"reference\": \"Practitioner/prac-003\"}, "
                + "\"organization\": {\"reference\": \"Organization/org-facility-001\"}}";
        Finds apart = one("Order sign", orderSign(null, "[{\"reference\": \"PractitionerRole/role-2\"}]", entry(separate) + ","
                + entry(FACILITY.replace("9999999995", "1234567893"))));
        // The role's Practitioner is not carried: its id alone, as a context.userId gives one.
        assertThat(apart.providers()).containsExactly(
                new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign", "Order sign", null),
                new FoundProvider(null, "prac-003", null, null, null, null, null, "Order sign", null, "Order sign"),
                new FoundProvider("1234567893", null, null, null, "Placeholder Sleep Laboratory", "617-555-0110", "617-555-0111",
                        "Order sign", null, "Order sign"));
    }

    @Test
    void aPerformerThatIsAnOrganizationGivesItsNameNpiPhoneAndFaxAndTheNpiOnlyWhenItsCheckDigitHolds() {
        Finds facility = one("Order sign", orderSign(null, "[{\"reference\": \"Organization/org-facility-001\"}]", entry(FACILITY)));
        assertThat(facility.providers()).contains(new FoundProvider("9999999995", null, null, null, "Placeholder Sleep Laboratory",
                "617-555-0110", "617-555-0111", "Order sign", null, "Order sign"));

        Finds wrongDigit = one("Order sign", orderSign(null, "[{\"reference\": \"Organization/org-facility-001\"}]",
                entry(FACILITY.replace("9999999995", "9999999990"))));
        assertThat(wrongDigit.providers()).contains(new FoundProvider(null, null, null, null, "Placeholder Sleep Laboratory", "617-555-0110",
                "617-555-0111", "Order sign", null, "Order sign"));
        assertThat(wrongDigit.notUsed()).containsExactly(
                "\"Order sign\": the NPI of an Organization was left out, because its last digit does not match the other nine.");

        Finds nameOnly = one("Order sign", orderSign(null, "[{\"reference\": \"Organization/org-2\"}]",
                entry("{\"resourceType\": \"Organization\", \"id\": \"org-2\", \"name\": \"Placeholder Imaging Center\"}")));
        assertThat(nameOnly.providers()).contains(new FoundProvider(null, null, null, null, "Placeholder Imaging Center", null, null,
                "Order sign", null, "Order sign"));
        assertThat(nameOnly.notUsed()).isEmpty();

        Finds nothing = one("Order sign", orderSign(null, "[{\"reference\": \"Organization/org-3\"}]",
                entry("{\"resourceType\": \"Organization\", \"id\": \"org-3\", \"telecom\": [{\"system\": \"phone\", \"value\": \"617-555-0112\"}]}")));
        assertThat(nothing.providers()).extracting(FoundProvider::vendorPractitionerId).containsExactly("prac-001");
        assertThat(nothing.notUsed()).containsExactly("\"Order sign\": an Organization states neither an NPI nor a name, so it cannot be saved.");
    }

    @Test
    void aContainedPerformerIsFoundByItsHashId() {
        String body = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"resource\": {\"resourceType\": "
                + "\"ServiceRequest\", \"id\": \"sr-1\", \"contained\": [" + FACILITY.replace("\"id\": \"org-facility-001\"", "\"id\": \"lab\"")
                + "], \"performer\": [{\"reference\": \"#lab\"}]}}]}";
        Finds finds = one("Order", body);
        assertThat(finds.providers()).containsExactly(new FoundProvider("9999999995", null, null, null, "Placeholder Sleep Laboratory",
                "617-555-0110", "617-555-0111", "Order", null, "Order"));
    }

    @Test
    void aReferenceToNothingTheRequestCarriesGivesAPractitionerIdAloneAndNothingForAnOrganization() {
        Finds finds = one("Order sign", orderSign(null, "[{\"reference\": \"Practitioner/prac-009\"}, {\"reference\": \"Organization/org-9\"}, "
                + "{\"reference\": \"PractitionerRole/role-9\"}, {\"reference\": \"Practitioner/not an id\"}, {\"display\": \"Placeholder\"}]", null));
        assertThat(finds.providers()).containsExactly(
                new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign", "Order sign", null),
                new FoundProvider(null, "prac-009", null, null, null, null, null, "Order sign", null, "Order sign"));
        assertThat(finds.notUsed()).containsExactly(
                "\"Order sign\": the performer of an order names an Organization the request does not carry, and a saved provider has no place "
                        + "for an organization's id, so it was not read.",
                "\"Order sign\": the performer of an order names a resource the request does not carry, so it was not read.",
                "\"Order sign\": the performer of an order names a Practitioner by an id a FHIR server does not give (letters, digits, - and . "
                        + "only), so it was left out.",
                "\"Order sign\": the performer of an order names no resource by reference, so it was not read.");
    }

    @Test
    void aProviderThatIsRequesterInOneRequestAndPerformerInAnotherIsOneEntryThatSaysBoth() {
        Finds finds = read(collection(List.of(new Req("Order sign", "POST", URL, orderSign(null, null, null)),
                new Req("Order for another", "POST", URL, orderSign(null, "[{\"reference\": \"Practitioner/prac-001\"}]", null)
                        .replace("Practitioner/prac-001\", \"patientId", "Practitioner/prac-005\", \"patientId")))), Map.of());
        assertThat(finds.providers()).containsExactly(
                new FoundProvider(null, "prac-001", null, null, null, null, null, "Order sign", "Order sign", "Order for another"),
                new FoundProvider(null, "prac-005", null, null, null, null, null, "Order for another", "Order for another", null));
    }

    @Test
    void aSampleOfAnErrorGivesNoDiagnosisAndNoPerformerAndSaysSo() {
        String reasons = "[{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"G47.33\"}]}]";
        Finds finds = one("Order sign error missing payer", orderSign(reasons, "[{\"reference\": \"Organization/org-facility-001\"}]",
                entry(FACILITY)));
        assertThat(finds.diagnoses()).isEmpty();
        assertThat(finds.providers()).extracting(FoundProvider::vendorPractitionerId).containsExactly("prac-001");
        assertThat(finds.notUsed()).containsExactly(
                "\"Order sign error missing payer\" is a sample of an error, so no diagnosis code and no performer was taken from it.");
    }

    @Test
    void aBodyThatIsNotJsonADeepBodyAndAnOlderFormatEndInAResult() {
        Finds notJson = one("Order sign", "{\"hook\": \"order-sign\", \"reasonCode\": ");
        assertThat(notJson.diagnoses()).isEmpty();
        assertThat(notJson.notUsed()).containsExactly("\"Order sign\": its body is not JSON, so nothing was read from it.");

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            deep.append("{\"a\":[");
        }
        deep.append("{\"resourceType\": \"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"code\": \"G47.33\"}]}], \"performer\": "
                + "[{\"reference\": \"Practitioner/prac-002\"}]}");
        for (int i = 0; i < 5000; i++) {
            deep.append("]}");
        }
        Finds deepFinds = one("Deep", deep.toString());
        assertThat(deepFinds.notUsed()).hasSize(1);
        assertThat(deepFinds.diagnoses()).isEmpty();

        String reasons = "[{\"coding\": [{\"system\": \"" + ICD + "\", \"code\": \"G47.33\"}]}]";
        String v20 = collection("https://schema.getpostman.com/json/collection/v2.0.0/collection.json",
                List.of(new Req("Order sign", "POST", URL, orderSign(reasons, "[{\"reference\": \"Organization/org-facility-001\"}]",
                        entry(FACILITY)))), Map.of());
        Finds older = read(v20, Map.of());
        assertThat(older.diagnoses()).extracting(FoundCode::code).containsExactly("G47.33");
        assertThat(older.providers()).extracting(FoundProvider::organization).containsExactly(null, "Placeholder Sleep Laboratory");
    }
}
