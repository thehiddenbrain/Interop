package org.p32h.interop.epa.pave.service.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import tools.jackson.databind.JsonNode;

/** How a reference is read, and how it is followed to a resource inside the document that makes it. */
class ResourceIndexTest {

    private static final CompareRules RULES = new CompareRules(Set.of("prefetch"),
            Map.of("context.patientId", "Patient", "context.encounterId", "Encounter", "context.userId", "", "context.selections", ""));

    private static JsonNode json(String text) {
        return JsonSafe.parse(text.replace('\'', '"')).node();
    }

    // ---- the forms a reference is written in ----

    @Test
    void aResourceTypeAndAnIdIsRead() {
        ReferenceText reference = ReferenceText.parse("Patient/patient-1", null);
        assertThat(reference.form()).isEqualTo(ReferenceText.Form.RELATIVE);
        assertThat(reference.type()).isEqualTo("Patient");
        assertThat(reference.id()).isEqualTo("patient-1");
        // A version at the end does not change what is pointed at.
        assertThat(ReferenceText.parse("Patient/patient-1/_history/3", null).id()).isEqualTo("patient-1");
        assertThat(ReferenceText.parse(" Patient/patient-1 ", null).form()).isEqualTo(ReferenceText.Form.RELATIVE);
    }

    @Test
    void anAbsoluteUrlThatEndsInATypeAndAnIdIsReadWithItsBase() {
        ReferenceText reference = ReferenceText.parse("https://ehr.example.invalid/fhir/r4/Patient/8842", null);
        assertThat(reference.form()).isEqualTo(ReferenceText.Form.ABSOLUTE);
        assertThat(reference.base()).isEqualTo("https://ehr.example.invalid/fhir/r4");
        assertThat(reference.type()).isEqualTo("Patient");
        assertThat(reference.id()).isEqualTo("8842");
        assertThat(ReferenceText.parse("https://ehr.example.invalid/Patient/8842/_history/2", null).base()).isEqualTo("https://ehr.example.invalid");
        // A host that happens to start with a capital is a host, not a resource type.
        ReferenceText host = ReferenceText.parse("https://Example.invalid/fhir", null);
        assertThat(host.form()).isEqualTo(ReferenceText.Form.ABSOLUTE);
        assertThat(host.type()).isNull();
    }

    @Test
    void aUrnAContainedPointerAndABareIdAreEachTheirOwnForm() {
        assertThat(ReferenceText.parse("urn:uuid:00000000-0000-4000-8000-000000000001", null).form()).isEqualTo(ReferenceText.Form.URN);
        assertThat(ReferenceText.parse("URN:OID:1.2.3", null).form()).isEqualTo(ReferenceText.Form.URN);
        assertThat(ReferenceText.parse("#med1", "Patient").form()).isEqualTo(ReferenceText.Form.CONTAINED);
        ReferenceText bare = ReferenceText.parse("8842", "Patient");
        assertThat(bare.form()).isEqualTo(ReferenceText.Form.BARE);
        assertThat(bare.type()).isEqualTo("Patient");
        assertThat(bare.id()).isEqualTo("8842");
    }

    @Test
    void textThatIsNoneOfTheseIsNotAReference() {
        // An id on its own is a reference only where the place says which resource type is meant.
        assertThat(ReferenceText.parse("8842", null).recognised()).isFalse();
        assertThat(ReferenceText.parse("8842", " ").recognised()).isFalse();
        assertThat(ReferenceText.parse("see the note", "Patient").recognised()).isFalse();
        assertThat(ReferenceText.parse("patient/8842", null).recognised()).isFalse();
        assertThat(ReferenceText.parse("", "Patient").recognised()).isFalse();
        assertThat(ReferenceText.parse(null, "Patient").recognised()).isFalse();
    }

    @Test
    void twoReferencesAreWrittenTheSameWayOnlyInTheSameFormAndOnTheSameBase() {
        ReferenceText relative = ReferenceText.parse("Patient/a", null);
        assertThat(relative.sameFormAs(ReferenceText.parse("Coverage/b", null))).isTrue();
        assertThat(relative.sameFormAs(ReferenceText.parse("a", "Patient"))).isFalse();
        assertThat(relative.sameFormAs(null)).isFalse();
        ReferenceText absolute = ReferenceText.parse("https://one.example.invalid/fhir/Patient/a", null);
        assertThat(absolute.sameFormAs(ReferenceText.parse("https://one.example.invalid/fhir/Patient/b", null))).isTrue();
        assertThat(absolute.sameFormAs(ReferenceText.parse("https://two.example.invalid/fhir/Patient/a", null))).isFalse();
    }

    @Test
    void aReferenceDoesNotPrintItsText() {
        assertThat(ReferenceText.parse("Patient/S123456789", null).toString()).isEqualTo("ReferenceText[RELATIVE]");
    }

    // ---- the resources a document carries ----

    private static final String DOCUMENT = "{'context':{'patientId':'patient-1','encounterId':'enc-9','userId':'PractitionerRole/role-1',"
            + "'selections':['ServiceRequest/sr-1','ServiceRequest/sr-missing'],"
            + "'draftOrders':{'resourceType':'Bundle','entry':[{'fullUrl':'urn:uuid:00000000-0000-4000-8000-00000000000A',"
            + "'resource':{'resourceType':'ServiceRequest','id':'sr-1','subject':{'reference':'Patient/patient-1'},"
            + "'requester':{'reference':'PractitionerRole/role-1'},'insurance':[{'reference':'Coverage/not-here'}],"
            + "'performer':[{'reference':'https://directory.example.invalid/fhir/Organization/7'}],"
            + "'reasonReference':[{'reference':'#condition-1'}],'supportingInfo':[{'reference':'urn:uuid:00000000-0000-4000-8000-00000000000f'}],"
            + "'basedOn':[{'reference':'urn:uuid:00000000-0000-4000-8000-00000000000a'}]}}]}},"
            + "'prefetch':{'patient':{'resourceType':'Patient','id':'patient-1'}}}";

    @Test
    void everyResourceIsListedWithItsTypeIdPlaceAndTheFullUrlOfItsEntry() {
        ResourceIndex index = ResourceIndex.of(json(DOCUMENT), RULES);
        assertThat(index.resources()).extracting(ResourceIndex.Resource::type, ResourceIndex.Resource::id, ResourceIndex.Resource::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Bundle", null, "context.draftOrders"),
                        org.assertj.core.groups.Tuple.tuple("ServiceRequest", "sr-1", "context.draftOrders.entry[0].resource"),
                        org.assertj.core.groups.Tuple.tuple("Patient", "patient-1", "prefetch.patient"));
        ResourceIndex.Resource order = index.resources("ServiceRequest").get(0);
        assertThat(order.fullUrl()).isEqualTo("urn:uuid:00000000-0000-4000-8000-00000000000A");
        assertThat(order.pointer()).isEqualTo("/context/draftOrders/entry/0/resource");
        assertThat(order.toString()).isEqualTo("Resource[ServiceRequest at context.draftOrders.entry[0].resource]");
        assertThat(index.resources("Coverage")).isEmpty();
    }

    @Test
    void aReferenceIsFollowedToTheResourceItPointsAtInsideTheDocument() {
        ResourceIndex index = ResourceIndex.of(json(DOCUMENT), RULES);
        assertThat(index.resolve(ReferenceText.parse("Patient/patient-1", null))).extracting(ResourceIndex.Resource::path).containsExactly("prefetch.patient");
        assertThat(index.resolve(ReferenceText.parse("patient-1", "Patient"))).hasSize(1);
        // A urn names the entry that carries it as its full url, whatever the letter case of the UUID.
        assertThat(index.resolve(ReferenceText.parse("urn:uuid:00000000-0000-4000-8000-00000000000a", null)))
                .extracting(ResourceIndex.Resource::type).containsExactly("ServiceRequest");
        // An absolute url is followed by its last two segments when no entry carries it as its full url.
        assertThat(index.resolve(ReferenceText.parse("https://ehr.example.invalid/fhir/Patient/patient-1", null))).hasSize(1);
        assertThat(index.resolve(ReferenceText.parse("Patient/someone-else", null))).isEmpty();
        assertThat(index.resolve(ReferenceText.parse("patient-1", "Encounter"))).isEmpty();
        assertThat(index.resolve(ReferenceText.parse("#condition-1", null))).isEmpty();
        assertThat(index.resolve(null)).isEmpty();
    }

    @Test
    void theReferencesThatPointOutsideTheDocumentAreListedByPlace() {
        ResourceIndex index = ResourceIndex.of(json(DOCUMENT), RULES);
        // Not listed: what resolves, the absolute url, which says for itself where its target is,
        // and the pointer to a contained resource, which never leaves its own resource.
        assertThat(index.pointingOutside()).extracting(ResourceIndex.Use::path).containsExactly(
                "context.encounterId",
                "context.userId",
                "context.selections[1]",
                "context.draftOrders.entry[0].resource.requester.reference",
                "context.draftOrders.entry[0].resource.insurance[0].reference",
                "context.draftOrders.entry[0].resource.supportingInfo[0].reference");
        assertThat(index.pointingOutside().get(0).pointer()).isEqualTo("/context/encounterId");
        assertThat(index.pointingOutside().get(0).text().type()).isEqualTo("Encounter");
        assertThat(index.uses()).hasSize(12);
    }

    @Test
    void theKeyReferenceOutsideAnyResourceIsNotAReference() {
        ResourceIndex index = ResourceIndex.of(json("{'reference':'Patient/1','note':{'reference':'Patient/2'}}"), RULES);
        assertThat(index.uses()).isEmpty();
        assertThat(index.resources()).isEmpty();
    }

    @Test
    void aDocumentNestedBeyondTheLimitIsIndexedAsFarAsTheLimitAndNoFurther() {
        String shallow = "{'a':".repeat(10) + "{'resourceType':'Patient','id':'near'}" + "}".repeat(10);
        String deep = "{'a':".repeat(JsonComparer.MAX_DEPTH) + "{'resourceType':'Patient','id':'far'}" + "}".repeat(JsonComparer.MAX_DEPTH);
        assertThat(ResourceIndex.of(json(shallow), RULES).resources()).hasSize(1);
        assertThat(ResourceIndex.of(json(deep), RULES).resources()).isEmpty();
        assertThat(ResourceIndex.of(null, null).resources()).isEmpty();
    }

    @Test
    void whatSaysItIsAResourceIsOnlyAnObjectWithATextResourceType() {
        assertThat(ResourceIndex.resourceType(json("{'resourceType':'Patient'}"))).isEqualTo("Patient");
        assertThat(ResourceIndex.resourceType(json("{'resourceType':' '}"))).isNull();
        assertThat(ResourceIndex.resourceType(json("{'resourceType':{'x':1}}"))).isNull();
        assertThat(ResourceIndex.resourceType(json("['Patient']"))).isNull();
        assertThat(ResourceIndex.resourceType(null)).isNull();
        assertThat(ResourceIndex.resourceType(json("{'resourceType':7}"))).isNull();
    }
}
