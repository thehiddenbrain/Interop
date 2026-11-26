package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import tools.jackson.databind.node.ObjectNode;

/**
 * What one request states of the FHIR server and of the payer (D-320), read where PAVE's own
 * builder writes the same values, on bodies written for the test with placeholders only; and the
 * value payerReference written on a Coverage's payor.
 */
class RequestValuesTest {

    private static final String REQUEST = "CRD / Coverage check";

    private static final VariableResolver RESOLVER = new VariableResolver(name -> null,
            Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC), () -> "00000000-0000-4000-8000-000000000001");

    /** A CDS Hooks body whose prefetch holds the Coverage alone under "coverage", with this payor array and these other keys. */
    private static String hook(String payors, String more) {
        return "{\"hook\": \"order-sign\", \"fhirServer\": \"https://ehr.example.org/fhir\", \"context\": {}, \"prefetch\": {"
                + "\"patient\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\", \"identifier\": [{\"value\": \"S123456789\"}]}, "
                + "\"coverage\": {\"resourceType\": \"Coverage\", \"id\": \"cov-001\", \"subscriberId\": \"S123456789\", "
                + "\"identifier\": [{\"system\": \"urn:example:member\", \"value\": \"S123456789\"}], "
                + "\"beneficiary\": {\"reference\": \"Patient/pat-001\"}" + (payors == null ? "" : ", \"payor\": " + payors) + "}"
                + (more == null ? "" : ", " + more) + "}}";
    }

    private static RequestValues.Stated read(String body) {
        return RequestValues.read(REQUEST, "Coverage check", body);
    }

    @Test
    void aPayorWithADisplayOnlyGivesThePayerName() {
        RequestValues.Stated stated = read(hook("[{\"display\": \"Placeholder Payer\"}]", null));
        assertThat(stated.values()).containsExactly(Map.entry("fhirServer", "https://ehr.example.org/fhir"),
                Map.entry("payerName", "Placeholder Payer"));
        assertThat(stated.payer()).isTrue();
    }

    @Test
    void aPayorWithAnIdentifierOnlyGivesTheIdentifierAndItsSystem() {
        RequestValues.Stated stated = read(hook("[{\"identifier\": {\"system\": \"urn:example:payer-ids\", \"value\": \"P32H-PAYER-1\"}}]",
                null));
        assertThat(stated.values()).containsEntry("payerIdentifier", "P32H-PAYER-1")
                .containsEntry("payerIdentifierSystem", "urn:example:payer-ids").doesNotContainKey("payerName");
    }

    @Test
    void aPayorWithBothGivesAllThree() {
        RequestValues.Stated stated = read(hook("[{\"identifier\": {\"system\": \"urn:example:payer-ids\", \"value\": \"P32H-PAYER-1\"}, "
                + "\"display\": \"Placeholder Payer\"}]", null));
        assertThat(stated.values()).containsKeys("payerName", "payerIdentifier", "payerIdentifierSystem").doesNotContainKey("payerReference");
    }

    @Test
    void aReferenceToAContainedOrganizationTakesItsNameAndIdentifierAndNotTheReference() {
        String body = hook("[{\"reference\": \"#payer\"}]", null).replace("\"id\": \"cov-001\",",
                "\"id\": \"cov-001\", \"contained\": [{\"resourceType\": \"Organization\", \"id\": \"payer\", \"name\": \"Placeholder Payer\", "
                        + "\"identifier\": [{\"system\": \"urn:example:payer-ids\", \"value\": \"P32H-PAYER-1\"}]}],");
        RequestValues.Stated stated = read(body);
        assertThat(stated.values()).containsEntry("payerName", "Placeholder Payer").containsEntry("payerIdentifier", "P32H-PAYER-1")
                .containsEntry("payerIdentifierSystem", "urn:example:payer-ids").doesNotContainKey("payerReference");
        assertThat(stated.lines("payerReference")).containsExactly("The collection's request \"CRD / Coverage check\" refers its payer to a "
                + "resource it carries itself. PAVE does not send that resource, so the reference is not taken.");
    }

    @Test
    void aReferenceToAnOrganizationElsewhereInThePrefetchTakesItsNameByTypeAndIdAndByFullUrl() {
        String organization = "\"payer\": {\"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [{\"fullUrl\": "
                + "\"https://payer.example.org/fhir/Organization/org-payer-001\", \"resource\": {\"resourceType\": \"Organization\", "
                + "\"id\": \"org-payer-001\", \"name\": \"Placeholder Payer\"}}]}";
        RequestValues.Stated byId = read(hook("[{\"reference\": \"Organization/org-payer-001\"}]", organization));
        assertThat(byId.values()).containsEntry("payerName", "Placeholder Payer").doesNotContainKey("payerReference");
        RequestValues.Stated byFullUrl = read(hook("[{\"reference\": \"https://payer.example.org/fhir/Organization/org-payer-001\"}]",
                organization));
        assertThat(byFullUrl.values()).containsEntry("payerName", "Placeholder Payer").doesNotContainKey("payerReference");
    }

    @Test
    void aReferenceToNothingTheRequestCarriesIsTakenAsWritten() {
        RequestValues.Stated stated = read(hook("[{\"reference\": \"Organization/org-payer-001\"}]", null));
        assertThat(stated.values()).containsEntry("payerReference", "Organization/org-payer-001").doesNotContainKey("payerName");
    }

    @Test
    void twoPayorsOrNoneGiveNothingAndOneLineSaysSo() {
        RequestValues.Stated two = read(hook("[{\"display\": \"Placeholder Payer\"}, {\"display\": \"Placeholder Payer Two\"}]", null));
        assertThat(two.statesPayerValue()).isFalse();
        assertThat(two.payer()).isTrue();
        assertThat(two.lines("payerName")).containsExactly("The collection's request \"CRD / Coverage check\" names 2 payers on its "
                + "Coverage, so none is taken: choose one and type it.");
        RequestValues.Stated none = read(hook(null, null));
        assertThat(none.statesPayerValue()).isFalse();
        assertThat(none.payer()).isFalse();
        assertThat(none.lines("payerName")).containsExactly("The collection's request \"CRD / Coverage check\" names no payer on its "
                + "Coverage.");
    }

    @Test
    void theCoverageAsTheFirstEntryOfABundleUnderAKey() {
        String body = "{\"hook\": \"order-sign\", \"prefetch\": {\"coverageBundle\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", "
                + "\"entry\": [{\"resource\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\"}}, {\"resource\": {\"resourceType\": "
                + "\"Coverage\", \"id\": \"cov-001\", \"payor\": [{\"display\": \"Placeholder Payer\"}]}}, {\"resource\": {\"resourceType\": "
                + "\"Coverage\", \"id\": \"cov-002\", \"payor\": [{\"display\": \"Placeholder Payer Two\"}]}}]}}}";
        assertThat(read(body).values()).containsExactly(Map.entry("payerName", "Placeholder Payer"));
    }

    @Test
    void aParametersBodyGivesThePayerOfItsFirstCoverage() {
        String body = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"coverage\", \"resource\": {\"resourceType\": "
                + "\"Coverage\", \"id\": \"cov-001\", \"payor\": [{\"reference\": \"Organization/org-payer-001\"}]}}, {\"name\": \"order\", "
                + "\"resource\": {\"resourceType\": \"Organization\", \"id\": \"org-payer-001\", \"name\": \"Placeholder Payer\", "
                + "\"identifier\": [{\"system\": \"urn:example:payer-ids\", \"value\": \"P32H-PAYER-1\"}]}}]}";
        RequestValues.Stated stated = read(body);
        assertThat(stated.values()).containsExactly(Map.entry("payerName", "Placeholder Payer"), Map.entry("payerIdentifier", "P32H-PAYER-1"),
                Map.entry("payerIdentifierSystem", "urn:example:payer-ids"));
    }

    @Test
    void aSubmitBundleGivesTheInsurerAsDecisionD308ReadsIt() {
        String body = "{\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": [{\"fullUrl\": \"urn:uuid:claim-1\", \"resource\": "
                + "{\"resourceType\": \"Claim\", \"id\": \"claim-1\", \"insurer\": {\"reference\": \"Organization/org-payer-001\"}, "
                + "\"patient\": {\"reference\": \"Patient/pat-001\"}}}, {\"resource\": {\"resourceType\": \"Organization\", \"id\": "
                + "\"org-payer-001\", \"name\": \"Placeholder Payer\", \"identifier\": [{\"system\": \"urn:example:payer-ids\", "
                + "\"value\": \"P32H-PAYER-1\"}]}}, {\"resource\": {\"resourceType\": \"Patient\", \"id\": \"pat-001\", \"identifier\": "
                + "[{\"value\": \"S123456789\"}]}}]}";
        RequestValues.Stated stated = RequestValues.read("PAS / Submit", "Submit", body);
        assertThat(stated.values()).containsExactly(Map.entry("payerName", "Placeholder Payer"), Map.entry("payerIdentifier", "P32H-PAYER-1"),
                Map.entry("payerIdentifierSystem", "urn:example:payer-ids"));
    }

    @Test
    void nothingOfTheMemberIsReadAndNoValueOfItIsInAnyLine() {
        RequestValues.Stated stated = read(hook("[{\"display\": \"Placeholder Payer\"}, {\"display\": \"Other\"}]", null));
        assertThat(stated.values().values()).doesNotContain("S123456789", "pat-001", "urn:example:member");
        assertThat(String.join(" ", stated.lines().values().stream().flatMap(List::stream).toList())).doesNotContain("S123456789");
        assertThat(stated.toString()).doesNotContain("Placeholder Payer");
    }

    @Test
    void aRequestNamedAsASampleOfAnErrorGivesNothing() {
        RequestValues.Stated stated = RequestValues.read("CRD / Coverage check error", "Coverage check error",
                hook("[{\"display\": \"Placeholder Payer\"}]", null));
        assertThat(stated.values()).isEmpty();
        assertThat(stated.lines("payerName")).containsExactly("The collection's request \"CRD / Coverage check error\" is a sample of an "
                + "error, so nothing it states is taken.");
    }

    @Test
    void noBodyABodyThatIsNotJsonAndAnotherKindOfBodyGiveNothing() {
        assertThat(read(null)).isEqualTo(RequestValues.Stated.NONE);
        assertThat(read("  ")).isEqualTo(RequestValues.Stated.NONE);
        assertThat(read("this is not { JSON")).isEqualTo(RequestValues.Stated.NONE);
        assertThat(read("[1, 2]")).isEqualTo(RequestValues.Stated.NONE);
        assertThat(read("{\"resourceType\": \"Patient\"}").values()).isEmpty();
        assertThat(RequestValues.read(null, null, null)).isEqualTo(RequestValues.Stated.NONE);
    }

    // ---- what a call takes of it ----

    private static Function<String, Resolution> layers(Map<String, String> yml) {
        List<VariableLayer> layers = List.of(VariableLayer.applicationYml(yml));
        return text -> RESOLVER.resolve(text, layers);
    }

    @Test
    void aPlaceholderThatIsDefinedIsTakenResolvedAndOneThatIsNotIsSaidByName() {
        RequestValues.Stated stated = read(hook("[{\"display\": \"{{vendorPayerName}}\", \"identifier\": {\"value\": \"{{vendorPayerId}}\"}}]",
                null));
        RequestValues.ForCall call = RequestValues.forCall(stated, layers(Map.of("vendorPayerName", "Placeholder Payer")));
        assertThat(call.values()).containsEntry("payerName", "Placeholder Payer").doesNotContainKey("payerIdentifier");
        assertThat(call.lines("payerIdentifier")).containsExactly("The collection's request \"CRD / Coverage check\" writes Payer "
                + "identifier with {{vendorPayerId}}, which nothing defines, so it is not taken.");
    }

    @Test
    void aValueThatResolvesThroughASecretOrNamesItselfIsNotTaken() {
        RequestValues.Stated stated = read(hook("[{\"display\": \"{{clientSecret}}\", \"identifier\": {\"value\": \"{{payerIdentifier}}\"}}]",
                null));
        RequestValues.ForCall call = RequestValues.forCall(stated, layers(Map.of("clientSecret", "not-a-real-secret")));
        assertThat(call.values()).doesNotContainKeys("payerName", "payerIdentifier");
        assertThat(call.lines("payerName")).containsExactly("Payer name: The collection's request \"CRD / Coverage check\" states it "
                + "through a secret, so it is not taken.");
        assertThat(call.lines("payerIdentifier")).containsExactly("Payer identifier: The collection's request \"CRD / Coverage check\" "
                + "writes it as {{payerIdentifier}}, which these files do not define.");
        assertThat(String.join(" ", call.payerLines())).doesNotContain("not-a-real-secret");
    }

    @Test
    void aValueLongerThanAStoredValueMayBeIsNotTaken() {
        String longName = "P".repeat(StoredSettings.VALUE_MAX + 1);
        RequestValues.ForCall call = RequestValues.forCall(read(hook("[{\"display\": \"" + longName + "\"}]", null)), layers(Map.of()));
        assertThat(call.values()).doesNotContainKey("payerName");
        assertThat(call.lines("payerName")).singleElement().asString().contains("in more than " + StoredSettings.VALUE_MAX + " characters");
    }

    // ---- payerReference on the Coverage ----

    private static Function<String, ResolvedText> values(Map<String, String> values) {
        return template -> {
            String name = template.strip().replace("{{", "").replace("}}", "");
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    @Test
    void thePayorCarriesTheReferenceBesideTheIdentifierAndTheDisplay() {
        ObjectNode payor = CaseResources.payer(values(Map.of("payerReference", "Organization/org-payer-001", "payerIdentifier", "P32H-PAYER-1",
                "payerIdentifierSystem", "urn:example:payer-ids", "payerName", "Placeholder Payer")));
        assertThat(payor.toString()).isEqualTo("{\"reference\":\"Organization/org-payer-001\",\"identifier\":{\"system\":"
                + "\"urn:example:payer-ids\",\"value\":\"P32H-PAYER-1\"},\"display\":\"Placeholder Payer\"}");
        assertThat(CaseResources.payer(values(Map.of("payerReference", "Organization/org-payer-001"))).toString())
                .isEqualTo("{\"reference\":\"Organization/org-payer-001\"}");
        assertThat(CaseResources.payer(values(Map.of())).isEmpty()).isTrue();
    }

    @Test
    void payerReferenceIsAKnownValueOfTheEditForm() {
        assertThat(KnownValues.of("payerReference")).isNotNull();
        assertThat(KnownValues.of("payerReference").label()).isEqualTo("Payer reference");
        assertThat(RequestValues.NAMES).allMatch(name -> KnownValues.of(name) != null);
    }

    // ---- the request's values under everything else, for one body ----

    private static PreparedCall callWith(Function<String, ResolvedText> variables, RequestValues.ForCall given) {
        return new IssuedCall(org.p32h.interop.epa.pave.model.EpaOperation.CRD_ORDER_SIGN, "POST", null, List.of(), null, "Coverage check",
                false, () -> 1L, variables, null, null, null,
                new IssuedCall.Settings(null, null, null, null, "Coverage check", null, given));
    }

    @Test
    void aValueOfTheCallWinsAndTheRequestGivesOnlyWhatNothingElseDoes() {
        RequestValues.ForCall given = new RequestValues.ForCall(REQUEST, Map.of("payerName", "Placeholder Payer From The Request",
                "payerIdentifier", "P32H-PAYER-1", "payerIdentifierSystem", "urn:example:payer-ids"), Map.of(), true);
        CaseResources.Reads reads = CaseResources.reading(callWith(values(Map.of("payerName", "Placeholder Payer")), given));
        assertThat(CaseResources.value(reads, "payerName")).isEqualTo("Placeholder Payer");
        assertThat(CaseResources.value(reads, "payerIdentifier")).isEqualTo("P32H-PAYER-1");
        assertThat(CaseResources.value(reads, "payerIdentifierSystem")).isEqualTo("urn:example:payer-ids");
        assertThat(reads.requestNotes()).containsExactly("Payer identifier and Payer identifier system come from the collection's request "
                + "\"CRD / Coverage check\", which this call is sent from: nothing else sets them.");
    }

    @Test
    void theIdentifiersSystemIsNotLaidBesideAnIdentifierFromElsewhere() {
        RequestValues.ForCall given = new RequestValues.ForCall(REQUEST, Map.of("payerIdentifier", "P32H-PAYER-1",
                "payerIdentifierSystem", "urn:example:payer-ids"), Map.of(), true);
        CaseResources.Reads reads = CaseResources.reading(callWith(values(Map.of("payerIdentifier", "P32H-PAYER-2")), given));
        assertThat(CaseResources.value(reads, "payerIdentifier")).isEqualTo("P32H-PAYER-2");
        assertThat(CaseResources.value(reads, "payerIdentifierSystem")).isNull();
        assertThat(reads.fromRequest()).isEmpty();
    }

    @Test
    void aValueNotTakenIsSaidWithItsRuleOnlyWhereNothingGaveOne() {
        RequestValues.ForCall given = new RequestValues.ForCall(REQUEST, Map.of(), Map.of("payerName", List.of("why not taken")), true);
        CaseResources.Reads reads = CaseResources.reading(callWith(values(Map.of()), given));
        assertThat(CaseResources.value(reads, "payerName")).isNull();
        assertThat(reads.requestNotes()).containsExactly("why not taken");
        CaseResources.Reads set = CaseResources.reading(callWith(values(Map.of("payerName", "Placeholder Payer")), given));
        assertThat(CaseResources.value(set, "payerName")).isEqualTo("Placeholder Payer");
        assertThat(set.requestNotes()).isEmpty();
    }

    @Test
    void aCallWithNoRequestAndNoCallAtAllTakeNothing() {
        CaseResources.Reads none = CaseResources.reading(callWith(values(Map.of()), null));
        assertThat(CaseResources.value(none, "payerName")).isNull();
        assertThat(none.requestNotes()).isEmpty();
        CaseResources.Reads noCall = CaseResources.reading((PreparedCall) null);
        assertThat(CaseResources.value(noCall, "payerName")).isNull();
        assertThat(noCall.requestNotes()).isEmpty();
        // Any other text is resolved as before: only a value alone in its placeholder is one of the five.
        RequestValues.ForCall given = new RequestValues.ForCall(REQUEST, Map.of("payerName", "Placeholder Payer"), Map.of(), true);
        CaseResources.Reads reads = CaseResources.reading(callWith(values(Map.of()), given));
        assertThat(reads.apply("Payer {{payerName}}").resolved()).isFalse();
    }
}
