package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import tools.jackson.databind.node.ObjectNode;

/**
 * The member API mapping. Every response here is invented. The contract of that API is not final,
 * so these tests pin what the mapping tolerates today and, as much, what it refuses to guess.
 */
class MemberResponseMappingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final MemberIdForms FORMS = MemberIds.forms("S123456789");
    private static final String RECORD = "{\"memberId\":\"S123456789\",\"firstName\":\"Avery\",\"lastName\":\"Placeholder\","
            + "\"dob\":\"1970-01-31\",\"lob\":\"Medicare\"}";

    /** Every way a response may write each field, as a service would write it. Checked against the mapping's own tables below. */
    private static final Map<String, List<String>> SPELLINGS = new LinkedHashMap<>();
    /** The ways a part may be written inside an address object. */
    private static final Map<String, List<String>> IN_ADDRESS = new LinkedHashMap<>();
    /** The ways a part may be written beside the other fields when there is no address object. */
    private static final Map<String, List<String>> FLAT_ADDRESS = new LinkedHashMap<>();
    private static final Map<String, String> SAMPLE = new LinkedHashMap<>();
    private static final Map<String, Function<MemberDetail, String>> DETAIL = new LinkedHashMap<>();

    static {
        SPELLINGS.put("memberId", List.of("memberId", "member_id", "MemberID", "MEMBER-ID", "memberNumber", "member_number",
                "memberIdentifier", "memberNo", "id"));
        SPELLINGS.put("firstName", List.of("firstName", "first_name", "FirstName", "givenName", "name.first", "name.firstName",
                "name.givenName", "name.given[0]", "name.given"));
        SPELLINGS.put("lastName", List.of("lastName", "last_name", "LastName", "familyName", "surname", "name.last", "name.lastName",
                "name.familyName", "name.family", "name.surname"));
        SPELLINGS.put("fullName", List.of("fullName", "full_name", "memberName", "displayName", "name", "name.text", "name.full",
                "name.fullName"));
        SPELLINGS.put("dateOfBirth", List.of("dateOfBirth", "date_of_birth", "DateOfBirth", "dob", "DOB", "birthDate", "birth_date",
                "birthdate", "birthday"));
        SPELLINGS.put("gender", List.of("gender", "Gender", "sex", "genderCode", "administrativeGender"));
        SPELLINGS.put("lineOfBusiness", List.of("lineOfBusiness", "line_of_business", "LineOfBusiness", "lob", "LOB", "businessLine",
                "coverage.lineOfBusiness", "coverage.lob", "plan.lineOfBusiness", "plan.lob", "eligibility.lineOfBusiness"));
        SPELLINGS.put("planName", List.of("planName", "plan_name", "plan", "planDescription", "benefitPlan", "benefitPlanName",
                "productName", "plan.name", "plan.planName", "coverage.planName", "coverage.plan", "coverage.plan.name"));
        SPELLINGS.put("coverageStatus", List.of("coverageStatus", "coverage_status", "CoverageStatus", "eligibilityStatus",
                "enrollmentStatus", "coverage.status", "eligibility.status", "enrollment.status"));
        SPELLINGS.put("coverageActive", List.of("coverageActive", "coverage_active", "isCoverageActive", "activeCoverage",
                "hasActiveCoverage", "coverage.active", "coverage.isActive"));
        SPELLINGS.put("coverageStart", List.of("coverageStart", "coverageStartDate", "coverage_start_date", "coverageEffectiveDate",
                "effectiveDate", "effective_date", "startDate", "eligibilityStartDate", "coverage.start", "coverage.startDate",
                "coverage.effectiveDate", "coverage.period.start", "eligibility.startDate", "eligibility.effectiveDate", "period.start"));
        SPELLINGS.put("coverageEnd", List.of("coverageEnd", "coverageEndDate", "coverage_end_date", "coverageTerminationDate",
                "terminationDate", "termDate", "term_date", "endDate", "expirationDate", "eligibilityEndDate", "coverage.end",
                "coverage.endDate", "coverage.terminationDate", "coverage.termDate", "coverage.period.end", "eligibility.endDate",
                "eligibility.terminationDate", "period.end"));
        SPELLINGS.put("subscriberId", List.of("subscriberId", "subscriber_id", "subscriberNumber", "subscriberIdentifier",
                "subscriber.id", "subscriber.subscriberId", "coverage.subscriberId"));

        IN_ADDRESS.put("addressLine1", List.of("line1", "addressLine1", "address_line_1", "address1", "street", "street1",
                "streetAddress", "line[0]", "line"));
        IN_ADDRESS.put("addressLine2", List.of("line2", "addressLine2", "address_line_2", "address2", "street2", "line[1]"));
        IN_ADDRESS.put("city", List.of("city", "City", "town"));
        IN_ADDRESS.put("state", List.of("state", "stateCode", "province"));
        IN_ADDRESS.put("postalCode", List.of("postalCode", "postal_code", "zip", "zipCode", "zip_code", "postal"));
        FLAT_ADDRESS.put("addressLine1", List.of("addressLine1", "address_line_1", "address1", "street", "streetAddress"));
        FLAT_ADDRESS.put("addressLine2", List.of("addressLine2", "address2", "street2"));
        FLAT_ADDRESS.put("city", List.of("city"));
        FLAT_ADDRESS.put("state", List.of("state", "state_code"));
        FLAT_ADDRESS.put("postalCode", List.of("postalCode", "zip", "zipCode"));

        SAMPLE.put("memberId", "S123456789");
        SAMPLE.put("firstName", "Avery");
        SAMPLE.put("lastName", "Placeholder");
        SAMPLE.put("fullName", "Avery Placeholder");
        SAMPLE.put("dateOfBirth", "1970-01-31");
        SAMPLE.put("gender", "female");
        SAMPLE.put("lineOfBusiness", "Medicare");
        SAMPLE.put("planName", "Synthetic Plan A");
        SAMPLE.put("coverageStatus", "Active");
        SAMPLE.put("coverageActive", "true");
        SAMPLE.put("coverageStart", "2026-01-01");
        SAMPLE.put("coverageEnd", "2099-12-31");
        SAMPLE.put("subscriberId", "S987654321");
        SAMPLE.put("addressLine1", "1 Placeholder Street");
        SAMPLE.put("addressLine2", "Suite 2");
        SAMPLE.put("city", "Sampletown");
        SAMPLE.put("state", "MA");
        SAMPLE.put("postalCode", "00000");

        DETAIL.put("firstName", MemberDetail::firstName);
        DETAIL.put("lastName", MemberDetail::lastName);
        DETAIL.put("dateOfBirth", MemberDetail::dateOfBirth);
        DETAIL.put("gender", MemberDetail::gender);
        DETAIL.put("lineOfBusiness", MemberDetail::lineOfBusiness);
        DETAIL.put("planName", MemberDetail::planName);
        DETAIL.put("coverageStatus", MemberDetail::coverageStatus);
        // With no status in words, the member carries the plain word for the yes or no.
        DETAIL.put("coverageActive", MemberDetail::coverageStatus);
        DETAIL.put("coverageStart", MemberDetail::coverageStart);
        DETAIL.put("coverageEnd", MemberDetail::coverageEnd);
        DETAIL.put("subscriberId", MemberDetail::subscriberId);
        DETAIL.put("addressLine1", member -> member.address().line1());
        DETAIL.put("addressLine2", member -> member.address().line2());
        DETAIL.put("city", member -> member.address().city());
        DETAIL.put("state", member -> member.address().state());
        DETAIL.put("postalCode", member -> member.address().postalCode());
    }

    private static MemberLookupResult map(int status, String body) {
        return MemberResponseMapping.map(TestExchanges.of(EpaOperation.MEMBER_LOOKUP, status, body, true), FORMS, TODAY);
    }

    private static MemberLookupResult found(String body) {
        MemberLookupResult result = map(200, body);
        assertThat(result.status()).as(body).isEqualTo(LookupStatus.FOUND);
        return result;
    }

    private static LookupField field(MemberLookupResult result, String name) {
        return result.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> paths(MemberLookupResult result) {
        return result.additionalFields().stream().map(AdditionalField::path).toList();
    }

    /** A record that is a member whatever field is under test: it carries one other field that says so. */
    private static ObjectNode anchored(String fieldUnderTest) {
        ObjectNode body = LookupJson.object();
        if (fieldUnderTest.equals("lineOfBusiness")) {
            return LookupJson.put(body, "dob", "1970-01-31");
        }
        return LookupJson.put(body, "lob", "Medicare");
    }

    private static void assertReadFrom(String name, String path, MemberLookupResult result) {
        LookupField read = field(result, name);
        assertThat(read.value()).as(name + " at " + path).isEqualTo(Shown.of(SAMPLE.get(name)));
        assertThat(read.sourceField()).as(name + " at " + path).isEqualTo(path);
        if (DETAIL.containsKey(name)) {
            assertThat(DETAIL.get(name).apply(result.member())).as(name + " at " + path)
                    .isEqualTo(name.equals("coverageActive") ? "active" : SAMPLE.get(name));
        }
        // The sample values are a member with nothing wrong, so no spelling may raise a reason to look.
        assertThat(result.attention()).as(name + " at " + path).isEmpty();
    }

    private static ObjectNode putSample(ObjectNode body, String name, String path) {
        return name.equals("coverageActive") ? LookupJson.put(body, path, true) : LookupJson.put(body, path, SAMPLE.get(name));
    }

    // ---------------------------------------------------------------------------------------
    // Field spellings

    @Test
    void everySpellingOfEveryFieldIsReadAndSaysWhereItWasReadFrom() {
        SPELLINGS.forEach((name, spellings) -> {
            for (String spelling : spellings) {
                assertReadFrom(name, spelling, found(putSample(anchored(name), name, spelling).toString()));
            }
        });
    }

    @Test
    void everyPartOfAnAddressIsReadFromAnAddressObjectUnderAnyOfItsNames() {
        IN_ADDRESS.forEach((name, spellings) -> {
            for (String spelling : spellings) {
                String path = "address." + spelling;
                assertReadFrom(name, path, found(putSample(anchored(name), name, path).toString()));
            }
        });
        for (String object : List.of("address", "homeAddress", "home_address", "residentialAddress", "mailingAddress", "primaryAddress")) {
            String path = object + ".city";
            assertReadFrom("city", path, found(putSample(anchored("city"), "city", path).toString()));
        }
        // A list of one address is read as that address.
        MemberLookupResult listed = found("{\"lob\":\"Medicare\",\"address\":[{\"line\":[\"1 Placeholder Street\",\"Suite 2\"],"
                + "\"city\":\"Sampletown\",\"state\":\"MA\",\"postalCode\":\"00000\"}]}");
        assertThat(field(listed, "addressLine1").sourceField()).isEqualTo("address[0].line[0]");
        assertThat(field(listed, "addressLine2").sourceField()).isEqualTo("address[0].line[1]");
        assertThat(listed.member().address().city()).isEqualTo("Sampletown");
    }

    @Test
    void anAddressWrittenBesideTheOtherFieldsIsReadToo() {
        FLAT_ADDRESS.forEach((name, spellings) -> {
            for (String spelling : spellings) {
                assertReadFrom(name, spelling, found(putSample(anchored(name), name, spelling).toString()));
            }
        });
    }

    @Test
    void theTestTablesAreExactlyTheSpellingsTheMappingKnows() {
        // A spelling added to the mapping with no test, or dropped from it, fails here.
        Map<String, List<Spelling>> mapping = new LinkedHashMap<>();
        mapping.put("memberId", MemberResponseMapping.MEMBER_ID);
        mapping.put("firstName", MemberResponseMapping.FIRST_NAME);
        mapping.put("lastName", MemberResponseMapping.LAST_NAME);
        mapping.put("fullName", MemberResponseMapping.FULL_NAME);
        mapping.put("dateOfBirth", MemberResponseMapping.DATE_OF_BIRTH);
        mapping.put("gender", MemberResponseMapping.GENDER);
        mapping.put("lineOfBusiness", MemberResponseMapping.LINE_OF_BUSINESS);
        mapping.put("planName", MemberResponseMapping.PLAN_NAME);
        mapping.put("coverageStatus", MemberResponseMapping.COVERAGE_STATUS);
        mapping.put("coverageActive", MemberResponseMapping.COVERAGE_ACTIVE);
        mapping.put("coverageStart", MemberResponseMapping.COVERAGE_START);
        mapping.put("coverageEnd", MemberResponseMapping.COVERAGE_END);
        mapping.put("subscriberId", MemberResponseMapping.SUBSCRIBER_ID);
        assertThat(mapping.keySet()).isEqualTo(SPELLINGS.keySet());
        mapping.forEach((name, spellings) -> assertThat(steps(SPELLINGS.get(name))).as(name).isEqualTo(stepsOf(spellings)));

        Map<String, List<Spelling>> inAddress = Map.of("addressLine1", MemberResponseMapping.LINE_1_IN_ADDRESS,
                "addressLine2", MemberResponseMapping.LINE_2_IN_ADDRESS, "city", MemberResponseMapping.CITY_IN_ADDRESS,
                "state", MemberResponseMapping.STATE_IN_ADDRESS, "postalCode", MemberResponseMapping.POSTAL_CODE_IN_ADDRESS);
        inAddress.forEach((name, spellings) -> assertThat(steps(IN_ADDRESS.get(name))).as(name).isEqualTo(stepsOf(spellings)));
        Map<String, List<Spelling>> flat = Map.of("addressLine1", MemberResponseMapping.LINE_1_FLAT,
                "addressLine2", MemberResponseMapping.LINE_2_FLAT, "city", MemberResponseMapping.CITY_FLAT,
                "state", MemberResponseMapping.STATE_FLAT, "postalCode", MemberResponseMapping.POSTAL_CODE_FLAT);
        flat.forEach((name, spellings) -> assertThat(steps(FLAT_ADDRESS.get(name))).as(name).isEqualTo(stepsOf(spellings)));
        assertThat(MemberResponseMapping.ADDRESS_OBJECTS).containsExactly("address", "homeAddress", "residentialAddress",
                "mailingAddress", "primaryAddress");
        assertThat(MemberResponseMapping.WRAPPERS).containsExactly("data", "result", "results", "member", "members");
    }

    private static Set<List<Spelling.Step>> steps(List<String> written) {
        return written.stream().map(one -> Spelling.of(one).steps()).collect(Collectors.toSet());
    }

    private static Set<List<Spelling.Step>> stepsOf(List<Spelling> spellings) {
        return spellings.stream().map(Spelling::steps).collect(Collectors.toSet());
    }

    @Test
    void aSubscriberIdIsNeverReadAsTheMemberId() {
        MemberLookupResult result = found("{\"subscriberId\":\"S987654321\",\"dob\":\"1970-01-31\"}");
        assertThat(field(result, "memberId").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "memberId").sourceField()).isNull();
        assertThat(field(result, "subscriberId").value()).isEqualTo(Shown.of("S987654321"));
        // The member keeps the ID that was asked for, and no mismatch is claimed against a field that is not the member ID.
        assertThat(result.member().memberId()).isEqualTo("S123456789");
        assertThat(result.attention()).isEmpty();
    }

    @Test
    void namesThatDescribeAResponseAndNotAMemberAreNotRead() {
        MemberLookupResult result = found("{\"status\":\"ok\",\"first\":true,\"last\":true,\"type\":\"member\",\"dob\":\"1970-01-31\"}");
        assertThat(field(result, "coverageStatus").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "firstName").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "lastName").value()).isEqualTo(Shown.absent());
        assertThat(result.member().coverageStatus()).isNull();
        assertThat(paths(result)).containsExactly("status", "first", "last", "type");
    }

    @Test
    void theFirstSpellingThatCarriesAValueWinsAndTheOthersStayOnScreen() {
        MemberLookupResult result = found("{\"id\":\"internal-77\",\"member_id\":\"S123456789\",\"firstName\":null,"
                + "\"first_name\":\"Avery\",\"dob\":\"1970-01-31\"}");
        // memberId is listed before id, and a value beats an earlier null.
        assertThat(field(result, "memberId").sourceField()).isEqualTo("member_id");
        assertThat(field(result, "firstName").value()).isEqualTo(Shown.of("Avery"));
        assertThat(field(result, "firstName").sourceField()).isEqualTo("first_name");
        assertThat(result.additionalFields()).containsExactly(new AdditionalField("id", Presence.VALUE, "\"internal-77\""),
                new AdditionalField("firstName", Presence.NULL, null));
    }

    @Test
    void aFieldThatIsAbsentNullOrEmptyGivesThreeDifferentPresences() {
        MemberLookupResult result = found("{\"memberId\":\"S123456789\",\"dob\":\"1970-01-31\",\"planName\":null,\"gender\":\"\"}");
        assertThat(field(result, "lineOfBusiness").value().presence()).isEqualTo(Presence.ABSENT);
        assertThat(field(result, "lineOfBusiness").sourceField()).isNull();
        assertThat(field(result, "planName").value().presence()).isEqualTo(Presence.NULL);
        assertThat(field(result, "planName").sourceField()).isEqualTo("planName");
        assertThat(field(result, "gender").value().presence()).isEqualTo(Presence.EMPTY);
        assertThat(field(result, "gender").sourceField()).isEqualTo("gender");
        assertThat(field(result, "dateOfBirth").value().presence()).isEqualTo(Presence.VALUE);
        // None of the three becomes a value on the member, and the null and the empty are not listed twice.
        assertThat(result.member().lineOfBusiness()).isNull();
        assertThat(result.member().planName()).isNull();
        assertThat(result.member().gender()).isNull();
        assertThat(result.additionalFields()).isEmpty();
    }

    @Test
    void aValueThatWasNotReturnedIsNeverFilledIn() {
        MemberLookupResult result = found("{\"firstName\":\"Avery\",\"lastName\":\"Placeholder\"}");
        MemberDetail member = result.member();
        assertThat(member.coverageStatus()).isNull();
        assertThat(member.lineOfBusiness()).isNull();
        assertThat(member.dateOfBirth()).isNull();
        assertThat(member.gender()).isNull();
        assertThat(member.planName()).isNull();
        assertThat(member.address()).isNull();
        // Not returned is said in the strip. It is not passed off as active by saying nothing.
        assertThat(result.message()).isEqualTo("Member found, but the member API returned no coverage status.");
        assertThat(result.attention()).isEmpty();
        assertThat(result.fields()).hasSize(18).extracting(LookupField::name).containsExactly("memberId", "firstName", "lastName",
                "fullName", "dateOfBirth", "gender", "lineOfBusiness", "planName", "coverageStatus", "coverageActive", "coverageStart",
                "coverageEnd", "subscriberId", "addressLine1", "addressLine2", "city", "state", "postalCode");
    }

    @Test
    void aCoverageStatusReturnedAsNullOrEmptyIsNoStatusAndIsNeverReadAsActive() {
        for (String nothing : List.of("null", "\"\"", "\"   \"")) {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":" + nothing + ",\"coverageActive\":" + nothing + "}");
            assertThat(result.member().coverageStatus()).as(nothing).isNull();
            assertThat(result.attention()).as(nothing).isEmpty();
            assertThat(result.message()).as(nothing).isEqualTo("Member found, but the member API returned no coverage status.");
            // The field is in the designed view as what it was, and is not listed a second time.
            assertThat(field(result, "coverageStatus").sourceField()).as(nothing).isEqualTo("coverageStatus");
            assertThat(field(result, "coverageStatus").value().presence()).as(nothing).isNotEqualTo(Presence.ABSENT);
            assertThat(result.additionalFields()).as(nothing).isEmpty();
        }
    }

    @Test
    void anObjectOrAListWhereOneValueIsExpectedIsLeftAmongTheReturnedFields() {
        MemberLookupResult result = found("{\"dob\":\"1970-01-31\",\"gender\":{\"code\":\"F\",\"display\":\"Female\"},"
                + "\"lob\":[\"Medicare\",\"Medicaid\"],\"name\":{\"given\":[]}}");
        assertThat(field(result, "gender").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "lineOfBusiness").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "firstName").value()).isEqualTo(Shown.absent());
        assertThat(result.member().gender()).isNull();
        // An empty list is listed too, which is how it is told apart from no list at all.
        assertThat(paths(result)).containsExactly("gender.code", "gender.display", "lob[0]", "lob[1]", "name.given");
    }

    // ---------------------------------------------------------------------------------------
    // Names

    @Test
    void aNestedNameIsReadInBothShapes() {
        MemberLookupResult plain = found("{\"name\":{\"first\":\"Avery\",\"last\":\"Placeholder\"},\"dob\":\"1970-01-31\"}");
        assertThat(plain.member().firstName()).isEqualTo("Avery");
        assertThat(plain.member().lastName()).isEqualTo("Placeholder");
        assertThat(field(plain, "firstName").sourceField()).isEqualTo("name.first");

        MemberLookupResult fhirLike = found("{\"name\":{\"given\":[\"Avery\",\"Quinn\"],\"family\":\"Placeholder\"},\"dob\":\"1970-01-31\"}");
        assertThat(fhirLike.member().firstName()).isEqualTo("Avery");
        assertThat(fhirLike.member().lastName()).isEqualTo("Placeholder");
        assertThat(field(fhirLike, "firstName").sourceField()).isEqualTo("name.given[0]");
        assertThat(field(fhirLike, "lastName").sourceField()).isEqualTo("name.family");
        // A second given name has no place on a member. It is not dropped.
        assertThat(fhirLike.additionalFields()).containsExactly(new AdditionalField("name.given[1]", Presence.VALUE, "\"Quinn\""));

        MemberLookupResult listOfOne = found("{\"name\":[{\"given\":[\"Avery\"],\"family\":\"Placeholder\"}],\"dob\":\"1970-01-31\"}");
        assertThat(listOfOne.member().firstName()).isEqualTo("Avery");
        assertThat(field(listOfOne, "lastName").sourceField()).isEqualTo("name[0].family");
    }

    @Test
    void severalNamesAreNeverChosenFrom() {
        MemberLookupResult result = found("{\"name\":[{\"family\":\"Placeholder\"},{\"family\":\"Formerly-Placeholder\"}],"
                + "\"dob\":\"1970-01-31\"}");
        assertThat(result.member().lastName()).isNull();
        assertThat(field(result, "lastName").value()).isEqualTo(Shown.absent());
        assertThat(paths(result)).containsExactly("name[0].family", "name[1].family");
    }

    @Test
    void aNameInOnePieceIsShownAsReturnedAndNeverSplit() {
        MemberLookupResult result = found("{\"name\":\"Placeholder, Avery\",\"dob\":\"1970-01-31\"}");
        assertThat(field(result, "fullName").value()).isEqualTo(Shown.of("Placeholder, Avery"));
        assertThat(field(result, "fullName").sourceField()).isEqualTo("name");
        assertThat(result.member().firstName()).isNull();
        assertThat(result.member().lastName()).isNull();
    }

    @Test
    void thePartsOfAnAddressAllComeFromOneObject() {
        // Two addresses, and the first lacks a city. The city of the other is not borrowed.
        MemberLookupResult result = found("{\"dob\":\"1970-01-31\",\"address\":{\"line1\":\"1 Placeholder Street\",\"state\":\"MA\"},"
                + "\"mailingAddress\":{\"line1\":\"PO Box 0\",\"city\":\"Elsewhere\",\"state\":\"NH\"},\"city\":\"Flatville\"}");
        assertThat(result.member().address().line1()).isEqualTo("1 Placeholder Street");
        assertThat(result.member().address().state()).isEqualTo("MA");
        assertThat(result.member().address().city()).isNull();
        assertThat(field(result, "city").value()).isEqualTo(Shown.absent());
        assertThat(paths(result)).containsExactly("mailingAddress.line1", "mailingAddress.city", "mailingAddress.state", "city");
    }

    // ---------------------------------------------------------------------------------------
    // The envelope

    @Test
    void theRecordIsFoundInEveryEnvelope() {
        Map<String, String> envelopes = new LinkedHashMap<>();
        envelopes.put(RECORD, "");
        envelopes.put("{\"data\":" + RECORD + "}", "data.");
        envelopes.put("{\"result\":" + RECORD + "}", "result.");
        envelopes.put("{\"member\":" + RECORD + "}", "member.");
        envelopes.put("[" + RECORD + "]", "[0].");
        envelopes.put("{\"data\":[" + RECORD + "]}", "data[0].");
        envelopes.put("{\"results\":[" + RECORD + "]}", "results[0].");
        envelopes.put("{\"members\":[" + RECORD + "]}", "members[0].");
        envelopes.put("{\"data\":{\"member\":" + RECORD + "}}", "data.member.");
        envelopes.put("{\"Data\":{\"Members\":[" + RECORD + "]}}", "Data.Members[0].");
        envelopes.put("[{\"result\":{\"member\":" + RECORD + "}}]", "[0].result.member.");
        envelopes.forEach((body, prefix) -> {
            MemberLookupResult result = found(body);
            assertThat(result.member().firstName()).as(body).isEqualTo("Avery");
            assertThat(result.member().lastName()).as(body).isEqualTo("Placeholder");
            assertThat(result.member().dateOfBirth()).as(body).isEqualTo("1970-01-31");
            assertThat(result.member().lineOfBusiness()).as(body).isEqualTo("Medicare");
            assertThat(field(result, "dateOfBirth").sourceField()).as(body).isEqualTo(prefix + "dob");
            assertThat(field(result, "memberId").sourceField()).as(body).isEqualTo(prefix + "memberId");
            assertThat(result.additionalFields()).as(body).isEmpty();
        });
    }

    @Test
    void whatSitsBesideTheRecordInItsWrapperStaysOnScreenAndIsNotReadAsTheMember() {
        MemberLookupResult result = found("{\"id\":\"req-0001\",\"status\":\"ok\",\"state\":\"DONE\",\"data\":{\"firstName\":\"Avery\"}}");
        assertThat(field(result, "memberId").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "coverageStatus").value()).isEqualTo(Shown.absent());
        assertThat(field(result, "state").value()).isEqualTo(Shown.absent());
        assertThat(result.additionalFields()).containsExactly(new AdditionalField("id", Presence.VALUE, "\"req-0001\""),
                new AdditionalField("status", Presence.VALUE, "\"ok\""), new AdditionalField("state", Presence.VALUE, "\"DONE\""));
    }

    @Test
    void aSingleValueUnderAWrapperNameIsAFieldAndNotAWrapper() {
        MemberLookupResult result = found("{\"result\":\"ok\",\"data\":7,\"memberId\":\"S123456789\",\"dob\":\"1970-01-31\"}");
        assertThat(field(result, "memberId").sourceField()).isEqualTo("memberId");
        assertThat(paths(result)).containsExactly("result", "data");
    }

    @Test
    void aGroupOfFieldsBesideAWrappedMemberIsStillRead() {
        MemberLookupResult result = found("{\"member\":{\"memberId\":\"S123456789\",\"firstName\":\"Avery\"},"
                + "\"coverage\":{\"status\":\"Termed\",\"lineOfBusiness\":\"Medicaid\",\"period\":{\"end\":\"2025-12-31\"}}}");
        assertThat(result.member().coverageStatus()).isEqualTo("Termed");
        assertThat(result.member().lineOfBusiness()).isEqualTo("Medicaid");
        assertThat(result.member().coverageEnd()).isEqualTo("2025-12-31");
        assertThat(field(result, "coverageStatus").sourceField()).isEqualTo("coverage.status");
        assertThat(result.attention()).containsExactly(
                "Coverage is not active: the member API returned the coverage status \"Termed\".",
                "Coverage ended on 2025-12-31, which is before today, 2026-03-10.");
    }

    @Test
    void anEnvelopeThatHoldsNoRecordIsNotFoundAndNeverAFault() {
        Map<String, String> empties = new LinkedHashMap<>();
        empties.put("{}", "(root)");
        empties.put("[]", "(root)");
        empties.put("null", "(root)");
        empties.put("{\"data\":null}", "data");
        empties.put("{\"data\":{}}", "data");
        empties.put("{\"data\":[]}", "data");
        empties.put("{\"member\":null}", "member");
        empties.put("{\"result\":{\"members\":[]}}", "result.members");
        empties.forEach((body, shownAt) -> {
            MemberLookupResult result = map(200, body);
            assertThat(result.status()).as(body).isEqualTo(LookupStatus.NOT_FOUND);
            assertThat(result.message()).as(body).isEqualTo("The member API answered, and its answer holds no member for this member ID.");
            assertThat(result.error()).as(body).isNull();
            assertThat(result.member()).as(body).isNull();
            assertThat(result.fields()).as(body).isEmpty();
            assertThat(result.attention()).as(body).isEmpty();
            // The emptiness itself is on screen, where it was found.
            assertThat(paths(result)).as(body).containsExactly(shownAt);
            assertThat(result.memberId()).as(body).isEqualTo(FORMS);
            assertThat(result.wire().response().status()).as(body).isEqualTo(200);
        });
    }

    @Test
    void aRecordWithNoValuesInItIsNotFound() {
        // The ID handed back with nothing else is no record.
        for (String body : List.of("{\"memberId\":\"S123456789\",\"firstName\":null,\"lastName\":\"\",\"address\":{}}",
                "{\"data\":{\"memberId\":\"S123456789\"}}", "{\"firstName\":null,\"dob\":\"  \"}")) {
            MemberLookupResult result = map(200, body);
            assertThat(result.status()).as(body).isEqualTo(LookupStatus.NOT_FOUND);
            assertThat(result.message()).as(body)
                    .isEqualTo("The member API answered with a record that holds no values, which this tool reads as not found.");
            assertThat(result.error()).as(body).isNull();
            assertThat(result.fields()).as(body).isEmpty();
        }
        // Everything that came back is listed, including what a first reading looked at.
        assertThat(paths(map(200, "{\"memberId\":\"S123456789\",\"firstName\":null,\"lastName\":\"\",\"address\":{}}")))
                .containsExactly("memberId", "firstName", "lastName", "address");
    }

    @Test
    void severalRecordsAreAFaultThatSaysHowManyCameBack() {
        MemberLookupResult two = map(200, "[" + RECORD + "," + RECORD.replace("Avery", "Blair") + "]");
        assertThat(two.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(two.message()).isEqualTo("The member API returned 2 records for one member ID, and this tool does not choose between them.");
        assertThat(two.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(two.error().httpStatus()).isEqualTo(200);
        assertThat(two.member()).isNull();
        assertThat(two.fields()).isEmpty();
        // Both records are on screen in full.
        assertThat(paths(two)).contains("[0].firstName", "[1].firstName").hasSize(10);

        MemberLookupResult three = map(200, "{\"data\":[" + RECORD + "," + RECORD + "," + RECORD + "]}");
        assertThat(three.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(three.message()).startsWith("The member API returned 3 records for one member ID");
    }

    @Test
    void anAnswerThatIsNotAMemberIsAFaultWithEverythingItSaidOnScreen() {
        Map<String, List<String>> answers = new LinkedHashMap<>();
        answers.put("{\"message\":\"No member matches\",\"code\":\"E404\"}", List.of("message", "code"));
        // The ID handed back beside a field this tool does not know. It cannot be called found, and is not called not found either.
        answers.put("{\"memberId\":\"S123456789\",\"found\":false}", List.of("memberId", "found"));
        // Another ID and nothing else. An ID on its own, whoever's it is, says nothing about a member.
        answers.put("{\"memberId\":\"S999999999\"}", List.of("memberId"));
        answers.put("{\"name\":\"NotFoundError\",\"state\":\"failed\"}", List.of("name", "state"));
        // An empty page of results, as one common framework writes it. Its first and last are not a name.
        answers.put("{\"content\":[],\"first\":true,\"last\":true,\"totalElements\":0}", List.of("content", "first", "last", "totalElements"));
        answers.put("{\"data\":{\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"plan\":\"all\"}}",
                List.of("data.startDate", "data.endDate", "data.plan"));
        answers.forEach((body, shown) -> {
            MemberLookupResult result = map(200, body);
            assertThat(result.status()).as(body).isEqualTo(LookupStatus.FAULT);
            assertThat(result.message()).as(body)
                    .isEqualTo("The member API answered, but this tool could read nothing about a member from its answer.");
            assertThat(result.error().kind()).as(body).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
            assertThat(result.error().action()).as(body).contains("not final").contains("type the member details by hand")
                    .contains("The member ID on its own is not taken as a member.");
            assertThat(result.member()).as(body).isNull();
            assertThat(result.fields()).as(body).isEmpty();
            assertThat(paths(result)).as(body).containsExactlyElementsOf(shown);
        });
    }

    @Test
    void aSingleValueWhereARecordIsExpectedIsAFault() {
        MemberLookupResult result = map(200, "\"S123456789\"");
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.message()).isEqualTo("The member API answered with JSON that this tool cannot read as one member record.");
        assertThat(result.additionalFields()).containsExactly(new AdditionalField("(root)", Presence.VALUE, "\"S123456789\""));
    }

    // ---------------------------------------------------------------------------------------
    // Nothing silently dropped

    @Test
    void aFieldTheMappingWasNeverToldAboutReachesTheReturnedFields() {
        MemberLookupResult result = found("{\"data\":{\"memberId\":\"S123456789\",\"firstName\":\"Avery\",\"loyaltyTier\":\"gold\","
                + "\"pcp\":{\"npi\":\"1234567893\",\"panel\":[]},\"deceased\":null},\"traceId\":\"trace-0001\"}");
        assertThat(result.additionalFields()).containsExactly(
                new AdditionalField("data.loyaltyTier", Presence.VALUE, "\"gold\""),
                new AdditionalField("data.pcp.npi", Presence.VALUE, "\"1234567893\""),
                new AdditionalField("data.pcp.panel", Presence.EMPTY, "[]"),
                new AdditionalField("data.deceased", Presence.NULL, null),
                new AdditionalField("traceId", Presence.VALUE, "\"trace-0001\""));
        // What the mapping did read is not listed a second time.
        assertThat(paths(result)).doesNotContain("data.memberId", "data.firstName");
    }

    @Test
    void aNoteAboutTheBodyComesBeforeTheFieldsOfTheBody() {
        MemberLookupResult result = found("{\"memberId\":\"S123456789\",\"dob\":\"1970-01-31\",\"dob\":\"1971-02-01\",\"extra\":1}");
        assertThat(result.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.NOTE);
        assertThat(result.additionalFields().get(0).value()).contains("repeats a key");
        assertThat(result.additionalFields().get(1)).isEqualTo(new AdditionalField("extra", Presence.VALUE, "1"));
    }

    // ---------------------------------------------------------------------------------------
    // Dates

    @Test
    void aDateInAnyOfTheFourShapesIsGivenInIsoForm() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("\"1970-01-31\"", "1970-01-31");
        shapes.put("\"1970-01-31T00:00:00Z\"", "1970-01-31");
        shapes.put("\"1970-01-31T05:00:00.000+00:00\"", "1970-01-31");
        // The date as written. It is not moved to another time zone, which would make it the first of February.
        shapes.put("\"1970-01-31T23:30:00-05:00\"", "1970-01-31");
        shapes.put("\"1970-01-31 00:00:00\"", "1970-01-31");
        shapes.put("\"01/31/1970\"", "1970-01-31");
        shapes.put("\"1/31/1970\"", "1970-01-31");
        shapes.put("\"19700131\"", "1970-01-31");
        shapes.put("19700131", "1970-01-31");
        shapes.put("\" 1970-01-31 \"", "1970-01-31");
        shapes.forEach((written, iso) -> {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"dob\":" + written + "}");
            assertThat(result.member().dateOfBirth()).as(written).isEqualTo(iso);
            assertThat(result.attention()).as(written).isEmpty();
        });
        // The screen shows what the API returned. The member carries the date in ISO form.
        MemberLookupResult result = found("{\"firstName\":\"Avery\",\"dob\":\"01/31/1970\"}");
        assertThat(field(result, "dateOfBirth").value()).isEqualTo(Shown.of("01/31/1970"));
    }

    @Test
    void aDateInAnyOtherShapeIsKeptAsReceivedAndSaidToBeSo() {
        for (String written : List.of("31-Jan-1970", "31/01/1970", "02/30/1970", "1970-13-01", "1/31/70", "January 31, 1970", "0",
                "1970-01-31T99:00:00Z", "1970-1-31")) {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"dob\":\"" + written + "\"}");
            assertThat(result.member().dateOfBirth()).as(written).isEqualTo(written);
            assertThat(result.attention()).as(written).containsExactly(
                    "The date of birth is not written in a form this tool reads as a date, so it is shown as received.");
        }
        // A coverage date that cannot be read was not compared, and the result says that too.
        MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStart\":\"start of year\",\"coverageEnd\":\"end of year\"}");
        assertThat(result.member().coverageStart()).isEqualTo("start of year");
        assertThat(result.member().coverageEnd()).isEqualTo("end of year");
        assertThat(result.attention()).containsExactly(
                "The coverage start date is not written in a form this tool reads as a date, so it is shown as received and was not "
                        + "compared with today's date.",
                "The coverage end date is not written in a form this tool reads as a date, so it is shown as received and was not "
                        + "compared with today's date.");
    }

    // ---------------------------------------------------------------------------------------
    // Needs a look

    @Test
    void aMemberWhoseCoverageIsNotActiveIsStillFoundWithAReasonToLook() {
        for (String status : List.of("Inactive", "INACTIVE", "Termed", "terminated", "Cancelled", "canceled", "Expired", "Not Active",
                "not-active", "NOT_ACTIVE", "lapsed", "Suspended", "disenrolled", "draft", "entered-in-error", "Ended")) {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"" + status + "\"}");
            assertThat(result.attention()).as(status)
                    .containsExactly("Coverage is not active: the member API returned the coverage status \"" + status + "\".");
            assertThat(result.message()).as(status).isEqualTo("Member found, with 1 thing to check before using this member.");
            // Kept as the API wrote it.
            assertThat(result.member().coverageStatus()).as(status).isEqualTo(status);
            assertThat(result.error()).as(status).isNull();
        }
    }

    @Test
    void anActiveStatusRaisesNothing() {
        for (String status : List.of("Active", "ACTIVE", " active ")) {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"" + status + "\",\"coverageEnd\":\"2099-12-31\"}");
            assertThat(result.attention()).as(status).isEmpty();
            assertThat(result.message()).as(status).isEqualTo("Member found, with coverage reported as active.");
        }
    }

    @Test
    void aStatusThisToolDoesNotRecogniseIsNeitherActiveNorInactive() {
        for (String status : List.of("Pending review", "Eligible", "A", "true", "COBRA")) {
            MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"" + status + "\"}");
            assertThat(result.attention()).as(status).containsExactly("The coverage status \"" + status
                    + "\" is not one this tool recognises, so it is treated as neither active nor inactive.");
            assertThat(result.member().coverageStatus()).as(status).isEqualTo(status);
            assertThat(result.message()).as(status).doesNotContain("active");
        }
    }

    @Test
    void aCoverageEndDateThatHasPassedIsAReasonToLookEvenWhenTheStatusSaysActive() {
        MemberLookupResult ended = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"coverageEnd\":\"2026-03-09\"}");
        assertThat(ended.attention()).containsExactly("Coverage ended on 2026-03-09, which is before today, 2026-03-10.");
        assertThat(ended.status()).isEqualTo(LookupStatus.FOUND);
        // The last day of coverage is a day of coverage.
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"coverageEnd\":\"2026-03-10\"}").attention()).isEmpty();
        // Read in whichever shape it arrived.
        assertThat(found("{\"firstName\":\"Avery\",\"termDate\":\"12/31/2025\"}").attention())
                .containsExactly("Coverage ended on 2025-12-31, which is before today, 2026-03-10.");
        // Both reasons are given when both hold.
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Termed\",\"coverageEnd\":\"20251231\"}").attention()).hasSize(2);
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Termed\",\"coverageEnd\":\"20251231\"}").message())
                .isEqualTo("Member found, with 2 things to check before using this member.");
    }

    @Test
    void coverageThatHasNotStartedIsAReasonToLook() {
        MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"effectiveDate\":\"2026-04-01\"}");
        assertThat(result.attention()).containsExactly("Coverage does not start until 2026-04-01, which is after today, 2026-03-10.");
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"effectiveDate\":\"2026-03-10\"}").attention()).isEmpty();
    }

    @Test
    void aYesOrNoAboutCoverageIsReadAsOne() {
        MemberLookupResult no = found("{\"firstName\":\"Avery\",\"coverageActive\":false}");
        assertThat(no.attention()).containsExactly("Coverage is not active: the member API returned coverageActive as false.");
        // The member carries the word for the answer. The field keeps what was returned.
        assertThat(no.member().coverageStatus()).isEqualTo("inactive");
        assertThat(field(no, "coverageActive").value()).isEqualTo(Shown.of("false"));
        MemberLookupResult yes = found("{\"firstName\":\"Avery\",\"coverage\":{\"isActive\":\"Y\"}}");
        assertThat(yes.attention()).isEmpty();
        assertThat(yes.message()).isEqualTo("Member found, with coverage reported as active.");
        assertThat(yes.member().coverageStatus()).isEqualTo("active");
        assertThat(field(yes, "coverageActive").value()).isEqualTo(Shown.of("Y"));
        MemberLookupResult unclear = found("{\"firstName\":\"Avery\",\"coverageActive\":\"maybe\"}");
        assertThat(unclear.attention()).containsExactly("The value \"maybe\" of coverageActive is not a yes or a no this tool recognises, "
                + "so it says nothing about whether coverage is active.");
        // Not a yes or a no, so it is kept as written and not turned into either word.
        assertThat(unclear.member().coverageStatus()).isEqualTo("maybe");
        // A status in words beside a yes or no is the one the member carries.
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"coverageActive\":true}").member().coverageStatus())
                .isEqualTo("Active");
    }

    @Test
    void aStatusAndAYesOrNoThatContradictEachOtherAreSaidToAndCoverageIsNotCalledActive() {
        MemberLookupResult result = found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"coverageActive\":false}");
        assertThat(result.attention()).containsExactly("The member API contradicts itself about coverage: it returned the coverage "
                + "status \"Active\" and coverageActive as false. Coverage is not taken to be active.");
        assertThat(result.message()).isEqualTo("Member found, with 1 thing to check before using this member.");
        // Both are in the designed view, each with where it came from.
        assertThat(field(result, "coverageStatus").value()).isEqualTo(Shown.of("Active"));
        assertThat(field(result, "coverageActive").value()).isEqualTo(Shown.of("false"));
        // When they agree there is nothing to say.
        assertThat(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\",\"coverageActive\":true}").attention()).isEmpty();
    }

    @Test
    void aRecordReturnedUnderAnotherIdIsAReasonToLookAndTheIdTypedIsTheOneKept() {
        MemberLookupResult other = found("{\"memberId\":\"S999999999\",\"firstName\":\"Avery\"}");
        assertThat(other.attention()).containsExactly("The member API returned a record under a different member ID from the one that was "
                + "asked for. Check that this is the member you meant. The ID that was typed is the one used.");
        assertThat(other.member().memberId()).isEqualTo("S123456789");
        assertThat(field(other, "memberId").value()).isEqualTo(Shown.of("S999999999"));

        MemberIdForms lowerCase = MemberIds.forms("s123456789");
        MemberLookupResult caseOnly = MemberResponseMapping.map(TestExchanges.of(EpaOperation.MEMBER_LOOKUP, 200,
                "{\"memberId\":\"S123456789\",\"firstName\":\"Avery\"}", true), lowerCase, TODAY);
        assertThat(caseOnly.attention()).containsExactly("The member API writes this member ID in different letter case from the ID that "
                + "was typed. The ID is sent as typed.");
        assertThat(caseOnly.member().memberId()).isEqualTo("s123456789");
    }

    @Test
    void anIdTheApiWritesWithSpacesIsTheSameIdAsItsNoSpaceForm() {
        MemberIdForms spaced = MemberIds.forms("S12345678   01");
        MemberLookupResult result = MemberResponseMapping.map(TestExchanges.of(EpaOperation.MEMBER_LOOKUP, 200,
                "{\"memberId\":\"S12345678   01\",\"firstName\":\"Avery\",\"lob\":\"Medicare\"}", true), spaced, TODAY);
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.attention()).isEmpty();
        // The member carries the form that is sent. Both forms are on the result and in the wire view.
        assertThat(result.member().memberId()).isEqualTo("S1234567801");
        assertThat(result.memberId()).isEqualTo(new MemberIdForms("S12345678   01", "S1234567801", true));
        assertThat(result.wire().memberId()).isEqualTo(result.memberId());
        assertThat(field(result, "memberId").value()).isEqualTo(Shown.of("S12345678   01"));
    }

    // ---------------------------------------------------------------------------------------
    // Not found and fault, by how the call ended

    @Test
    void anHttp404IsNotFoundWithNoErrorPanelAndItsBodyOnScreen() {
        MemberLookupResult result = map(404, "{\"error\":\"not_found\",\"detail\":\"No member matches the identifier supplied.\"}");
        assertThat(result.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(result.message()).isEqualTo("The member API does not know this member ID (it answered HTTP 404).");
        assertThat(result.error()).isNull();
        assertThat(result.member()).isNull();
        assertThat(paths(result)).containsExactly("error", "detail");
        assertThat(result.wire().response().status()).isEqualTo(404);
        assertThat(result.wire().memberId()).isEqualTo(FORMS);
        // With no body at all it is the same answer.
        MemberLookupResult bare = map(404, null);
        assertThat(bare.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(bare.additionalFields()).isEmpty();
    }

    @Test
    void everyOtherRejectionIsAFaultThatSaysWhich() {
        MemberLookupResult badRequest = map(400, "{\"error\":\"invalid_request\"}");
        assertThat(badRequest.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(badRequest.message()).isEqualTo("The member API did not accept the lookup and answered HTTP 400.");
        assertThat(badRequest.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(badRequest.error().httpStatus()).isEqualTo(400);
        assertThat(badRequest.error().title()).isEqualTo(badRequest.message());
        assertThat(paths(badRequest)).containsExactly("error");

        for (int status : List.of(401, 403)) {
            MemberLookupResult refused = map(status, null);
            assertThat(refused.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(refused.message()).isEqualTo("The member API refused the lookup with HTTP " + status
                    + ", which means the credentials were not accepted or none were sent.");
            assertThat(refused.error().action()).contains("without an Authorization header").contains("lookup.member.headers")
                    .contains("lookup.member.use-onyx-auth");
        }

        MemberLookupResult redirect = map(302, null);
        assertThat(redirect.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(redirect.message()).isEqualTo("The member API answered the lookup with a redirect, HTTP 302, which this tool does not follow.");
        assertThat(redirect.error().action()).contains("lookup.member.url");
    }

    @Test
    void aServerErrorIsAFaultAboutTheEnvironmentAndItsBodyIsKept() {
        MemberLookupResult result = map(503, "<html>Service Unavailable</html>");
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.message()).isEqualTo("The member API failed with HTTP 503.");
        assertThat(result.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(result.error().httpStatus()).isEqualTo(503);
        assertThat(result.error().action()).contains("fault in the environment").contains("Type the member details by hand.");
        // The body could not be parsed, so it is shown as received, first.
        assertThat(result.additionalFields()).containsExactly(AdditionalField.unparsedBody("<html>Service Unavailable</html>"));
        assertThat(result.wire().response().body()).isEqualTo("<html>Service Unavailable</html>");
    }

    @Test
    void aTimeoutAndAnUnreachableHostAreDifferentFaults() {
        MemberLookupResult timeout = MemberResponseMapping.map(TestExchanges.timeout(EpaOperation.MEMBER_LOOKUP), FORMS, TODAY);
        assertThat(timeout.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(timeout.message()).isEqualTo("The member API did not answer inside the read timeout.");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.error().httpStatus()).isNull();
        assertThat(timeout.wire().response().status()).isNull();
        assertThat(timeout.wire().request()).isNotNull();

        MemberLookupResult unreachable = MemberResponseMapping.map(TestExchanges.unavailable(EpaOperation.MEMBER_LOOKUP), FORMS, TODAY);
        assertThat(unreachable.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(unreachable.message()).isEqualTo("The member API could not be reached.");
        assertThat(unreachable.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        assertThat(unreachable.error().title())
                .isEqualTo("The member API could not be reached. The connection was refused or could not be opened.");
        assertThat(unreachable.error().action()).contains("lookup.member.url");
    }

    @Test
    void aBodyThatCannotBeReadIsAFaultAndIsShownAsReceived() {
        MemberLookupResult malformed = map(200, "{\"memberId\": \"S1234");
        assertThat(malformed.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(malformed.message()).isEqualTo("The member API answered HTTP 200 with a body that is not JSON.");
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(malformed.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"memberId\": \"S1234"));
        assertThat(malformed.wire().response().parsed()).isFalse();

        for (int status : List.of(200, 204)) {
            MemberLookupResult empty = map(status, null);
            assertThat(empty.status()).as("an empty answer is not taken as not found").isEqualTo(LookupStatus.FAULT);
            assertThat(empty.message()).isEqualTo("The member API answered HTTP " + status
                    + " with an empty body, so there is nothing to read a member from.");
            assertThat(empty.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
            assertThat(empty.error().action()).contains("not reported as not found");
            assertThat(empty.additionalFields()).isEmpty();
        }

        // A body of nothing but white space holds no record either. It is called blank, not empty, and is still on screen as received.
        MemberLookupResult blank = map(200, " \n ");
        assertThat(blank.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(blank.message()).isEqualTo("The member API answered HTTP 200 with a blank body, so there is nothing to read a member from.");
        assertThat(blank.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(blank.error().action()).contains("not reported as not found");
        assertThat(blank.additionalFields()).containsExactly(AdditionalField.unparsedBody(" \n "));
    }

    @Test
    void anAnswerThatStartedAndDidNotFinishIsAFaultThatKeepsWhatArrived() {
        VendorExchange sent = TestExchanges.of(EpaOperation.MEMBER_LOOKUP, 200, "{}", true);
        for (int status : List.of(200, 404, 503)) {
            VendorExchange cut = Exchanges.interrupted(sent.request(), status, List.of(), "{\"memberId\":\"S12".getBytes(StandardCharsets.UTF_8),
                    "application/json", 40L);
            MemberLookupResult result = MemberResponseMapping.map(cut, FORMS, TODAY);
            // Even with 404 on the status line. An answer that stopped part way is not taken as an answer.
            assertThat(result.status()).as("HTTP " + status).isEqualTo(LookupStatus.FAULT);
            assertThat(result.message()).isEqualTo("The member API started to answer with HTTP " + status
                    + " and the answer did not arrive in full.");
            assertThat(result.error().httpStatus()).isEqualTo(status);
            assertThat(result.additionalFields().get(0)).isEqualTo(AdditionalField.unparsedBody("{\"memberId\":\"S12"));
            assertThat(result.additionalFields().get(1).kind()).isEqualTo(AdditionalField.Kind.NOTE);
        }
    }

    @Test
    void anOperationOutcomeIsReadIssueByIssueAndNotListedTwice() {
        MemberLookupResult result = map(400, "{\"resourceType\":\"OperationOutcome\",\"id\":\"oo-1\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"invalid\",\"diagnostics\":\"The identifier is not in a form this service accepts.\"}]}");
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.error().issues()).hasSize(1);
        assertThat(result.error().issues().get(0).code()).isEqualTo(Shown.of("invalid"));
        assertThat(result.error().issues().get(0).diagnostics().value()).contains("not in a form");
        assertThat(paths(result)).containsExactly("id");
    }

    // ---------------------------------------------------------------------------------------
    // What a result carries, whatever its state

    @Test
    void everyStateHasItsOwnSentenceAndAlwaysBothFormsOfTheId() {
        List<MemberLookupResult> results = new ArrayList<>();
        results.add(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Active\"}"));
        results.add(found("{\"firstName\":\"Avery\"}"));
        results.add(found("{\"firstName\":\"Avery\",\"coverageStatus\":\"Termed\"}"));
        results.add(map(404, null));
        results.add(map(200, "{}"));
        results.add(map(200, "{\"firstName\":null}"));
        results.add(map(500, null));
        results.add(map(400, null));
        results.add(map(200, null));
        results.add(map(200, "not json"));
        results.add(map(200, "[" + RECORD + "," + RECORD + "]"));
        results.add(MemberResponseMapping.map(TestExchanges.timeout(EpaOperation.MEMBER_LOOKUP), FORMS, TODAY));
        results.add(MemberResponseMapping.map(TestExchanges.unavailable(EpaOperation.MEMBER_LOOKUP), FORMS, TODAY));
        assertThat(results).extracting(MemberLookupResult::message).doesNotHaveDuplicates().allSatisfy(message -> {
            // One sentence, which names no member.
            assertThat(message).endsWith(".").doesNotContain("Avery").doesNotContain("S123456789");
            assertThat(message.indexOf(". ")).as(message).isEqualTo(-1);
        });
        assertThat(results).allSatisfy(result -> {
            assertThat(result.memberId()).isEqualTo(FORMS);
            assertThat(result.wire().memberId()).isEqualTo(FORMS);
            assertThat(result.wire().request()).isNotNull();
        });
        // Found and not found never carry an error panel. A fault always does.
        assertThat(results).filteredOn(result -> result.status() != LookupStatus.FAULT).allSatisfy(result -> assertThat(result.error()).isNull());
        assertThat(results).filteredOn(result -> result.status() == LookupStatus.FAULT).hasSize(7)
                .allSatisfy(result -> assertThat(result.error().title()).isNotBlank());
    }

    @Test
    void nothingAboutAMemberIsPrintedByAReadOrAResult() {
        MemberLookupResult result = found(RECORD);
        assertThat(result.toString()).doesNotContain("Avery").doesNotContain("Placeholder").doesNotContain("1970").doesNotContain("S123456789");
        assertThat(result.fields().toString()).doesNotContain("Avery").doesNotContain("1970-01-31");
        LookupReader.Read read = new LookupReader.Read(Shown.of("Placeholder"), "Placeholder", "lastName", Spelling.of("lastName"));
        assertThat(read.toString()).isEqualTo("Read[VALUE]");
    }
}
