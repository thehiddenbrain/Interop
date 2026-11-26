package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import tools.jackson.databind.JsonNode;

/**
 * Submit and the inquiry under Da Vinci PAS 2.0.1, the guide every environment gets unless it
 * chooses 2.2.1. Each expectation is read from the 2.0.1 source (input/fsh at the 2.0.1 tag):
 * Claim.fsh (PASClaimBase: insurer 1..1, item 1..*, item.extension[requestType] and
 * [certificationType] 0..1 MS, item.location[x] MS, levelOfServiceType 0..1 MS; PASClaim:
 * item.category 1..1), Identifier.fsh (PASIdentifier has no required system), Coverage.fsh (no
 * self-beneficiary rule), Practitioner.fsh (PASPractitionerRole on FHIR PractitionerRole, not US
 * Core) and Claim-update.fsh (no documentInformation extension). A bare request leaves out what the
 * build before the rebuild (79a7c25) counted, and nothing more.
 */
class Pas201SubmitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T14:06:08Z"), ZoneId.of("UTC"));
    private static final String CLAIM = "claim-00000000-0000-4000-8000-000000000001";
    private static final String BUNDLE = "urn:uuid:00000000-0000-4000-8000-0000000000b1";
    private static final String EXT = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-";

    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            String name = template.substring(2, template.length() - 2);
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    /** A builder that names no guide, so the default applies. */
    private static PasRequestBuilder builder() {
        AtomicInteger next = new AtomicInteger();
        return new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_FHIR, CLOCK,
                () -> UUID.fromString(String.format("00000000-0000-4000-8000-%012d", next.incrementAndGet())));
    }

    /** The bare request of 79a7c25's PasRequestBuilderTest: member, provider with NPI and name, two codes, a date. */
    private static PasSubmitRequest.Builder bare() {
        return PasSubmitRequest.builder()
                .member(MemberDetail.builder().memberId("S12345678   01").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").lineOfBusiness("Medicare").coverageStatus("active").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .dateOfService("2026-03-20");
    }

    private static JsonNode fixture() {
        try (InputStream in = Pas201SubmitTest.class.getResourceAsStream("/pas/submit-201.json")) {
            return JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).node();
        } catch (IOException e) {
            throw new IllegalStateException("The fixture pas/submit-201.json could not be read", e);
        }
    }

    private static PasRequestBuilder.Built full() {
        Map<String, String> values = new HashMap<>();
        fixture().get("values").properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        PasSubmitRequest request = JsonSafe.mapper().treeToValue(fixture().get("request"), PasSubmitRequest.class);
        return builder().submit(request, CLAIM, true, BUNDLE, variables(values));
    }

    private static JsonNode claim(PasRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node().at("/entry/0/resource");
    }

    private static JsonNode resource(PasRequestBuilder.Built built, String type, String id) {
        for (JsonNode entry : JsonSafe.parse(built.json()).node().get("entry")) {
            JsonNode resource = entry.get("resource");
            if (type.equals(resource.get("resourceType").asString()) && (id == null || id.equals(resource.get("id").asString()))) {
                return resource;
            }
        }
        return null;
    }

    private static JsonNode extension(JsonNode element, String url) {
        for (JsonNode extension : element.path("extension")) {
            if (url.equals(extension.get("url").asString())) {
                return extension;
            }
        }
        return null;
    }

    @Test
    void theGuideIs201UnlessTheEnvironmentChooses221() {
        assertThat(RequestShape.PAS_GUIDE.defaultValue()).isEqualTo("2.0.1");
        assertThat(RequestShape.PAS_GUIDE.options()).extracting(RequestShape.Option::value).containsExactly("2.0.1", "2.2.1");
        assertThat(RequestShape.PAS_GUIDE.label()).isEqualTo("PAS guide");
        assertThat(RequestShape.PAS_GUIDE.why()).isEqualTo("Submit follows Da Vinci PAS 2.0.1 unless the service asks for 2.2.1.");
        assertThat(PasGuide.of(RequestShapes.DEFAULTS)).isEqualTo(PasGuide.V201);
        assertThat(PasGuide.of(null)).isEqualTo(PasGuide.V201);
        assertThat(PasGuide.of(RequestShapes.DEFAULTS.with(RequestShape.PAS_GUIDE, RequestShapes.Choice.ofEnvironment("2.2.1"))))
                .isEqualTo(PasGuide.V221);
        assertThat(PasOptions.CURRENT.version()).isEqualTo("Da Vinci PAS 2.0.1");
    }

    @Test
    void aBareSubmitLeavesOutWhat201RequiresAndNothingMore() {
        PasRequestBuilder.Built built = builder().submit(bare().build(), CLAIM, true, BUNDLE, variables(Map.of()));
        // The four elements 79a7c25 counted for the same request.
        assertThat(built.leftOut()).containsExactly("PASClaim insurer", "PASBeneficiary identifier.system", "PASCoverage payor",
                "PASClaim item.category");
        assertThat(built.notes().get(0)).isEqualTo("This request goes out without 4 elements PAS 2.0.1 requires, because nobody "
                + "supplied them: PASClaim insurer; PASBeneficiary identifier.system; PASCoverage payor; PASClaim item.category. The service "
                + "may refuse the request for that alone.");
        assertThat(built.notes()).containsOnlyOnce("This request follows Da Vinci PAS 2.0.1, the default: no environment states a "
                + "PAS guide (Configuration > Environments, Request shapes).");
        assertThat(built.notes()).noneMatch(note -> note.contains("2.2.1"));
        assertThat(built.notes()).contains("The claim identifier was sent without an identifier system: the variable claimIdentifierSystem "
                + "is not set. PAS 2.0.1 does not require one.");
        // To complete before sending: only what 2.0.1 requires (the insurer, the service type).
        assertThat(built.toComplete()).extracting(gap -> gap.field()).containsExactly("values.payerName", "serviceType");
        assertThat(built.toComplete()).allSatisfy(gap -> assertThat(gap.line()).doesNotContain("2.2.1"));
        // Must-support elements are said as information, never counted.
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("No request type was sent on the items")
                .contains("PAS 2.0.1 PASClaim marks it must-support"));
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("No place of service was sent on the items")
                .contains("PAS 2.0.1 PASClaim marks item.location[x] must-support"));
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).startsWith("No subscriber was named on the coverage")
                .contains("PAS 2.0.1 PASCoverage marks it must-support"));
    }

    @Test
    void everyServiceCardFieldIsSentWhere201DefinesItAndTheReportTypeIsNotSentWithANote() {
        PasRequestBuilder.Built built = full();
        assertThat(built.leftOut()).isEmpty();
        assertThat(built.toComplete()).isEmpty();
        JsonNode claim = claim(built);
        // Claim-update.fsh 2.0.1: levelOfServiceCode, certificationType, serviceItemRequestType, careTeamClaimScope.
        assertThat(extension(claim, EXT + "levelOfServiceCode").at("/valueCodeableConcept/coding/0/code").asString()).isEqualTo("U");
        JsonNode item = claim.at("/item/0");
        assertThat(extension(item, EXT + "certificationType").at("/valueCodeableConcept/coding/0/code").asString()).isEqualTo("I");
        assertThat(extension(item, EXT + "serviceItemRequestType").at("/valueCodeableConcept/coding/0/code").asString()).isEqualTo("HS");
        assertThat(item.at("/category/coding/0/code").asString()).isEqualTo("1");
        assertThat(item.at("/locationCodeableConcept/coding/0/code").asString()).isEqualTo("11");
        assertThat(item.at("/quantity/value").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(item.at("/quantity/code").asString()).isEqualTo("HS");
        assertThat(extension(item, EXT + "itemTraceNumber").at("/valueIdentifier/system").asString())
                .isEqualTo("https://example.org/p32h-placeholder/trace");
        assertThat(extension(claim.at("/careTeam/0"), EXT + "careTeamClaimScope").get("valueBoolean").booleanValue()).isTrue();
        assertThat(claim.at("/careTeam/0/role/coding/0/code").asString()).isEqualTo("SJ");
        assertThat(claim.at("/insurer/reference").asString()).isNotEmpty();
        assertThat(resource(built, "PractitionerRole", "practitioner-role-1").at("/telecom/0/value").asString()).isEqualTo("555-0100");
        JsonNode document = resource(built, "DocumentReference", null);
        assertThat(document.at("/content/0/attachment/data").asString()).isEqualTo("UDMySC1wbGFjZWhvbGRlcg==");
        // PAS 2.0.1 defines no documentInformation extension: the report type has nowhere to go.
        assertThat(built.json()).doesNotContain(EXT + "documentInformation");
        assertThat(built.notes()).contains("PAS report type: not sent, PAS 2.0.1 has no place for it");
        assertThat(built.notes()).noneMatch(note -> note.contains("2.2.1"));
    }

    @Test
    void underTheSameRequest221StillCountsWhatItRequires() {
        PasRequestBuilder.Built built = builder().under(PasGuide.V221).submit(bare().build(), CLAIM, true, BUNDLE, variables(Map.of()));
        assertThat(built.leftOut()).as("2.2.1 counts its own requirements: claim and trace systems, request type, place of service, payer system, phone or fax, subscriber").hasSize(10);
        assertThat(built.notes().get(0)).contains("10 elements PAS 2.2.1 requires");
        assertThat(built.notes()).contains("This request follows Da Vinci PAS 2.2.1, the PAS guide of the environment "
                + "(Configuration > Environments, Request shapes).");
    }

    @Test
    void anAttachmentByUrlIsNotAGapUnder201() {
        PasRequestBuilder.Built built = builder().submit(bare().attachments(List.of(new PasAttachment("Placeholder study", "application/pdf",
                null, "https://example.org/p32h-placeholder/doc.pdf", new CodeInput("11506-3", "http://loinc.org", null),
                new CodeInput("clinical-note", null, null)))).build(), CLAIM, true, BUNDLE, variables(Map.of()));
        assertThat(built.leftOut()).noneMatch(element -> element.contains("DocumentReference"));
        assertThat(built.notes()).anySatisfy(note -> assertThat(note).contains("PAS 2.0.1 allows a url"));
    }

    /**
     * Each resource this tool builds for a Submit says which PAS profile it is, right after its id, as the guide's own
     * examples do (D-319): the vendor's service refused a Bundle that named none, and may look for one on each resource.
     * The ids are those of the 2.0.1 sources (Claim.fsh, Patient.fsh, Coverage.fsh, Organization.fsh, Practitioner.fsh,
     * ServiceRequest.fsh). A document says none: 2.0.1 has no profile for it.
     */
    @Test
    void eachResourceOfASubmitDeclaresThePasProfileItIsBuiltAs() {
        Map<String, String> expected = Map.of("Claim", "profile-claim", "Patient", "profile-beneficiary", "Coverage", "profile-coverage",
                "Organization", "profile-insurer", "Practitioner", "profile-practitioner", "PractitionerRole", "profile-practitionerrole",
                "ServiceRequest", "profile-servicerequest");
        Map<String, Integer> seen = new HashMap<>();
        for (JsonNode entry : JsonSafe.parse(full().json()).node().get("entry")) {
            JsonNode resource = entry.get("resource");
            String type = resource.get("resourceType").asString();
            if (type.equals("DocumentReference")) {
                assertThat(resource.has("meta")).as("a document declares no PAS profile").isFalse();
                continue;
            }
            if (!expected.containsKey(type)) {
                // A questionnaire response travels as DTR completed it.
                continue;
            }
            String what = type + "/" + resource.get("id").asString();
            assertThat(resource.at("/meta/profile")).as(what).hasSize(1);
            assertThat(resource.at("/meta/profile/0").asString()).as(what)
                    .isEqualTo("http://hl7.org/fhir/us/davinci-pas/StructureDefinition/" + expected.get(type));
            assertThat(resource.propertyNames().stream().limit(3).toList()).as(what).containsExactly("resourceType", "id", "meta");
            seen.merge(type, 1, Integer::sum);
        }
        // The full request carries each of them, and the requesting and the servicing provider as two people in two roles.
        assertThat(seen.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(seen).containsEntry("Practitioner", 2).containsEntry("PractitionerRole", 2);
    }

    /** An organization says which part it plays: the requestor is PASRequestor, a care team member the plain PASOrganization. */
    @Test
    void anOrganizationDeclaresTheProfileOfThePartItPlays() {
        PasSubmitRequest request = bare().provider(ProviderDetail.builder().npi("1234567893").organizationName("Placeholder Clinic").build())
                .servicingProvider(ProviderDetail.builder().organizationName("Placeholder Sleep Laboratory").build()).build();
        PasRequestBuilder.Built built = builder().submit(request, CLAIM, true, BUNDLE,
                variables(Map.of("payerName", "Placeholder Payer", "payerIdentifier", "P32H-PAYER-1")));
        String pas = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/";
        assertThat(resource(built, "Organization", "organization-requestor-1").at("/meta/profile/0").asString())
                .isEqualTo(pas + "profile-requestor");
        assertThat(resource(built, "Organization", "organization-servicing-1").at("/meta/profile/0").asString())
                .isEqualTo(pas + "profile-organization");
        JsonNode insurer = null;
        for (JsonNode entry : JsonSafe.parse(built.json()).node().get("entry")) {
            JsonNode resource = entry.get("resource");
            if ("Organization".equals(resource.get("resourceType").asString())
                    && (pas + "profile-insurer").equals(resource.at("/meta/profile/0").asString())) {
                insurer = resource;
            }
        }
        assertThat(insurer).as("the insurer organization declares PASInsurer").isNotNull();
        assertThat(insurer.get("name").asString()).isEqualTo("Placeholder Payer");
    }

    @Test
    void anInquiryUnder201NeedsAnItemAndDeclaresItsProfile() {
        PasInquireRequest request = PasInquireRequest.builder().member(MemberDetail.builder().memberId("S123456789").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).build();
        PasRequestBuilder.Built none = builder().inquire(request, "claim-test-0001", List.of(), "nowhere", BUNDLE, variables(Map.of()));
        assertThat(none.leftOut()).contains("PASClaimInquiry item");
        PasRequestBuilder.Built none221 = builder().under(PasGuide.V221).inquire(request, "claim-test-0001", List.of(), "nowhere", BUNDLE,
                variables(Map.of()));
        assertThat(none221.leftOut()).doesNotContain("PASClaimInquiry item");
        PasRequestBuilder.Built one = builder().inquire(request, "claim-test-0001", List.of(CodeInput.of("95810")), "given on the form",
                BUNDLE, variables(Map.of()));
        assertThat(one.leftOut()).doesNotContain("PASClaimInquiry item");
        // Claim.fsh 2.0.1: PASClaimInquiry, Id profile-claim-inquiry.
        assertThat(claim(one).at("/meta/profile/0").asString())
                .isEqualTo("http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim-inquiry");
        List<String> all = new ArrayList<>(one.notes());
        assertThat(all).noneMatch(note -> note.contains("2.2.1"));
    }
}
