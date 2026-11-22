package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import tools.jackson.databind.JsonNode;

/**
 * The audit of the synthetic fixtures, driven through the real loader, the real mock clients and
 * the real mappings. In an earlier build of this tool three of five hand-typed synthetic NPIs
 * failed the application's own check. These tests are what stops that, and what keeps anything
 * that could pass for a real member out of the mock profile.
 */
class LookupFixtureAuditTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    // Written here on their own, and not taken from the loader, so the audit does not check the loader against itself.
    private static final Pattern SENTINEL = Pattern.compile("^P32H-[A-Z0-9]+(-[A-Z0-9]+)*$");
    private static final Pattern PLACEHOLDER = Pattern.compile("^S\\d{9,10}$");
    private static final Pattern INVENTED_PHONE = Pattern.compile("^\\d{3}-555-01\\d{2}$");

    /** The sentinel member IDs of the 14 CRD scenarios. The seventh, member not found, is the one with no fixture. */
    private static final List<String> SENTINELS_WITH_A_MEMBER = List.of("P32H-PA-REQ", "P32H-NO-AUTH", "P32H-PA-SAT", "P32H-DOC-DTR",
            "P32H-INFO", "P32H-NOCOV", "P32H-INACTIVE", "P32H-NOPROV", "P32H-DELEG", "P32H-BAD400", "P32H-ERR500", "P32H-TIMEOUT",
            "P32H-EMPTY");
    private static final String MEMBER_NOT_FOUND_SENTINEL = "P32H-NOMEM";
    private static final String PROVIDER_NOT_FOUND_SENTINEL = "9999999995";
    private static final Set<String> LINES_OF_BUSINESS = Set.of("Medicare", "Medicaid", "Commercial");

    /** Which lines each current delegated vendor serves. Carelon and eviCore serve public plans only, Optum Post Acute Medicare only, Evolent both. */
    private static final Map<String, Set<String>> SERVED_BY = Map.of("eviCore", Set.of("Medicare", "Medicaid"),
            "Carelon", Set.of("Medicare", "Medicaid"), "Optum Post Acute", Set.of("Medicare"),
            "Evolent", Set.of("Medicare", "Medicaid", "Commercial"));

    private static final LookupFixtures FIXTURES = new LookupFixtures();
    private static final Map<String, JsonNode> MEMBER_FILES = FixtureFiles.read(LookupFixtures.MEMBERS);
    private static final Map<String, JsonNode> PROVIDER_FILES = FixtureFiles.read(LookupFixtures.PROVIDERS);

    private final MockMemberLookupClient members = new MockMemberLookupClient(FIXTURES, LookupProperties.defaults(), CLOCK);
    private final MockProviderLookupClient providers = new MockProviderLookupClient(FIXTURES, LookupProperties.defaults());

    private MemberLookupResult member(String typed) {
        return members.lookup(MemberIds.forms(typed));
    }

    @Test
    void everyFixtureMemberIdIsASentinelOrAPlaceholder() {
        assertThat(MEMBER_FILES).as("the audit found the member fixture files").hasSizeGreaterThanOrEqualTo(19);
        MEMBER_FILES.forEach((file, json) -> {
            String id = JsonSafe.text(json, "memberId");
            assertThat(id).as(file).isNotBlank();
            assertThat(SENTINEL.matcher(id).matches() || PLACEHOLDER.matcher(id).matches())
                    .as(file + ": the member ID must be a P32H- sentinel or an S123456789 style placeholder").isTrue();
        });
        // Every file is one the loader serves. None was skipped.
        assertThat(FIXTURES.members()).hasSize(MEMBER_FILES.size());
    }

    @Test
    void everyFixtureNpiPassesTheApplicationsOwnCheckAndWasMadeWithItsRule() {
        assertThat(PROVIDER_FILES).as("the audit found the provider fixture files").hasSizeGreaterThanOrEqualTo(7);
        PROVIDER_FILES.forEach((file, json) -> {
            String npi = JsonSafe.text(json, "npi");
            assertThat(npi).as(file).matches("\\d{10}");
            assertThat(Npi.isValid(npi)).as(file + ": the NPI must pass the check the application applies").isTrue();
            assertThat(npi).as(file).isEqualTo(Npi.withCheckDigit(npi.substring(0, 9)));
            // An NPI the response itself carries is the same kind of number.
            for (String inResponse : valuesUnder(json.get("response"), Set.of("npi", "number", "npinumber", "providernpi"))) {
                assertThat(Npi.isValid(inResponse)).as(file + ": an NPI inside the response").isTrue();
            }
        });
        assertThat(FIXTURES.providers()).hasSize(PROVIDER_FILES.size());
        // The two NPIs the documentation leans on.
        assertThat(FIXTURES.provider("1234567893")).isNotNull();
        assertThat(Npi.isValid(PROVIDER_NOT_FOUND_SENTINEL)).isTrue();
    }

    @Test
    void nothingInsideAFixtureCouldPassForARealMemberOrProvider() {
        List<Map<String, JsonNode>> all = List.of(MEMBER_FILES, PROVIDER_FILES);
        for (Map<String, JsonNode> files : all) {
            files.forEach((file, json) -> {
                JsonNode response = json.get("response");
                for (String id : valuesUnder(response, Set.of("memberid", "membernumber", "memberidentifier", "memberno", "subscriberid",
                        "subscribernumber", "subscriberidentifier"))) {
                    String noSpace = MemberIds.noSpace(id);
                    assertThat(SENTINEL.matcher(noSpace).matches() || PLACEHOLDER.matcher(noSpace).matches())
                            .as(file + ": an ID inside the response must be a sentinel or a placeholder").isTrue();
                }
                for (String surname : valuesUnder(response, Set.of("lastname", "familyname", "family", "last", "surname"))) {
                    assertThat(surname).as(file + ": a surname must say it is a placeholder").contains("Placeholder");
                }
                for (String organization : valuesUnder(response, Set.of("organizationname"))) {
                    assertThat(organization).as(file + ": an organization must say it is a placeholder").contains("Placeholder");
                }
                for (String postalCode : valuesUnder(response, Set.of("postalcode", "zip", "zipcode"))) {
                    assertThat(postalCode).as(file + ": a postal code no post office uses").isEqualTo("00000");
                }
                for (String phone : valuesUnder(response, Set.of("phone", "phonenumber", "telephone", "telephonenumber"))) {
                    assertThat(phone).as(file + ": a phone number from the range kept for fiction").matches(INVENTED_PHONE);
                }
                assertThat(JsonSafe.text(json, "basis")).as(file + ": says where its content came from").startsWith("Synthetic.");
                assertThat(JsonSafe.text(json, "description")).as(file + ": says what it is for").isNotBlank();
            });
        }
    }

    @Test
    void everyCrdSentinelHasAMemberAndTheMemberNotFoundSentinelHasNone() {
        for (String sentinel : SENTINELS_WITH_A_MEMBER) {
            assertThat(member(sentinel).status()).as(sentinel).isEqualTo(LookupStatus.FOUND);
        }
        assertThat(FIXTURES.member(MEMBER_NOT_FOUND_SENTINEL)).isNull();
        MemberLookupResult notFound = member(MEMBER_NOT_FOUND_SENTINEL);
        assertThat(notFound.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(notFound.error()).isNull();

        // And the same from the scenario files themselves, so a sentinel added to one is not forgotten here.
        Set<String> inScenarioFiles = new LinkedHashSet<>();
        for (Scenario scenario : new ScenarioRepository(AppProperties.defaults()).list("crd")) {
            if (scenario.sentinelMemberId() == null) {
                continue;
            }
            inScenarioFiles.add(scenario.sentinelMemberId());
            boolean memberNotFound = scenario.expect() != null && "MEMBER_NOT_FOUND".equals(scenario.expect().outcome());
            assertThat(member(scenario.sentinelMemberId()).status()).as(scenario.source())
                    .isEqualTo(memberNotFound ? LookupStatus.NOT_FOUND : LookupStatus.FOUND);
        }
        List<String> fromTheSpecification = new ArrayList<>(SENTINELS_WITH_A_MEMBER);
        fromTheSpecification.add(MEMBER_NOT_FOUND_SENTINEL);
        assertThat(inScenarioFiles).containsExactlyInAnyOrderElementsOf(fromTheSpecification);
    }

    @Test
    void everySentinelMemberCanFillTheFormAndStatesItsLineOfBusiness() {
        for (String sentinel : SENTINELS_WITH_A_MEMBER) {
            MemberLookupResult result = member(sentinel);
            assertThat(result.member().memberId()).as(sentinel).isEqualTo(sentinel);
            assertThat(result.member().firstName()).as(sentinel).isNotBlank();
            assertThat(result.member().lastName()).as(sentinel).contains("Placeholder");
            LocalDate dateOfBirth = IsoDates.parse(result.member().dateOfBirth());
            assertThat(dateOfBirth).as(sentinel + ": a date of birth in ISO form").isNotNull();
            assertThat(dateOfBirth).as(sentinel).isBefore(TODAY);
            assertThat(result.member().lineOfBusiness()).as(sentinel).isIn(LINES_OF_BUSINESS);
            // The ID the fixture returns is the ID that was asked for, so no fixture raises a mismatch by accident.
            assertThat(result.attention()).as(sentinel).noneMatch(reason -> reason.contains("member ID"));
        }
        // Only the coverage-inactive sentinel needs a look. That state is what it is for.
        for (String sentinel : SENTINELS_WITH_A_MEMBER) {
            assertThat(member(sentinel).attention().isEmpty()).as(sentinel).isEqualTo(!sentinel.equals("P32H-INACTIVE"));
        }
        assertThat(member("P32H-INACTIVE").attention()).containsExactly(
                "Coverage is not active: the member API returned the coverage status \"Termed\".",
                "Coverage ended on 2024-12-31, which is before today, 2026-03-10.");
    }

    @Test
    void whatASentinelMemberCarriesIsWhatTheRequestBuilderCanSend() {
        // A sentinel is typed to drive a CRD run. Its gender and its coverage status go into that
        // request, so each must be one the request builder turns into a code and does not leave out.
        for (String sentinel : SENTINELS_WITH_A_MEMBER) {
            MemberLookupResult result = member(sentinel);
            if (result.member().gender() != null) {
                assertThat(CaseResources.gender(result.member().gender())).as(sentinel + ": gender").isNotNull();
            }
            if (result.member().coverageStatus() != null && result.attention().isEmpty()) {
                assertThat(CaseResources.coverageStatus(result.member().coverageStatus())).as(sentinel + ": coverage status").isEqualTo("active");
            }
        }
        // Coverage stated as a yes reaches the member as the word, so the request can carry it.
        assertThat(member("P32H-DOC-DTR").member().coverageStatus()).isEqualTo("active");
        assertThat(member("P32H-ERR500").member().coverageStatus()).isEqualTo("active");
        // The one sentinel that returns no gender worth sending and no coverage status says so by carrying none.
        assertThat(member("P32H-BAD400").member().gender()).isNull();
        assertThat(member("P32H-BAD400").member().coverageStatus()).isNull();
    }

    @Test
    void theDelegatedVendorSentinelIsAMemberOfALineItsVendorServes() {
        Scenario delegated = new ScenarioRepository(AppProperties.defaults()).list("crd").stream()
                .filter(scenario -> "P32H-DELEG".equals(scenario.sentinelMemberId())).findFirst().orElseThrow();
        String vendor = delegated.expect().delegatedVendor();
        assertThat(SERVED_BY).as("a current delegated vendor").containsKey(vendor);
        assertThat(member("P32H-DELEG").member().lineOfBusiness()).isIn(SERVED_BY.get(vendor));
    }

    @Test
    void thePlaceholdersCoverEveryLineOfBusiness() {
        Set<String> lines = new LinkedHashSet<>();
        MEMBER_FILES.forEach((file, json) -> {
            String id = JsonSafe.text(json, "memberId");
            if (PLACEHOLDER.matcher(id).matches()) {
                MemberLookupResult result = member(id);
                if (result.status() == LookupStatus.FOUND && result.member().lineOfBusiness() != null) {
                    lines.add(result.member().lineOfBusiness());
                }
            }
        });
        assertThat(lines).containsExactlyInAnyOrderElementsOf(LINES_OF_BUSINESS);
    }

    @Test
    void aMedicareIdWrittenWithSpacesIsKeyedByItsNoSpaceForm() {
        assertThat(MEMBER_FILES.get("s1234567801.json").get("memberId").asString()).isEqualTo("S1234567801");
        MemberLookupResult typedWithSpaces = member("S12345678   01");
        assertThat(typedWithSpaces.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(typedWithSpaces.memberId().typed()).isEqualTo("S12345678   01");
        assertThat(typedWithSpaces.memberId().sent()).isEqualTo("S1234567801");
        assertThat(typedWithSpaces.memberId().changed()).isTrue();
        assertThat(typedWithSpaces.member().memberId()).isEqualTo("S1234567801");
        assertThat(typedWithSpaces.member().lineOfBusiness()).isEqualTo("Medicare");
        // The source writes the ID with its spaces. That is the same ID, so it is no reason to look.
        assertThat(typedWithSpaces.attention()).isEmpty();
        // Typed without spaces it is the same member through the same path.
        MemberLookupResult typedWithout = member("S1234567801");
        assertThat(typedWithout.member()).isEqualTo(typedWithSpaces.member());
        assertThat(typedWithout.memberId().changed()).isFalse();
        // And an unspaced public plan ID goes the same way.
        assertThat(member("S123456789").member().lineOfBusiness()).isEqualTo("Medicaid");
    }

    @Test
    void everyMemberFixtureProducesWhatItClaims() {
        MEMBER_FILES.forEach((file, json) -> {
            JsonNode expect = json.get("expect");
            assertThat(expect).as(file + ": every fixture states what it must produce").isNotNull();
            MemberLookupResult result = member(JsonSafe.text(json, "memberId"));
            assertThat(result.status().name()).as(file).isEqualTo(JsonSafe.text(expect, "status"));
            if (result.status() == LookupStatus.FOUND) {
                assertThat(JsonSafe.bool(expect, "needsALook")).as(file + ": a found fixture says whether it needs a look").isNotNull();
                assertThat(!result.attention().isEmpty()).as(file + ": needs a look").isEqualTo(JsonSafe.bool(expect, "needsALook"));
                assertThat(result.member().lineOfBusiness()).as(file).isEqualTo(JsonSafe.text(expect, "lineOfBusiness"));
                assertThat(result.error()).as(file).isNull();
            } else {
                assertThat(result.member()).as(file).isNull();
            }
            assertThat(result.wire().request().sent()).as(file + ": nothing leaves this machine on mock").isFalse();
            assertThat(result.wire().request().note()).as(file).contains(file);
        });
    }

    @Test
    void everyProviderFixtureProducesWhatItClaims() {
        PROVIDER_FILES.forEach((file, json) -> {
            JsonNode expect = json.get("expect");
            assertThat(expect).as(file + ": every fixture states what it must produce").isNotNull();
            String npi = JsonSafe.text(json, "npi");
            ProviderLookupResult result = providers.lookup(npi);
            assertThat(result.status().name()).as(file).isEqualTo(JsonSafe.text(expect, "status"));
            if (result.status() == LookupStatus.FOUND) {
                assertThat(JsonSafe.bool(expect, "needsALook")).as(file + ": a found fixture says whether it needs a look").isNotNull();
                assertThat(!result.attention().isEmpty()).as(file + ": needs a look").isEqualTo(JsonSafe.bool(expect, "needsALook"));
                assertThat(result.provider().npi()).as(file).isEqualTo(npi);
                assertThat(result.provider().displayName()).as(file + ": a name to show").contains("Placeholder");
                assertThat(result.attention()).as(file).noneMatch(reason -> reason.contains("different NPI"));
            } else {
                assertThat(result.provider()).as(file).isNull();
            }
            assertThat(result.wire().request().sent()).as(file).isFalse();
        });
    }

    @Test
    void theProvidersIncludeInNetworkOutOfNetworkAFaultAndTheNotFoundSentinel() {
        ProviderLookupResult inNetwork = providers.lookup("1234567893");
        assertThat(inNetwork.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(inNetwork.attention()).isEmpty();
        assertThat(inNetwork.message()).isEqualTo("Provider found, reported as in network.");

        ProviderLookupResult outOfNetwork = providers.lookup(Npi.withCheckDigit("100000002"));
        assertThat(outOfNetwork.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(outOfNetwork.attention()).containsExactly(
                "This provider is out of network: the provider directory returned the network status \"Out of network\".");

        ProviderLookupResult fault = providers.lookup(Npi.withCheckDigit("999999998"));
        assertThat(fault.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(fault.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(fault.error().httpStatus()).isEqualTo(500);
        // The body is not JSON, and is on screen as received.
        assertThat(fault.additionalFields()).singleElement().satisfies(field -> {
            assertThat(field.kind()).isEqualTo(AdditionalField.Kind.RAW_BODY);
            assertThat(field.value()).contains("500 Internal Server Error");
        });

        assertThat(FIXTURES.provider(PROVIDER_NOT_FOUND_SENTINEL)).isNull();
        ProviderLookupResult notFound = providers.lookup(PROVIDER_NOT_FOUND_SENTINEL);
        assertThat(notFound.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(notFound.error()).isNull();
        assertThat(notFound.message()).isEqualTo("The provider directory does not know this NPI (it answered HTTP 404).");
    }

    @Test
    void theStatesOfTheLookupItselfCanBeSeenWithoutANetwork() {
        MemberLookupResult fault = member("P32H-LOOKUP-FAULT");
        assertThat(fault.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(fault.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(fault.error().httpStatus()).isEqualTo(503);
        assertThat(fault.additionalFields()).extracting(AdditionalField::path).containsExactly("error", "message", "traceId");

        MemberLookupResult timeout = member("P32H-LOOKUP-TIMEOUT");
        assertThat(timeout.status()).isEqualTo(LookupStatus.NOT_ANSWERING);
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);

        MemberLookupResult several = member("P32H-LOOKUP-MULTI");
        assertThat(several.status()).isEqualTo(LookupStatus.SEVERAL);
        assertThat(several.message()).startsWith("The member API returned 2 records for one member ID");

        MemberLookupResult none = member("P32H-LOOKUP-NONE");
        assertThat(none.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(none.message()).isEqualTo("The member API answered, and its answer holds no member for this member ID.");
        // The two ways of not being found are told apart in words, and neither is a fault.
        assertThat(member(MEMBER_NOT_FOUND_SENTINEL).message()).isEqualTo("The member API does not know this member ID (it answered HTTP 404).");
    }

    @Test
    void theFixturesThemselvesExerciseTheToleranceOfTheMapping() {
        Set<String> whereTheIdWasRead = new LinkedHashSet<>();
        for (String sentinel : SENTINELS_WITH_A_MEMBER) {
            whereTheIdWasRead.add(field(member(sentinel), "memberId").sourceField());
        }
        // Bare, each wrapper, a list of one, and combinations of them, under several spellings.
        assertThat(whereTheIdWasRead).contains("memberId", "data.member_id", "result.id", "member.memberId", "[0].MemberId",
                "data[0].memberNumber", "MEMBER_ID", "results[0].memberId", "data.member.memberId", "member-id",
                "members[0].memberIdentifier");

        // A nested name in both shapes, and a nested address.
        assertThat(field(member("P32H-PA-SAT"), "firstName").sourceField()).isEqualTo("result.name.first");
        assertThat(field(member("P32H-DOC-DTR"), "firstName").sourceField()).isEqualTo("member.name.given[0]");
        assertThat(field(member("P32H-DOC-DTR"), "lastName").sourceField()).isEqualTo("member.name.family");
        assertThat(field(member("P32H-DOC-DTR"), "addressLine2").sourceField()).isEqualTo("member.address.line[1]");
        // A group of fields beside the wrapper.
        assertThat(field(member("P32H-DOC-DTR"), "lineOfBusiness").sourceField()).isEqualTo("coverage.lineOfBusiness");

        // Dates in the other three shapes arrive in ISO form.
        assertThat(member("P32H-NO-AUTH").member().dateOfBirth()).isEqualTo("1985-02-28");
        assertThat(member("P32H-PA-SAT").member().dateOfBirth()).isEqualTo("1978-03-15");
        assertThat(member("P32H-NOCOV").member().dateOfBirth()).isEqualTo("1982-06-12");

        // Fields the mapping does not know, in the record and beside it, are on screen.
        assertThat(member("P32H-NOCOV").additionalFields()).contains(new AdditionalField("data[0].loyaltyTier", Presence.VALUE, "\"placeholder-tier\""));
        assertThat(member("P32H-NO-AUTH").additionalFields()).contains(new AdditionalField("request_id", Presence.VALUE, "\"mock-request-0002\""));
        assertThat(member("P32H-DELEG").additionalFields()).extracting(AdditionalField::path)
                .containsExactly("count", "results[0].groupNumber", "results[0].preferredLanguage");

        // Null, empty and not returned, on one record.
        MemberLookupResult nothings = member("P32H-BAD400");
        assertThat(field(nothings, "planName").value().presence()).isEqualTo(Presence.NULL);
        assertThat(field(nothings, "gender").value().presence()).isEqualTo(Presence.EMPTY);
        assertThat(field(nothings, "coverageStatus").value().presence()).isEqualTo(Presence.ABSENT);
        assertThat(nothings.message()).isEqualTo("Member found, but the member API returned no coverage status.");

        // What the mapping will not do: split a name, or read a date it would have to guess at.
        MemberLookupResult shapes = member("S333333333");
        assertThat(shapes.member().firstName()).isNull();
        assertThat(field(shapes, "fullName").value().orNull()).isEqualTo("Placeholder-Shapes, Sage");
        assertThat(shapes.member().dateOfBirth()).isEqualTo("31-Jan-1970");
        assertThat(shapes.additionalFields()).extracting(AdditionalField::path).containsExactly("address");
    }

    private static LookupField field(MemberLookupResult result, String name) {
        return result.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    /** Every text or number that sits under one of the names, anywhere beneath the node. Names are compared as the mapping compares them. */
    private static List<String> valuesUnder(JsonNode node, Set<String> names) {
        List<String> out = new ArrayList<>();
        collect(node, names, out);
        return out;
    }

    private static void collect(JsonNode node, Set<String> names, List<String> out) {
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            String value = JsonSafe.presentText(property.getValue());
            if (value != null && names.contains(Spelling.normalise(property.getKey()))) {
                out.add(value);
            }
            collect(property.getValue(), names, out);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collect(element, names, out);
        }
    }
}
