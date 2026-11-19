package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.CaseField;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Found;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Missing;
import org.p32h.interop.epa.pave.service.crd.CrdRequestReader.Reading;

/**
 * What the reader takes out of a request somebody else wrote, where it takes it from, and what it
 * refuses to take. That it reads back everything this tool's own builder writes is shown by the
 * round trip in {@link CrdRequestComparerTest}.
 */
class CrdRequestReaderTest {

    private static final String NPI = Npi.withCheckDigit("198765432");
    private static final String MB = "'type':{'coding':[{'system':'http://terminology.hl7.org/CodeSystem/v2-0203','code':'MB'}]}";

    private static Reading read(String json) {
        JsonSafe.Parsed parsed = JsonSafe.parse(json.replace('\'', '"'));
        assertThat(parsed.ok()).as("the test document is JSON: " + json).isTrue();
        return CrdRequestReader.read(parsed.node());
    }

    /** A request with the given prefetch members and context, and nothing else. */
    private static String request(String context, String prefetch) {
        return "{'hook':'order-sign','context':{" + context + "},'prefetch':{" + prefetch + "}}";
    }

    private static void assertFound(Reading reading, CaseField field, String value, String path) {
        Found found = reading.found(field);
        assertThat(found).as(field + " was read").isNotNull();
        assertThat(found.value()).as(field.key()).isEqualTo(value);
        assertThat(found.path()).as(field.key() + " path").isEqualTo(path);
    }

    private static Missing missing(Reading reading, CaseField field) {
        assertThat(reading.found(field)).as(field + " was not read").isNull();
        Missing missing = reading.missing().get(field);
        assertThat(missing).as(field + " is reported as looked for").isNotNull();
        return missing;
    }

    // ---- the hook ----

    @Test
    void theHookIsReadAsWritten() {
        assertFound(read("{'hook':'order-select'}"), CaseField.HOOK, "order-select", "hook");
        assertThat(read("{'hook':'order-select'}").found(CaseField.HOOK).pointer()).isEqualTo("/hook");
        assertThat(missing(read("{}"), CaseField.HOOK).lookedIn()).isEqualTo("hook");
        missing(read("{'hook':' '}"), CaseField.HOOK);
        missing(read("{'hook':7}"), CaseField.HOOK);
    }

    // ---- the member ID ----

    @Test
    void theMemberIdIsReadFromAnIdentifierTypedAsAMemberNumberOnThePatient() {
        Reading reading = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','identifier':["
                + "{'system':'https://ehr.example.invalid/mrn','value':'MRN-PLACEHOLDER-1'},{" + MB + ",'value':'S123456789'}]}"));
        assertFound(reading, CaseField.MEMBER_ID, "S123456789", "prefetch.who.identifier[1].value");
        assertThat(reading.found(CaseField.MEMBER_ID).pointer()).isEqualTo("/prefetch/who/identifier/1/value");
    }

    @Test
    void anIdentifierTypedAsAMemberNumberWithNoSystemOnItsTypeIsStillOne() {
        Reading reading = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','identifier':["
                + "{'type':{'coding':[{'code':'MB'}]},'value':'S123456789'}]}"));
        assertFound(reading, CaseField.MEMBER_ID, "S123456789", "prefetch.who.identifier[0].value");
        // The same code in another code system is not this tool's to interpret.
        Reading other = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','identifier':["
                + "{'type':{'coding':[{'system':'https://example.invalid/codes','code':'MB'}]},'value':'S123456789'}]}"));
        missing(other, CaseField.MEMBER_ID);
    }

    @Test
    void aResourceIdAndThePatientIdOfTheContextAreNeverOfferedAsTheMemberId() {
        // Everything here looks like a member ID and none of it is one: it is all labels.
        Reading reading = read(request("'patientId':'S123456789'", "'who':{'resourceType':'Patient','id':'S123456789',"
                + "'identifier':[{'system':'https://ehr.example.invalid/mrn','value':'S123456789'}]}"));
        Missing missing = missing(reading, CaseField.MEMBER_ID);
        assertThat(missing.candidates()).isEmpty();
        assertThat(missing.lookedIn()).isEqualTo("an identifier typed as a member number (MB) on the Patient or a Coverage, then "
                + "Coverage.subscriberId and the other identifiers of a Coverage");
    }

    @Test
    void whereNoIdentifierIsTypedTheSubscriberIdAndTheIdentifiersOfACoverageAreRead() {
        Reading subscriber = read(request("", "'cov':{'resourceType':'Coverage','id':'c1','subscriberId':'S123456789'}"));
        assertFound(subscriber, CaseField.MEMBER_ID, "S123456789", "prefetch.cov.subscriberId");
        Reading identifier = read(request("", "'cov':{'resourceType':'Coverage','id':'c1','identifier':[{'system':'https://payer.example.invalid/ids',"
                + "'value':'S123456789'}]}"));
        assertFound(identifier, CaseField.MEMBER_ID, "S123456789", "prefetch.cov.identifier[0].value");
        // Both, and they agree once the spaces of a member ID are set aside.
        Reading both = read(request("", "'cov':{'resourceType':'Coverage','id':'c1','subscriberId':'S12345678   01',"
                + "'identifier':[{'value':'S1234567801'}]}"));
        assertFound(both, CaseField.MEMBER_ID, "S1234567801", "prefetch.cov.identifier[0].value");
    }

    @Test
    void candidatesThatDisagreeAreNotUsedAndEachIsReportedWithItsPlace() {
        Reading reading = read(request("", "'cov':{'resourceType':'Coverage','id':'c1','subscriberId':'S123456789',"
                + "'identifier':[{'value':'S987654321'}]}"));
        Missing missing = missing(reading, CaseField.MEMBER_ID);
        assertThat(missing.candidates()).extracting(Found::path, Found::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("prefetch.cov.identifier[0].value", "S987654321"),
                org.assertj.core.groups.Tuple.tuple("prefetch.cov.subscriberId", "S123456789"));
        assertThat(missing.note()).isEqualTo("Your request holds 2 different member IDs, so none was used. Each is listed with its place. "
                + "Type the one you mean on the form.");
        // The subscriber id itself is one value, so it is still read as the subscriber id.
        assertFound(reading, CaseField.SUBSCRIBER_ID, "S123456789", "prefetch.cov.subscriberId");
    }

    @Test
    void aTypedIdentifierIsPreferredAndIsNeverMeasuredAgainstASubscriberId() {
        // This is how this tool writes a member whose subscriber is somebody else: the two ids differ and both are right.
        Reading reading = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','identifier':[{" + MB + ",'value':'S123456789'}]},"
                + "'cov':{'resourceType':'Coverage','id':'c1','identifier':[{" + MB + ",'value':'S123456789'}],'subscriberId':'S000000001'}"));
        assertFound(reading, CaseField.MEMBER_ID, "S123456789", "prefetch.who.identifier[0].value");
        assertFound(reading, CaseField.SUBSCRIBER_ID, "S000000001", "prefetch.cov.subscriberId");
    }

    @Test
    void twoTypedIdentifiersThatDisagreeAreNotUsed() {
        Reading reading = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','identifier':[{" + MB + ",'value':'S123456789'}]},"
                + "'cov':{'resourceType':'Coverage','id':'c1','identifier':[{" + MB + ",'value':'S987654321'}]}"));
        assertThat(missing(reading, CaseField.MEMBER_ID).candidates()).extracting(Found::path)
                .containsExactly("prefetch.who.identifier[0].value", "prefetch.cov.identifier[0].value");
    }

    // ---- the member's details ----

    @Test
    void theMembersDetailsAreReadFromThePatientAndTheCoverage() {
        Reading reading = read(request("'patientId':'p1'", "'who':{'resourceType':'Patient','id':'p1','name':[{'family':'Sample-Member',"
                + "'given':['Robin','Quinn']},{'family':'Earlier-Name'}],'birthDate':'1968-04-12','gender':'female'},"
                + "'cov':{'resourceType':'Coverage','id':'c1','status':'active','subscriberId':'SUB-PLACEHOLDER-1','class':["
                + "{'type':{'coding':[{'code':'group'}]},'value':'GROUP-PLACEHOLDER'},"
                + "{'type':{'coding':[{'system':'http://terminology.hl7.org/CodeSystem/coverage-class','code':'plan'}]},'value':'PLAN-PLACEHOLDER'}]}"));
        assertFound(reading, CaseField.MEMBER_LAST_NAME, "Sample-Member", "prefetch.who.name[0].family");
        assertFound(reading, CaseField.MEMBER_FIRST_NAME, "Robin", "prefetch.who.name[0].given[0]");
        assertFound(reading, CaseField.MEMBER_DATE_OF_BIRTH, "1968-04-12", "prefetch.who.birthDate");
        assertFound(reading, CaseField.MEMBER_GENDER, "female", "prefetch.who.gender");
        assertFound(reading, CaseField.SUBSCRIBER_ID, "SUB-PLACEHOLDER-1", "prefetch.cov.subscriberId");
        assertFound(reading, CaseField.COVERAGE_STATUS, "active", "prefetch.cov.status");
        assertFound(reading, CaseField.PLAN_NAME, "PLAN-PLACEHOLDER", "prefetch.cov.class[1].value");
    }

    @Test
    void thePatientIsTheOneTheContextNamesAndTheOnlyOneWhenItNamesNone() {
        String two = "'a':{'resourceType':'Patient','id':'p1','birthDate':'1968-04-12'},'b':{'resourceType':'Patient','id':'p2','birthDate':'1970-01-31'}";
        assertFound(read(request("'patientId':'p2'", two)), CaseField.MEMBER_DATE_OF_BIRTH, "1970-01-31", "prefetch.b.birthDate");
        assertFound(read(request("'patientId':'Patient/p1'", two)), CaseField.MEMBER_DATE_OF_BIRTH, "1968-04-12", "prefetch.a.birthDate");
        // Two patients and a context that names neither: which one is the member would be a guess.
        assertThat(missing(read(request("'patientId':'p9'", two)), CaseField.MEMBER_DATE_OF_BIRTH).note())
                .isEqualTo("Your request carries 2 Patient resources and context.patientId names none of them.");
        // One patient is the patient, whatever the context calls it.
        assertFound(read(request("'patientId':'p9'", "'a':{'resourceType':'Patient','id':'p1','birthDate':'1968-04-12'}")),
                CaseField.MEMBER_DATE_OF_BIRTH, "1968-04-12", "prefetch.a.birthDate");
        assertThat(missing(read(request("", "")), CaseField.MEMBER_GENDER).note()).isEqualTo("Your request carries no Patient resource.");
    }

    @Test
    void aResourceInsideABundleInPrefetchIsReadLikeAnyOther() {
        Reading reading = read(request("'patientId':'p1'", "'coverageBundle':{'resourceType':'Bundle','type':'searchset','entry':["
                + "{'resource':{'resourceType':'Coverage','id':'c1','status':'active','subscriberId':'S123456789'}}]},"
                + "'who':{'resourceType':'Patient','id':'p1'}"));
        assertFound(reading, CaseField.COVERAGE_STATUS, "active", "prefetch.coverageBundle.entry[0].resource.status");
        assertFound(reading, CaseField.MEMBER_ID, "S123456789", "prefetch.coverageBundle.entry[0].resource.subscriberId");
        assertThat(reading.found(CaseField.MEMBER_ID).pointer()).isEqualTo("/prefetch/coverageBundle/entry/0/resource/subscriberId");
    }

    @Test
    void theLineOfBusinessIsNeverReadNotEvenFromAPlanName() {
        Reading reading = read(request("", "'cov':{'resourceType':'Coverage','id':'c1','type':{'text':'Medicare Advantage'},'class':["
                + "{'type':{'coding':[{'code':'plan'}]},'value':'Medicare Placeholder Plan','name':'Medicare'}]}"));
        Missing missing = missing(reading, CaseField.LINE_OF_BUSINESS);
        assertThat(missing.lookedIn()).isNull();
        assertThat(missing.note()).startsWith("A CRD request does not carry the line of business in any element this tool writes");
        assertFound(reading, CaseField.PLAN_NAME, "Medicare Placeholder Plan", "prefetch.cov.class[0].value");
    }

    // ---- the provider ----

    private static final String PRACTITIONER = "'doc':{'resourceType':'Practitioner','id':'prac-1','identifier':["
            + "{'system':'https://directory.example.invalid/ids','value':'OTHER-PLACEHOLDER'},{'system':'http://hl7.org/fhir/sid/us-npi','value':'" + NPI + "'}],"
            + "'name':[{'family':'Sample-Provider','given':['Alex']}]}";

    @Test
    void theNpiIsReadFromThePractitionerTheUserIdNames() {
        Reading reading = read(request("'userId':'Practitioner/prac-1'", PRACTITIONER));
        assertFound(reading, CaseField.NPI, NPI, "prefetch.doc.identifier[1].value");
        assertFound(reading, CaseField.PROVIDER_LAST_NAME, "Sample-Provider", "prefetch.doc.name[0].family");
        assertFound(reading, CaseField.PROVIDER_FIRST_NAME, "Alex", "prefetch.doc.name[0].given[0]");
        assertThat(Npi.isValid(reading.value(CaseField.NPI))).isTrue();
    }

    @Test
    void theNpiIsReadThroughThePractitionerRoleTheUserIdNames() {
        Reading reading = read(request("'userId':'PractitionerRole/role-1'", "'role':{'resourceType':'PractitionerRole','id':'role-1',"
                + "'practitioner':{'reference':'Practitioner/prac-1'}}," + PRACTITIONER));
        assertFound(reading, CaseField.NPI, NPI, "prefetch.doc.identifier[1].value");
    }

    @Test
    void aProviderWhoseNameIsOneTextIsReadAsThat() {
        Reading reading = read(request("'userId':'Practitioner/prac-1'", "'doc':{'resourceType':'Practitioner','id':'prac-1',"
                + "'name':[{'text':'Sample Clinic'}]}"));
        assertFound(reading, CaseField.PROVIDER_NAME_TEXT, "Sample Clinic", "prefetch.doc.name[0].text");
        missing(reading, CaseField.PROVIDER_LAST_NAME);
        assertThat(missing(reading, CaseField.NPI).lookedIn()).isEqualTo("an identifier with the NPI system on the Practitioner that context.userId leads to");
    }

    @Test
    void aProviderTheUserIdDoesNotLeadToIsNotReadAndTheReasonIsGiven() {
        // A Practitioner is in the request, and nothing says it is the one who is ordering.
        assertThat(missing(read(request("", PRACTITIONER)), CaseField.NPI).note()).isEqualTo("Your request has no context.userId.");
        assertThat(missing(read(request("'userId':'Practitioner/someone-else'", PRACTITIONER)), CaseField.NPI).note())
                .isEqualTo("context.userId names a Practitioner that your request does not carry.");
        assertThat(missing(read(request("'userId':'PractitionerRole/role-1'", PRACTITIONER)), CaseField.NPI).note())
                .isEqualTo("context.userId names a PractitionerRole that your request does not carry.");
        assertThat(missing(read(request("'userId':'PractitionerRole/role-1'", "'role':{'resourceType':'PractitionerRole','id':'role-1'},"
                + PRACTITIONER)), CaseField.NPI).note()).isEqualTo("The PractitionerRole that context.userId names has no reference to a practitioner.");
        assertThat(missing(read(request("'userId':'PractitionerRole/role-1'", "'role':{'resourceType':'PractitionerRole','id':'role-1',"
                + "'practitioner':{'reference':'Practitioner/elsewhere'}}," + PRACTITIONER)), CaseField.NPI).note())
                .isEqualTo("The PractitionerRole that context.userId names points at a Practitioner that your request does not carry.");
        assertThat(missing(read(request("'userId':'prac-1'", PRACTITIONER)), CaseField.NPI).note())
                .isEqualTo("context.userId is not written as a resource type and an id.");
        assertThat(missing(read(request("'userId':'Patient/p1'", PRACTITIONER)), CaseField.PROVIDER_LAST_NAME).note())
                .isEqualTo("context.userId names a resource that is neither a Practitioner nor a PractitionerRole.");
    }

    @Test
    void twoNpisOnOnePractitionerThatDisagreeAreNotUsed() {
        Reading reading = read(request("'userId':'Practitioner/prac-1'", "'doc':{'resourceType':'Practitioner','id':'prac-1','identifier':["
                + "{'system':'http://hl7.org/fhir/sid/us-npi','value':'" + NPI + "'},{'system':'http://hl7.org/fhir/sid/us-npi','value':'1234567893'}]}"));
        assertThat(missing(reading, CaseField.NPI).candidates()).extracting(Found::value).containsExactly(NPI, "1234567893");
    }

    // ---- the orders ----

    private static String orders(String... resources) {
        StringBuilder entries = new StringBuilder();
        for (String resource : resources) {
            entries.append(entries.length() == 0 ? "" : ",").append("{'resource':{").append(resource).append("}}");
        }
        return "{'context':{'draftOrders':{'resourceType':'Bundle','entry':[" + entries + "]}}}";
    }

    @Test
    void eachOrderGivesItsCodeWithItsSystemAndDisplayFromTheElementItsResourceTypeUses() {
        Reading reading = read(orders(
                "'resourceType':'ServiceRequest','id':'a','code':{'coding':[{'system':'http://www.ama-assn.org/go/cpt','code':'95810','display':'Sleep study'}]}",
                "'resourceType':'DeviceRequest','id':'b','codeCodeableConcept':{'coding':[{'code':'E0601'}]}",
                "'resourceType':'MedicationRequest','id':'c','medicationCodeableConcept':{'coding':[{'system':'http://www.nlm.nih.gov/research/umls/rxnorm','code':'1049502'}]}"));
        assertThat(reading.orders()).extracting(CrdRequestReader.Order::resourceType, order -> order.code().value(),
                order -> order.system() == null ? null : order.system().value(),
                order -> order.display() == null ? null : order.display().value()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("ServiceRequest", "95810", "http://www.ama-assn.org/go/cpt", "Sleep study"),
                org.assertj.core.groups.Tuple.tuple("DeviceRequest", "E0601", null, null),
                org.assertj.core.groups.Tuple.tuple("MedicationRequest", "1049502", "http://www.nlm.nih.gov/research/umls/rxnorm", null));
        assertThat(reading.orders().get(0).code().path()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[0].code");
        assertThat(reading.orders().get(1).code().pointer()).isEqualTo("/context/draftOrders/entry/1/resource/codeCodeableConcept/coding/0/code");
        // The system and the display are values read from the request too, so each has the place it was read from.
        assertThat(reading.orders().get(0).system().path()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[0].system");
        assertThat(reading.orders().get(0).system().pointer()).isEqualTo("/context/draftOrders/entry/0/resource/code/coding/0/system");
        assertThat(reading.orders().get(0).display().path()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[0].display");
        assertThat(reading.orders().get(0).display().pointer()).isEqualTo("/context/draftOrders/entry/0/resource/code/coding/0/display");
        assertThat(reading.orders().get(2).system().pointer())
                .isEqualTo("/context/draftOrders/entry/2/resource/medicationCodeableConcept/coding/0/system");
        // Where the coding states none, the place it would have been in is still known, so the answer can say where it looked.
        assertThat(reading.orders().get(1).codingPath()).isEqualTo("context.draftOrders.entry[1].resource.codeCodeableConcept.coding[0]");
        assertThat(reading.missing()).doesNotContainKey(CaseField.PROCEDURE_CODES);
        assertThat(reading.notes()).isEmpty();
    }

    @Test
    void aSystemOrADisplayThatIsNotTextIsNotReadAsOne() {
        // A number where a url belongs is not a system this tool could build with: it is left to the comparison to show.
        Reading reading = read(orders("'resourceType':'ServiceRequest','id':'a','code':{'coding':[{'system':7,'code':'95810','display':true},"
                + "{'system':'  ','code':'70553','display':''}]}"));
        assertThat(reading.orders()).singleElement().satisfies(order -> {
            assertThat(order.code().value()).isEqualTo("95810");
            assertThat(order.system()).isNull();
            assertThat(order.display()).isNull();
        });
    }

    @Test
    void theSystemAndTheDisplayComeFromTheCodingTheCodeWasReadFromAndFromNoOther() {
        // The first coding has a system and no code. Its system belongs to it, and is never lent to the code of the next coding.
        Reading reading = read(orders("'resourceType':'ServiceRequest','id':'a','code':{'coding':[{'system':'https://example.invalid/first',"
                + "'display':'No code here'},{'code':'95810'}]}"));
        assertThat(reading.orders()).singleElement().satisfies(order -> {
            assertThat(order.code().path()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[1].code");
            assertThat(order.codingPath()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[1]");
            assertThat(order.system()).isNull();
            assertThat(order.display()).isNull();
        });
    }

    @Test
    void anOrderWithSeveralCodingsGivesItsFirstAndTheResultSaysTheRestWereNotRead() {
        Reading reading = read(orders("'resourceType':'ServiceRequest','id':'a','code':{'coding':[{'display':'no code here'},"
                + "{'system':'http://www.ama-assn.org/go/cpt','code':'95810'},{'system':'https://example.invalid/local','code':'SLEEP-1'}]}"));
        assertThat(reading.orders()).singleElement().satisfies(order -> {
            assertThat(order.code().value()).isEqualTo("95810");
            assertThat(order.code().path()).isEqualTo("context.draftOrders.entry[0].resource.code.coding[1].code");
            assertThat(order.otherCodings()).isEqualTo(1);
        });
        assertThat(reading.notes()).containsExactly("The ServiceRequest at context.draftOrders.entry[0].resource carries 2 codings with a code. "
                + "This tool sends one coding for each order, so the first was read and the rest were not.");
    }

    @Test
    void anOrderWithNoCodeIsReportedAndIsNotACode() {
        Reading reading = read(orders("'resourceType':'ServiceRequest','id':'a','code':{'text':'A sleep study'}"));
        assertThat(reading.ordersWithCode()).isEmpty();
        assertThat(reading.notes()).containsExactly("No code was found on the ServiceRequest at context.draftOrders.entry[0].resource. It was "
                + "looked for in code.coding.");
        assertThat(missing(reading, CaseField.PROCEDURE_CODES).lookedIn()).startsWith("the orders in context.draftOrders");
    }

    @Test
    void theOtherOrderResourcesAreNotReadForACodeAndTheResultSaysSo() {
        Reading reading = read(orders("'resourceType':'NutritionOrder','id':'n','oralDiet':{'type':[{'coding':[{'code':'1055204001'}]}]}",
                "'resourceType':'Appointment','id':'a','serviceType':[{'coding':[{'code':'124'}]}]",
                "'resourceType':'Patient','id':'p'"));
        assertThat(reading.orders()).isEmpty();
        assertThat(reading.notes()).containsExactly(
                "This tool does not read a procedure code from a NutritionOrder, which is what context.draftOrders.entry[0].resource is.",
                "This tool does not read a procedure code from an Appointment, which is what context.draftOrders.entry[1].resource is.");
        missing(reading, CaseField.PROCEDURE_CODES);
    }

    @Test
    void aRequestWithNoDraftOrdersHasNoCodesAndSaysWhy() {
        assertThat(missing(read("{'context':{}}"), CaseField.PROCEDURE_CODES).note()).isEqualTo("Your request has no entries in context.draftOrders.");
        assertThat(missing(read("{'context':{'draftOrders':'not a bundle'}}"), CaseField.PROCEDURE_CODES).note()).isNotNull();
    }

    // ---- the date of service ----

    private static String orderOn(String occurrence) {
        return "'resourceType':'ServiceRequest','id':'a','code':{'coding':[{'code':'95810'}]}," + occurrence;
    }

    @Test
    void theDateOfServiceIsReadFromTheOccurrenceOfTheOrder() {
        Reading reading = read(orders(orderOn("'occurrenceDateTime':'2026-03-20'")));
        assertFound(reading, CaseField.DATE_OF_SERVICE, "2026-03-20", "context.draftOrders.entry[0].resource.occurrenceDateTime");
        assertThat(reading.found(CaseField.DATE_OF_SERVICE).note()).isNull();
        Reading period = read(orders(orderOn("'occurrencePeriod':{'start':'2026-03-20','end':'2026-04-20'}")));
        assertFound(period, CaseField.DATE_OF_SERVICE, "2026-03-20", "context.draftOrders.entry[0].resource.occurrencePeriod.start");
    }

    @Test
    void aDateAndTimeGivesItsDateAndTheValueSaysSo() {
        Reading reading = read(orders(orderOn("'occurrenceDateTime':'2026-03-20T09:30:00-04:00'")));
        assertFound(reading, CaseField.DATE_OF_SERVICE, "2026-03-20", "context.draftOrders.entry[0].resource.occurrenceDateTime");
        assertThat(reading.found(CaseField.DATE_OF_SERVICE).note()).isEqualTo("Your request holds a date and time here. This tool sends the date "
                + "of service as a date, so the date was used.");
    }

    @Test
    void whatIsNotAFullDateIsNotUsedAsTheDateOfService() {
        for (String value : List.of("2026-03", "2026", "03/20/2026", "2026-02-30", "2026-02-30T10:00:00Z")) {
            Reading reading = read(orders(orderOn("'occurrenceDateTime':'" + value + "'")));
            missing(reading, CaseField.DATE_OF_SERVICE);
            assertThat(reading.notes()).as(value).containsExactly("The value at context.draftOrders.entry[0].resource.occurrenceDateTime is not a "
                    + "full date written as YYYY-MM-DD, so it was not used as the date of service.");
        }
    }

    @Test
    void ordersThatAgreeOnTheDateGiveItAndOrdersThatDisagreeGiveNone() {
        String first = orderOn("'occurrenceDateTime':'2026-03-20'");
        String sameDay = "'resourceType':'DeviceRequest','id':'b','codeCodeableConcept':{'coding':[{'code':'E0601'}]},'occurrenceDateTime':'2026-03-20T08:00:00Z'";
        String later = "'resourceType':'DeviceRequest','id':'b','codeCodeableConcept':{'coding':[{'code':'E0601'}]},'occurrenceDateTime':'2026-04-01'";
        String noDate = "'resourceType':'MedicationRequest','id':'c','medicationCodeableConcept':{'coding':[{'code':'1049502'}]},'authoredOn':'2026-01-01'";
        assertFound(read(orders(first, sameDay, noDate)), CaseField.DATE_OF_SERVICE, "2026-03-20", "context.draftOrders.entry[0].resource.occurrenceDateTime");
        Missing missing = missing(read(orders(first, later)), CaseField.DATE_OF_SERVICE);
        assertThat(missing.candidates()).extracting(Found::path, Found::value).containsExactly(
                org.assertj.core.groups.Tuple.tuple("context.draftOrders.entry[0].resource.occurrenceDateTime", "2026-03-20"),
                org.assertj.core.groups.Tuple.tuple("context.draftOrders.entry[1].resource.occurrenceDateTime", "2026-04-01"));
        assertThat(missing.note()).startsWith("Your request holds 2 different dates of service, so none was used.");
        // The day an order was written is not the day the service is performed.
        missing(read(orders(noDate)), CaseField.DATE_OF_SERVICE);
    }

    // ---- whatever the document holds ----

    @Test
    void aDocumentOfAnyShapeIsReadWithoutFailingAndGivesNothingItDoesNotHold() {
        for (String document : List.of("{}", "{'context':'text','prefetch':[1,2]}", "{'context':{'patientId':{'a':1},'userId':7,"
                + "'draftOrders':{'entry':'x'}},'prefetch':{'a':null,'b':'text','c':{'resourceType':'Patient','name':'not a list','identifier':{'value':'x'}}}}",
                "{'context':{'draftOrders':{'entry':[1,null,{'resource':7},{'resource':{'resourceType':'ServiceRequest','code':'95810'}}]}}}")) {
            Reading reading = read(document);
            assertThat(reading.found()).as(document).isEmpty();
            assertThat(reading.ordersWithCode()).isEmpty();
            assertThat(reading.missing()).containsKeys(CaseField.MEMBER_ID, CaseField.NPI, CaseField.PROCEDURE_CODES, CaseField.LINE_OF_BUSINESS);
        }
    }

    @Test
    void nothingTheReaderHandsBackPrintsAValue() {
        Reading reading = read(request("'patientId':'p1','userId':'Practitioner/prac-1','draftOrders':{'entry':[{'resource':{"
                + orderOn("'occurrenceDateTime':'2026-03-20'") + "}}]}", "'who':{'resourceType':'Patient','id':'p1','identifier':[{" + MB
                + ",'value':'S123456789'}],'name':[{'family':'Sample-Member'}],'birthDate':'1968-04-12'}," + PRACTITIONER));
        String printed = reading + " " + reading.found(CaseField.MEMBER_ID) + " " + reading.orders() + " " + reading.missing();
        assertThat(printed).doesNotContain("S123456789").doesNotContain("Sample-Member").doesNotContain("1968-04-12").doesNotContain(NPI)
                .doesNotContain("95810");
        assertThat(reading.found(CaseField.MEMBER_ID).toString()).isEqualTo("Found[prefetch.who.identifier[0].value]");
        assertThat(reading.orders().get(0).toString()).isEqualTo("Order[ServiceRequest at context.draftOrders.entry[0].resource]");
    }
}
