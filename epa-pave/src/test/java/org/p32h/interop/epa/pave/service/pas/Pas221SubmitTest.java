package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import tools.jackson.databind.JsonNode;

/**
 * Each element R-39 adds to Submit, held against the Da Vinci PAS 2.2.1 source (input/fsh at the
 * 2.2.1 tag: Claim.fsh, Claim-update.fsh, Practitioner.fsh, Quantity.fsh, Organization.fsh and the
 * examples), built from the placeholder fixture {@code pas/submit-221.json}: a submission with every
 * Service card field and the environment's values.
 */
class Pas221SubmitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T14:06:08.123456789Z"), ZoneId.of("America/New_York"));
    private static final String CLAIM = "claim-00000000-0000-4000-8000-000000000001";
    private static final String BUNDLE = "urn:uuid:00000000-0000-4000-8000-0000000000b1";
    private static final String EXT = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-";

    private static JsonNode fixture() {
        try (InputStream in = Pas221SubmitTest.class.getResourceAsStream("/pas/submit-221.json")) {
            return JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).node();
        } catch (IOException e) {
            throw new IllegalStateException("The fixture pas/submit-221.json could not be read", e);
        }
    }

    private static PasSubmitRequest request() {
        return JsonSafe.mapper().treeToValue(fixture().get("request"), PasSubmitRequest.class);
    }

    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        fixture().get("values").properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        return values;
    }

    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            String name = template.substring(2, template.length() - 2);
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    private static PasRequestBuilder.Built build(PasSubmitRequest request, Map<String, String> values) {
        AtomicInteger next = new AtomicInteger();
        return new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_FHIR, CLOCK,
                () -> UUID.fromString(String.format("00000000-0000-4000-8000-%012d", next.incrementAndGet())))
                .submit(request, CLAIM, true, BUNDLE, variables(values));
    }

    private static PasRequestBuilder.Built built() {
        return build(request(), values());
    }

    private static JsonNode bundle(PasRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    private static JsonNode claim(PasRequestBuilder.Built built) {
        return bundle(built).at("/entry/0/resource");
    }

    private static JsonNode byId(PasRequestBuilder.Built built, String type, String id) {
        for (JsonNode entry : bundle(built).get("entry")) {
            JsonNode resource = entry.get("resource");
            if (type.equals(resource.get("resourceType").asString()) && id.equals(resource.get("id").asString())) {
                return resource;
            }
        }
        return null;
    }

    private static JsonNode named(PasRequestBuilder.Built built, JsonNode holder, String reference) {
        return PasBundles.resolve(bundle(built), PasBundles.fullUrlOf(bundle(built), holder.get("resourceType").asString()), reference);
    }

    private static JsonNode extension(JsonNode element, String url) {
        for (JsonNode extension : element.path("extension")) {
            if (url.equals(extension.get("url").asString())) {
                return extension;
            }
        }
        return null;
    }

    private static String coding(JsonNode concept) {
        return concept.at("/coding/0/system").asString() + "#" + concept.at("/coding/0/code").asString();
    }

    @Test
    void theFixtureBuildsARequestWithNothingLeftOut() {
        PasRequestBuilder.Built built = built();
        assertThat(built.leftOut()).isEmpty();
        assertThat(PasBundles.unresolved(bundle(built))).isEmpty();
    }

    @Test
    void everyItemCarriesTheRequestAndCertificationTypesTheCategoryAndThePlaceOfService() {
        for (JsonNode item : claim(built()).get("item")) {
            // PASClaim: item.extension[requestType] 1..1, item.extension[certificationType] 1..1.
            assertThat(coding(extension(item, EXT + "serviceItemRequestType").get("valueCodeableConcept")))
                    .isEqualTo("https://codesystem.x12.org/005010/1525#HS");
            assertThat(coding(extension(item, EXT + "certificationType").get("valueCodeableConcept")))
                    .isEqualTo("https://codesystem.x12.org/005010/1322#I");
            // PASClaim: item.category 1..1, from X12 1365.
            assertThat(coding(item.get("category"))).isEqualTo("https://codesystem.x12.org/005010/1365#1");
            // PASClaim: item.location[x] 1..1, from X12278LocationType (the CMS place of service codes).
            assertThat(coding(item.get("locationCodeableConcept")))
                    .isEqualTo("https://www.cms.gov/Medicare/Coding/place-of-service-codes/Place_of_Service_Code_Set#11");
            // PASIdentifier: system 1..1 on the item trace number.
            assertThat(extension(item, EXT + "itemTraceNumber").at("/valueIdentifier/system").asString())
                    .isEqualTo("https://example.org/p32h-placeholder/trace");
        }
    }

    @Test
    void aValueOfTheServiceCardComesFirstThenTheEnvironmentsValueThenTodaysRuleThenNothing() {
        Map<String, String> values = values();
        values.put("pasRequestType", "AR");
        values.put("pasServiceType", "2");
        values.put("pasCertificationType", "S");
        JsonNode cardWins = claim(build(request(), values)).at("/item/0");
        assertThat(coding(extension(cardWins, EXT + "serviceItemRequestType").get("valueCodeableConcept"))).endsWith("#HS");
        assertThat(coding(cardWins.get("category"))).endsWith("#1");
        assertThat(coding(extension(cardWins, EXT + "certificationType").get("valueCodeableConcept"))).endsWith("#I");

        PasSubmitRequest noCard = PasSubmitRequest.builder().member(request().member()).provider(request().provider())
                .procedureCodes(request().procedureCodes()).placeOfService("11").build();
        JsonNode fromValues = claim(build(noCard, values)).at("/item/0");
        assertThat(coding(extension(fromValues, EXT + "serviceItemRequestType").get("valueCodeableConcept"))).endsWith("#AR");
        assertThat(coding(fromValues.get("category"))).endsWith("#2");
        assertThat(coding(extension(fromValues, EXT + "certificationType").get("valueCodeableConcept"))).endsWith("#S");

        PasRequestBuilder.Built byRule = build(noCard, values());
        assertThat(coding(extension(claim(byRule).at("/item/0"), EXT + "certificationType").get("valueCodeableConcept"))).endsWith("#I");
        assertThat(byRule.notes()).contains("Each item was sent with the certification type I (initial), because the claim identifier was "
                + "minted for this submission.");
        assertThat(byRule.leftOut()).containsExactly("PASClaim item.category", "PASClaim item.extension[requestType]");
        assertThat(byRule.notes()).anySatisfy(note -> assertThat(note).startsWith("No request type was sent on the items: none was chosen "
                + "on the Service card and the value pasRequestType is not set. PAS 2.2.1 PASClaim requires it on every item"));
    }

    @Test
    void aQuantityTravelsAsPasQuantityWithItsX12UnitAndAQuantityWithNoUnitHasNone() {
        JsonNode claim = claim(built());
        JsonNode first = claim.at("/item/0/quantity");
        // Read exactly: 1.50 stays 1.50.
        assertThat(first.get("value").decimalValue()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(first.get("value").decimalValue().toPlainString()).isEqualTo("1.50");
        assertThat(first.get("system").asString()).isEqualTo("https://codesystem.x12.org/005010/673");
        assertThat(first.get("code").asString()).isEqualTo("HS");
        JsonNode second = claim.at("/item/1/quantity");
        assertThat(second.get("value").decimalValue()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(second.has("system")).isFalse();
        assertThat(second.has("code")).isFalse();
        // The order behind the item carries the same quantity.
        assertThat(byId(built(), "ServiceRequest", "service-request-1").get("quantityQuantity")).isEqualTo(first);
        assertThat(built().notes()).contains("The quantity of the code E0601 was sent with no unit, because none was chosen.");
    }

    @Test
    void theClaimCarriesTheLevelOfService() {
        JsonNode level = extension(claim(built()), EXT + "levelOfServiceCode");
        assertThat(coding(level.get("valueCodeableConcept"))).isEqualTo("https://codesystem.x12.org/005010/1338#U");
    }

    @Test
    void theRequestingRoleCarriesThePhoneAndFaxRulePd1AsksFor() {
        PasRequestBuilder.Built built = built();
        JsonNode role = named(built, claim(built), claim(built).at("/provider/reference").asString());
        assertThat(role.get("resourceType").asString()).isEqualTo("PractitionerRole");
        assertThat(role.at("/telecom/0/system").asString()).isEqualTo("phone");
        assertThat(role.at("/telecom/0/value").asString()).isEqualTo("555-0100");
        assertThat(role.at("/telecom/1/system").asString()).isEqualTo("fax");
        assertThat(role.at("/telecom/1/value").asString()).isEqualTo("555-0101");
    }

    @Test
    void theServicingProviderIsACareTeamMemberWithARoleAndAPractitionerOfItsOwn() {
        PasRequestBuilder.Built built = built();
        JsonNode careTeam = claim(built).get("careTeam");
        assertThat(careTeam).hasSize(1);
        JsonNode member = careTeam.get(0);
        assertThat(member.get("sequence").asInt()).isEqualTo(1);
        assertThat(extension(member, EXT + "careTeamClaimScope").get("valueBoolean").asBoolean()).isTrue();
        assertThat(coding(member.get("role"))).isEqualTo("https://codesystem.x12.org/005010/98#SJ");
        JsonNode role = named(built, claim(built), member.at("/provider/reference").asString());
        assertThat(role.get("id").asString()).isEqualTo("practitioner-role-servicing-1");
        assertThat(role.at("/telecom/0/value").asString()).isEqualTo("555-0102");
        JsonNode practitioner = named(built, role, role.at("/practitioner/reference").asString());
        assertThat(practitioner.get("id").asString()).isEqualTo("practitioner-servicing-1");
        assertThat(practitioner.at("/name/0/family").asString()).isEqualTo("Servicing-Placeholder");
        assertThat(practitioner.at("/identifier/0/system").asString()).isEqualTo("http://hl7.org/fhir/sid/us-npi");
        // The requesting provider keeps its own resources.
        assertThat(byId(built, "Practitioner", "practitioner-1").at("/name/0/family").asString()).isEqualTo("Requesting-Placeholder");

        // Without a phone or a fax, rule pd-1 is broken on the servicing role and that is counted.
        PasSubmitRequest noPhone = PasSubmitRequest.builder().member(request().member()).provider(request().provider())
                .procedureCodes(request().procedureCodes()).placeOfService("11").requestType("HS").serviceType("1")
                .servicingProvider(ProviderDetail.builder().npi("1234567893").lastName("Servicing-Placeholder")
                        .build()).build();
        assertThat(build(noPhone, values()).leftOut()).containsExactly("PASPractitionerRole telecom of the servicing provider (rule pd-1)");
        // No servicing provider, no care team at all: never an empty array.
        PasSubmitRequest none = PasSubmitRequest.builder().member(request().member()).provider(request().provider())
                .procedureCodes(request().procedureCodes()).build();
        assertThat(claim(build(none, values())).has("careTeam")).isFalse();
    }

    @Test
    void theInsurerIsThePayerOfTheEnvironmentsValues() {
        PasRequestBuilder.Built built = built();
        JsonNode insurer = named(built, claim(built), claim(built).at("/insurer/reference").asString());
        assertThat(insurer.get("name").asString()).isEqualTo("Placeholder payer");
        assertThat(insurer.at("/identifier/0/system").asString()).isEqualTo("https://example.org/p32h-placeholder/payers");
        assertThat(insurer.at("/identifier/0/value").asString()).isEqualTo("PAYER-PLACEHOLDER-1");
        assertThat(coding(insurer.get("type").get(0))).isEqualTo("https://codesystem.x12.org/005010/98#PR");
        assertThat(insurer.get("active").asBoolean()).isTrue();

        Map<String, String> noPayer = values();
        noPayer.remove("payerName");
        noPayer.remove("payerIdentifier");
        PasRequestBuilder.Built without = build(request(), noPayer);
        assertThat(claim(without).has("insurer")).isFalse();
        assertThat(without.leftOut()).contains("PASClaim insurer");
        assertThat(without.notes()).anySatisfy(note -> assertThat(note).endsWith("add Payer name and Payer identifier under Values in "
                + "Configuration > Environments."));
    }

    @Test
    void everyAdditionalInformationEntryCarriesDocumentInformationWhenAReportTypeIsStated() {
        JsonNode supportingInfo = claim(built()).get("supportingInfo");
        assertThat(supportingInfo).hasSize(2);
        for (JsonNode info : supportingInfo) {
            assertThat(info.at("/category/coding/0/code").asString()).isEqualTo("additionalInformation");
            JsonNode documentInformation = extension(info, EXT + "documentInformation");
            assertThat(coding(extension(documentInformation, "reportTypeCode").get("valueCodeableConcept")))
                    .isEqualTo("https://codesystem.x12.org/005010/755#09");
            assertThat(coding(extension(documentInformation, "transmissionMethod").get("valueCodeableConcept")))
                    .isEqualTo("https://codesystem.x12.org/005010/756#EL");
        }

        Map<String, String> noReport = values();
        noReport.remove("pasReportType");
        PasRequestBuilder.Built without = build(request(), noReport);
        for (JsonNode info : claim(without).get("supportingInfo")) {
            assertThat(info.has("extension")).isFalse();
        }
        // PASClaim supportingInfo[AdditionalInformation].extension[documentInformation] is 1..1: counted once for all entries.
        assertThat(without.leftOut()).containsExactly("PASClaim supportingInfo[AdditionalInformation].extension[documentInformation]");
        assertThat(without.notes()).contains("No document information was sent on the claim's supporting information: the value "
                + "pasReportType is not set. PAS 2.2.1 PASClaim requires it, with an X12 PWK01 report type, on every additional "
                + "information entry: add PAS report type under Values in Configuration > Environments.");
    }

    @Test
    void anAttachmentIsAPasDocumentReferenceWithItsDataAndSize() {
        PasRequestBuilder.Built built = built();
        JsonNode document = byId(built, "DocumentReference", "document-reference-1");
        JsonNode attachment = document.at("/content/0/attachment");
        assertThat(attachment.get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(attachment.get("title").asString()).isEqualTo("Placeholder sleep study");
        assertThat(attachment.get("data").asString()).isEqualTo("UDMySC1wbGFjZWhvbGRlcg==");
        assertThat(attachment.get("size").asLong()).isEqualTo(16);
        // PASDocumentReference: content.attachment.url 0..0.
        assertThat(attachment.has("url")).isFalse();
        assertThat(document.at("/subject/reference").asString()).isNotBlank();
    }

    @Test
    void everyReferenceResolvesInsideTheBundleAndNoArrayOrStringIsEmpty() {
        JsonNode bundle = bundle(built());
        assertThat(PasBundles.unresolved(bundle)).isEmpty();
        assertThat(empties(bundle)).isEmpty();
        // The smallest request too, where most elements are left out.
        PasSubmitRequest bare = PasSubmitRequest.builder().member(request().member()).provider(request().provider())
                .procedureCodes(request().procedureCodes()).build();
        assertThat(empties(bundle(build(bare, Map.of())))).isEmpty();
    }

    @Test
    void theInstantsAreWholeSecondsInUtc() {
        assertThat(bundle(built()).get("timestamp").asString()).isEqualTo("2026-11-22T14:06:08Z");
        assertThat(claim(built()).get("created").asString()).isEqualTo("2026-11-22T14:06:08Z");
    }

    /** The paths of every empty array, empty object and empty or blank string, walked with an explicit stack. */
    static List<String> empties(JsonNode root) {
        List<String> out = new ArrayList<>();
        Deque<Map.Entry<String, JsonNode>> stack = new ArrayDeque<>();
        stack.push(Map.entry("", root));
        while (!stack.isEmpty()) {
            Map.Entry<String, JsonNode> next = stack.pop();
            JsonNode node = next.getValue();
            if (node.isString() && node.asString().isBlank()) {
                out.add(next.getKey());
            } else if ((node.isArray() || node.isObject()) && node.isEmpty()) {
                out.add(next.getKey());
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    stack.push(Map.entry(next.getKey() + "/" + i, node.get(i)));
                }
            } else if (node.isObject()) {
                node.properties().forEach(entry -> stack.push(Map.entry(next.getKey() + "/" + entry.getKey(), entry.getValue())));
            }
        }
        return out;
    }
}
