package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import tools.jackson.databind.node.ObjectNode;

/**
 * The provider directory mapping. Every response here is invented, and every NPI in it is made
 * with the application's own check digit rule.
 */
class ProviderResponseMappingTest {

    private static final String NPI = Npi.withCheckDigit("123456789");
    private static final String RECORD = "{\"npi\":\"" + NPI + "\",\"firstName\":\"Pat\",\"lastName\":\"Provider\","
            + "\"specialty\":\"Sleep Medicine\",\"networkStatus\":\"In network\"}";

    private static final Map<String, List<String>> SPELLINGS = new LinkedHashMap<>();
    private static final Map<String, List<String>> IN_ADDRESS = new LinkedHashMap<>();
    private static final Map<String, List<String>> FLAT_ADDRESS = new LinkedHashMap<>();
    private static final Map<String, String> SAMPLE = new LinkedHashMap<>();
    private static final Map<String, Function<ProviderDetail, String>> DETAIL = new LinkedHashMap<>();

    static {
        SPELLINGS.put("npi", List.of("npi", "NPI", "npiNumber", "npi_number", "providerNpi", "nationalProviderIdentifier",
                "nationalProviderId", "number"));
        SPELLINGS.put("firstName", List.of("firstName", "first_name", "FirstName", "givenName", "providerFirstName", "name.first",
                "name.firstName", "name.givenName", "name.given[0]", "name.given", "basic.first_name"));
        SPELLINGS.put("lastName", List.of("lastName", "last_name", "familyName", "surname", "providerLastName", "name.last",
                "name.lastName", "name.familyName", "name.family", "name.surname", "basic.last_name"));
        SPELLINGS.put("organizationName", List.of("organizationName", "organization_name", "orgName", "organization",
                "legalBusinessName", "businessName", "facilityName", "practiceName", "groupName", "organization.name",
                "basic.organization_name"));
        SPELLINGS.put("name", List.of("fullName", "providerName", "displayName", "name", "name.text", "name.full", "name.fullName",
                "basic.name"));
        SPELLINGS.put("specialty", List.of("specialty", "speciality", "primarySpecialty", "specialtyName", "specialtyDescription",
                "taxonomyDescription", "specialty.name", "specialty.description", "taxonomy.description", "taxonomy.desc",
                "taxonomies.description", "taxonomies.desc"));
        SPELLINGS.put("taxonomyCode", List.of("taxonomyCode", "taxonomy_code", "taxonomy", "primaryTaxonomy", "primaryTaxonomyCode",
                "specialtyCode", "taxonomy.code", "taxonomies.code", "specialty.code"));
        SPELLINGS.put("networkStatus", List.of("networkStatus", "network_status", "networkParticipation", "participationStatus",
                "network", "network.status", "participation.status"));
        SPELLINGS.put("inNetwork", List.of("inNetwork", "in_network", "isInNetwork", "participating", "isParticipating",
                "network.inNetwork", "network.participating"));
        SPELLINGS.put("phone", List.of("phone", "phoneNumber", "phone_number", "telephone", "telephoneNumber", "officePhone",
                "practicePhone", "contact.phone", "contact.phoneNumber"));

        IN_ADDRESS.put("addressLine1", List.of("line1", "addressLine1", "address_1", "street", "street1", "streetAddress", "line[0]",
                "line"));
        IN_ADDRESS.put("addressLine2", List.of("line2", "addressLine2", "address_2", "street2", "line[1]"));
        IN_ADDRESS.put("city", List.of("city", "town"));
        IN_ADDRESS.put("state", List.of("state", "stateCode", "province"));
        IN_ADDRESS.put("postalCode", List.of("postalCode", "postal_code", "zip", "zipCode", "postal"));
        FLAT_ADDRESS.put("addressLine1", List.of("addressLine1", "address1", "street", "streetAddress"));
        FLAT_ADDRESS.put("addressLine2", List.of("addressLine2", "address2", "street2"));
        FLAT_ADDRESS.put("city", List.of("city"));
        FLAT_ADDRESS.put("state", List.of("state", "stateCode"));
        FLAT_ADDRESS.put("postalCode", List.of("postalCode", "zip", "zipCode"));

        SAMPLE.put("npi", NPI);
        SAMPLE.put("firstName", "Pat");
        SAMPLE.put("lastName", "Provider");
        SAMPLE.put("organizationName", "Placeholder Sleep Center");
        SAMPLE.put("name", "Pat Provider");
        SAMPLE.put("specialty", "Sleep Medicine");
        SAMPLE.put("taxonomyCode", "207X00000X");
        SAMPLE.put("networkStatus", "In network");
        SAMPLE.put("inNetwork", "true");
        SAMPLE.put("phone", "617-555-0100");
        SAMPLE.put("addressLine1", "1 Placeholder Street");
        SAMPLE.put("addressLine2", "Suite 2");
        SAMPLE.put("city", "Sampletown");
        SAMPLE.put("state", "MA");
        SAMPLE.put("postalCode", "00000");

        DETAIL.put("firstName", ProviderDetail::firstName);
        DETAIL.put("lastName", ProviderDetail::lastName);
        DETAIL.put("organizationName", ProviderDetail::organizationName);
        DETAIL.put("specialty", ProviderDetail::specialty);
        DETAIL.put("taxonomyCode", ProviderDetail::taxonomyCode);
        DETAIL.put("networkStatus", ProviderDetail::networkStatus);
        // With no status in words, the provider carries the plain words for the yes or no.
        DETAIL.put("inNetwork", ProviderDetail::networkStatus);
        DETAIL.put("phone", ProviderDetail::phone);
        DETAIL.put("addressLine1", provider -> provider.address().line1());
        DETAIL.put("addressLine2", provider -> provider.address().line2());
        DETAIL.put("city", provider -> provider.address().city());
        DETAIL.put("state", provider -> provider.address().state());
        DETAIL.put("postalCode", provider -> provider.address().postalCode());
    }

    private static ProviderLookupResult map(int status, String body) {
        return ProviderResponseMapping.map(TestExchanges.of(EpaOperation.PROVIDER_LOOKUP, status, body, true), NPI);
    }

    private static ProviderLookupResult found(String body) {
        ProviderLookupResult result = map(200, body);
        assertThat(result.status()).as(body).isEqualTo(LookupStatus.FOUND);
        return result;
    }

    private static LookupField field(ProviderLookupResult result, String name) {
        return result.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> paths(ProviderLookupResult result) {
        return result.additionalFields().stream().map(AdditionalField::path).toList();
    }

    /** A record that is a provider whatever field is under test: it carries one other field that says so. */
    private static ObjectNode anchored(String fieldUnderTest) {
        ObjectNode body = LookupJson.object();
        if (fieldUnderTest.equals("phone")) {
            return LookupJson.put(body, "specialtyName", "Sleep Medicine");
        }
        return LookupJson.put(body, "officePhone", "617-555-0100");
    }

    private static ObjectNode putSample(ObjectNode body, String name, String path) {
        return name.equals("inNetwork") ? LookupJson.put(body, path, true) : LookupJson.put(body, path, SAMPLE.get(name));
    }

    private static void assertReadFrom(String name, String path, ProviderLookupResult result) {
        LookupField read = field(result, name);
        assertThat(read.value()).as(name + " at " + path).isEqualTo(Shown.of(SAMPLE.get(name)));
        assertThat(read.sourceField()).as(name + " at " + path).isEqualTo(path);
        if (DETAIL.containsKey(name)) {
            assertThat(DETAIL.get(name).apply(result.provider())).as(name + " at " + path)
                    .isEqualTo(name.equals("inNetwork") ? "in network" : SAMPLE.get(name));
        }
        assertThat(result.attention()).as(name + " at " + path).isEmpty();
    }

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
        for (String object : List.of("address", "practiceAddress", "practice_address", "locationAddress", "officeAddress", "primaryAddress")) {
            String path = object + ".city";
            assertReadFrom("city", path, found(putSample(anchored("city"), "city", path).toString()));
        }
        FLAT_ADDRESS.forEach((name, spellings) -> {
            for (String spelling : spellings) {
                assertReadFrom(name, spelling, found(putSample(anchored(name), name, spelling).toString()));
            }
        });
    }

    @Test
    void aListOfOneAddressIsReadAndItsPhoneIsUsedWhenTheRecordStatesNone() {
        ProviderLookupResult result = found("{\"number\":\"" + NPI + "\",\"basic\":{\"organization_name\":\"Placeholder Sleep Center\"},"
                + "\"addresses\":[{\"address_1\":\"1 Placeholder Street\",\"city\":\"Sampletown\",\"state\":\"MA\","
                + "\"postal_code\":\"00000\",\"telephone_number\":\"617-555-0100\",\"address_purpose\":\"LOCATION\"}],"
                + "\"taxonomies\":[{\"code\":\"207X00000X\",\"desc\":\"Orthopaedic Surgery\",\"primary\":true}]}");
        assertThat(result.provider().address().line1()).isEqualTo("1 Placeholder Street");
        assertThat(result.provider().phone()).isEqualTo("617-555-0100");
        assertThat(result.provider().organizationName()).isEqualTo("Placeholder Sleep Center");
        assertThat(result.provider().taxonomyCode()).isEqualTo("207X00000X");
        assertThat(result.provider().specialty()).isEqualTo("Orthopaedic Surgery");
        assertThat(field(result, "phone").sourceField()).isEqualTo("addresses[0].telephone_number");
        assertThat(field(result, "addressLine1").sourceField()).isEqualTo("addresses[0].address_1");
        assertThat(field(result, "taxonomyCode").sourceField()).isEqualTo("taxonomies[0].code");
        assertThat(paths(result)).containsExactly("addresses[0].address_purpose", "taxonomies[0].primary");
        // A phone the record states itself is the one used, and the address keeps its own on screen.
        ProviderLookupResult own = found("{\"phone\":\"617-555-0199\",\"specialty\":\"Sleep Medicine\","
                + "\"address\":{\"city\":\"Sampletown\",\"phone\":\"617-555-0100\"}}");
        assertThat(own.provider().phone()).isEqualTo("617-555-0199");
        assertThat(paths(own)).containsExactly("address.phone");
        // Two addresses are never chosen from.
        ProviderLookupResult two = found("{\"specialty\":\"Sleep Medicine\",\"addresses\":[{\"city\":\"Sampletown\"},{\"city\":\"Elsewhere\"}]}");
        assertThat(two.provider().address()).isNull();
        assertThat(paths(two)).containsExactly("addresses[0].city", "addresses[1].city");
    }

    @Test
    void theTestTablesAreExactlyTheSpellingsTheMappingKnows() {
        Map<String, List<Spelling>> mapping = new LinkedHashMap<>();
        mapping.put("npi", ProviderResponseMapping.NPI);
        mapping.put("firstName", ProviderResponseMapping.FIRST_NAME);
        mapping.put("lastName", ProviderResponseMapping.LAST_NAME);
        mapping.put("organizationName", ProviderResponseMapping.ORGANIZATION_NAME);
        mapping.put("name", ProviderResponseMapping.NAME);
        mapping.put("specialty", ProviderResponseMapping.SPECIALTY);
        mapping.put("taxonomyCode", ProviderResponseMapping.TAXONOMY_CODE);
        mapping.put("networkStatus", ProviderResponseMapping.NETWORK_STATUS);
        mapping.put("inNetwork", ProviderResponseMapping.IN_NETWORK);
        mapping.put("phone", ProviderResponseMapping.PHONE);
        assertThat(mapping.keySet()).isEqualTo(SPELLINGS.keySet());
        mapping.forEach((name, spellings) -> assertThat(steps(SPELLINGS.get(name))).as(name).isEqualTo(stepsOf(spellings)));

        Map<String, List<Spelling>> inAddress = Map.of("addressLine1", ProviderResponseMapping.LINE_1_IN_ADDRESS,
                "addressLine2", ProviderResponseMapping.LINE_2_IN_ADDRESS, "city", ProviderResponseMapping.CITY_IN_ADDRESS,
                "state", ProviderResponseMapping.STATE_IN_ADDRESS, "postalCode", ProviderResponseMapping.POSTAL_CODE_IN_ADDRESS);
        inAddress.forEach((name, spellings) -> assertThat(steps(IN_ADDRESS.get(name))).as(name).isEqualTo(stepsOf(spellings)));
        Map<String, List<Spelling>> flat = Map.of("addressLine1", ProviderResponseMapping.LINE_1_FLAT,
                "addressLine2", ProviderResponseMapping.LINE_2_FLAT, "city", ProviderResponseMapping.CITY_FLAT,
                "state", ProviderResponseMapping.STATE_FLAT, "postalCode", ProviderResponseMapping.POSTAL_CODE_FLAT);
        flat.forEach((name, spellings) -> assertThat(steps(FLAT_ADDRESS.get(name))).as(name).isEqualTo(stepsOf(spellings)));
        assertThat(stepsOf(ProviderResponseMapping.PHONE_IN_ADDRESS)).isEqualTo(steps(List.of("phone", "phoneNumber", "telephone", "telephone_number")));
        assertThat(ProviderResponseMapping.ADDRESS_OBJECTS).containsExactly("address", "practiceAddress", "locationAddress",
                "officeAddress", "primaryAddress", "addresses");
        assertThat(ProviderResponseMapping.WRAPPERS).containsExactly("data", "result", "results", "provider", "providers");
    }

    private static Set<List<Spelling.Step>> steps(List<String> written) {
        return written.stream().map(one -> Spelling.of(one).steps()).collect(Collectors.toSet());
    }

    private static Set<List<Spelling.Step>> stepsOf(List<Spelling> spellings) {
        return spellings.stream().map(Spelling::steps).collect(Collectors.toSet());
    }

    @Test
    void aRecordIdIsNeverReadAsTheNpi() {
        ProviderLookupResult result = found("{\"id\":4711,\"specialty\":\"Sleep Medicine\"}");
        assertThat(field(result, "npi").value()).isEqualTo(Shown.absent());
        assertThat(result.provider().npi()).isEqualTo(NPI);
        assertThat(result.attention()).isEmpty();
        assertThat(result.additionalFields()).containsExactly(new AdditionalField("id", Presence.VALUE, "4711"));
    }

    @Test
    void theRecordIsFoundInEveryEnvelope() {
        Map<String, String> envelopes = new LinkedHashMap<>();
        envelopes.put(RECORD, "");
        envelopes.put("{\"data\":" + RECORD + "}", "data.");
        envelopes.put("{\"result\":" + RECORD + "}", "result.");
        envelopes.put("{\"provider\":" + RECORD + "}", "provider.");
        envelopes.put("[" + RECORD + "]", "[0].");
        envelopes.put("{\"results\":[" + RECORD + "]}", "results[0].");
        envelopes.put("{\"providers\":[" + RECORD + "]}", "providers[0].");
        envelopes.put("{\"data\":{\"provider\":" + RECORD + "}}", "data.provider.");
        envelopes.forEach((body, prefix) -> {
            ProviderLookupResult result = found(body);
            assertThat(result.provider().lastName()).as(body).isEqualTo("Provider");
            assertThat(result.provider().specialty()).as(body).isEqualTo("Sleep Medicine");
            assertThat(field(result, "npi").sourceField()).as(body).isEqualTo(prefix + "npi");
            assertThat(result.additionalFields()).as(body).isEmpty();
            assertThat(result.message()).as(body).isEqualTo("Provider found, reported as in network.");
        });
        // The member wrapper is not a provider wrapper: the two lists are separate.
        assertThat(map(200, "{\"member\":" + RECORD + "}").status()).isEqualTo(LookupStatus.FAULT);
    }

    @Test
    void anEnvelopeThatHoldsNoRecordIsNotFoundAndNeverAFault() {
        for (String body : List.of("{}", "[]", "null", "{\"data\":null}", "{\"provider\":{}}", "{\"providers\":[]}", "{\"results\":[]}")) {
            ProviderLookupResult result = map(200, body);
            assertThat(result.status()).as(body).isEqualTo(LookupStatus.NOT_FOUND);
            assertThat(result.message()).as(body).isEqualTo("The provider directory answered, and its answer holds no provider for this NPI.");
            assertThat(result.error()).as(body).isNull();
            assertThat(result.provider()).as(body).isNull();
            assertThat(result.fields()).as(body).isEmpty();
            assertThat(result.additionalFields()).as(body).hasSize(1);
            assertThat(result.npi()).as(body).isEqualTo(NPI);
        }
        // The NPI handed back with nothing else is no record.
        ProviderLookupResult hollow = map(200, "{\"npi\":\"" + NPI + "\",\"firstName\":null,\"address\":{}}");
        assertThat(hollow.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(hollow.message())
                .isEqualTo("The provider directory answered with a record that holds no values, which this tool reads as not found.");
        assertThat(paths(hollow)).containsExactly("npi", "firstName", "address");
    }

    @Test
    void severalRecordsAreAFaultThatSaysHowManyCameBack() {
        ProviderLookupResult result = map(200, "{\"result_count\":2,\"results\":[" + RECORD + "," + RECORD + "]}");
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.message()).isEqualTo("The provider directory returned 2 records for one NPI, and this tool does not choose between them.");
        assertThat(result.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(result.provider()).isNull();
        assertThat(paths(result)).contains("result_count", "results[0].npi", "results[1].npi").hasSize(11);
    }

    @Test
    void anAnswerThatIsNotAProviderIsAFaultWithEverythingItSaidOnScreen() {
        Map<String, List<String>> answers = new LinkedHashMap<>();
        answers.put("{\"message\":\"NPI not on file\"}", List.of("message"));
        answers.put("{\"npi\":\"" + NPI + "\",\"found\":false}", List.of("npi", "found"));
        answers.put("{\"name\":\"NotFoundError\",\"state\":\"failed\",\"network\":\"all\"}", List.of("name", "state", "network"));
        answers.forEach((body, shown) -> {
            ProviderLookupResult result = map(200, body);
            assertThat(result.status()).as(body).isEqualTo(LookupStatus.FAULT);
            assertThat(result.message()).as(body)
                    .isEqualTo("The provider directory answered, but this tool could read nothing about a provider from its answer.");
            assertThat(result.error().action()).as(body).contains("type the provider details by hand");
            assertThat(result.fields()).as(body).isEmpty();
            assertThat(paths(result)).as(body).containsExactlyElementsOf(shown);
        });
    }

    @Test
    void aFieldTheMappingWasNeverToldAboutReachesTheReturnedFields() {
        ProviderLookupResult result = found("{\"provider\":{\"npi\":\"" + NPI + "\",\"lastName\":\"Provider\",\"acceptingNewPatients\":true,"
                + "\"languages\":[\"en\",\"es\"],\"boardCertified\":null},\"asOf\":\"2026-01-01\"}");
        assertThat(result.additionalFields()).containsExactly(
                new AdditionalField("provider.acceptingNewPatients", Presence.VALUE, "true"),
                new AdditionalField("provider.languages[0]", Presence.VALUE, "\"en\""),
                new AdditionalField("provider.languages[1]", Presence.VALUE, "\"es\""),
                new AdditionalField("provider.boardCertified", Presence.NULL, null),
                new AdditionalField("asOf", Presence.VALUE, "\"2026-01-01\""));
    }

    @Test
    void aFieldThatIsAbsentNullOrEmptyGivesThreeDifferentPresencesAndNothingIsFilledIn() {
        ProviderLookupResult result = found("{\"npi\":\"" + NPI + "\",\"lastName\":\"Provider\",\"specialty\":null,\"phone\":\"\"}");
        assertThat(field(result, "networkStatus").value().presence()).isEqualTo(Presence.ABSENT);
        assertThat(field(result, "networkStatus").sourceField()).isNull();
        assertThat(field(result, "specialty").value().presence()).isEqualTo(Presence.NULL);
        assertThat(field(result, "phone").value().presence()).isEqualTo(Presence.EMPTY);
        assertThat(field(result, "lastName").value().presence()).isEqualTo(Presence.VALUE);
        // A network status that was not returned is not "in network".
        assertThat(result.provider().networkStatus()).isNull();
        assertThat(result.provider().specialty()).isNull();
        assertThat(result.provider().phone()).isNull();
        assertThat(result.message()).isEqualTo("Provider found, but the provider directory returned no network status.");
        assertThat(result.attention()).isEmpty();
        assertThat(result.fields()).extracting(LookupField::name).containsExactly("npi", "firstName", "lastName", "organizationName",
                "name", "specialty", "taxonomyCode", "networkStatus", "inNetwork", "phone", "addressLine1", "addressLine2", "city",
                "state", "postalCode");
    }

    @Test
    void aNameInOnePieceIsNeitherSplitNorTakenForAnOrganization() {
        ProviderLookupResult result = found("{\"name\":\"Placeholder Sleep Center\",\"specialty\":\"Sleep Medicine\"}");
        assertThat(field(result, "name").value()).isEqualTo(Shown.of("Placeholder Sleep Center"));
        assertThat(result.provider().organizationName()).isNull();
        assertThat(result.provider().firstName()).isNull();
        assertThat(result.provider().displayName()).isNull();
    }

    @Test
    void aProviderWhoIsOutOfNetworkIsStillFoundWithAReasonToLook() {
        for (String status : List.of("Out of network", "OUT_OF_NETWORK", "out-of-network", "OON", "Non-participating", "non-par",
                "Not participating", "not in network", "OUT")) {
            ProviderLookupResult result = found("{\"lastName\":\"Provider\",\"networkStatus\":\"" + status + "\"}");
            assertThat(result.attention()).as(status).containsExactly(
                    "This provider is out of network: the provider directory returned the network status \"" + status + "\".");
            assertThat(result.message()).as(status).isEqualTo("Provider found, with 1 thing to check before using this provider.");
            assertThat(result.provider().networkStatus()).as(status).isEqualTo(status);
            assertThat(result.error()).as(status).isNull();
        }
        for (String status : List.of("In network", "IN_NETWORK", "in-network", "INN", "Participating", "PAR", "in")) {
            ProviderLookupResult result = found("{\"lastName\":\"Provider\",\"networkStatus\":\"" + status + "\"}");
            assertThat(result.attention()).as(status).isEmpty();
            assertThat(result.message()).as(status).isEqualTo("Provider found, reported as in network.");
        }
    }

    @Test
    void aNetworkStatusThisToolDoesNotRecogniseIsNeitherInNorOut() {
        for (String status : List.of("Tier 2", "Preferred", "true", "Pending credentialing")) {
            ProviderLookupResult result = found("{\"lastName\":\"Provider\",\"networkStatus\":\"" + status + "\"}");
            assertThat(result.attention()).as(status).containsExactly("The network status \"" + status
                    + "\" is not one this tool recognises, so it is treated as neither in network nor out of network.");
            assertThat(result.message()).as(status).doesNotContain("in network");
        }
    }

    @Test
    void aYesOrNoAboutTheNetworkIsReadAsOne() {
        ProviderLookupResult no = found("{\"lastName\":\"Provider\",\"inNetwork\":false}");
        assertThat(no.attention()).containsExactly("This provider is out of network: the provider directory returned inNetwork as false.");
        // The provider carries the words for the answer. The field keeps what was returned.
        assertThat(no.provider().networkStatus()).isEqualTo("out of network");
        assertThat(field(no, "inNetwork").value()).isEqualTo(Shown.of("false"));
        ProviderLookupResult yes = found("{\"lastName\":\"Provider\",\"network\":{\"participating\":\"Yes\"}}");
        assertThat(yes.attention()).isEmpty();
        assertThat(yes.message()).isEqualTo("Provider found, reported as in network.");
        assertThat(yes.provider().networkStatus()).isEqualTo("in network");
        // A word under a yes-or-no name is still read as the word it is.
        ProviderLookupResult word = found("{\"lastName\":\"Provider\",\"participating\":\"NON-PAR\"}");
        assertThat(word.attention()).hasSize(1);
        assertThat(word.provider().networkStatus()).isEqualTo("NON-PAR");
        ProviderLookupResult unclear = found("{\"lastName\":\"Provider\",\"inNetwork\":\"sometimes\"}");
        assertThat(unclear.attention()).containsExactly("The value \"sometimes\" of inNetwork is not a yes or a no this tool recognises, "
                + "so it says nothing about whether the provider is in network.");
    }

    @Test
    void aStatusAndAYesOrNoThatContradictEachOtherAreSaidTo() {
        ProviderLookupResult result = found("{\"lastName\":\"Provider\",\"networkStatus\":\"In network\",\"inNetwork\":false}");
        assertThat(result.attention()).containsExactly("The provider directory contradicts itself about the network: it returned the "
                + "network status \"In network\" and inNetwork as false. The provider is not taken to be in network.");
        assertThat(found("{\"lastName\":\"Provider\",\"networkStatus\":\"In network\",\"inNetwork\":true}").attention()).isEmpty();
    }

    @Test
    void aRecordReturnedUnderAnotherNpiIsAReasonToLookAndTheNpiTypedIsTheOneKept() {
        String other = Npi.withCheckDigit("100000001");
        ProviderLookupResult result = found("{\"npi\":" + other + ",\"lastName\":\"Provider\",\"inNetwork\":true}");
        assertThat(result.attention()).containsExactly("The provider directory returned a record under a different NPI from the one that "
                + "was asked for. Check that this is the provider you meant. The NPI that was typed is the one used.");
        assertThat(result.provider().npi()).isEqualTo(NPI);
        assertThat(result.npi()).isEqualTo(NPI);
        assertThat(field(result, "npi").value()).isEqualTo(Shown.of(other));
        // An NPI the directory writes as a number is the same NPI.
        assertThat(found("{\"npi\":" + NPI + ",\"lastName\":\"Provider\",\"inNetwork\":true}").attention()).isEmpty();
    }

    @Test
    void notFoundAndEachFaultAreToldApartInTheDirectorysOwnWords() {
        ProviderLookupResult notFound = map(404, "{\"error\":\"not_found\"}");
        assertThat(notFound.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(notFound.message()).isEqualTo("The provider directory does not know this NPI (it answered HTTP 404).");
        assertThat(notFound.error()).isNull();
        assertThat(paths(notFound)).containsExactly("error");

        ProviderLookupResult serverError = map(500, "<html>Internal Server Error</html>");
        assertThat(serverError.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(serverError.message()).isEqualTo("The provider directory failed with HTTP 500.");
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.error().action()).contains("not in the NPI").contains("Type the provider details by hand.");
        assertThat(serverError.additionalFields()).containsExactly(AdditionalField.unparsedBody("<html>Internal Server Error</html>"));

        ProviderLookupResult refused = map(403, null);
        assertThat(refused.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(refused.error().action()).contains("lookup.provider.headers").contains("lookup.provider.use-onyx-auth");

        ProviderLookupResult timeout = ProviderResponseMapping.map(TestExchanges.timeout(EpaOperation.PROVIDER_LOOKUP), NPI);
        assertThat(timeout.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(timeout.message()).isEqualTo("The provider directory did not answer inside the read timeout.");
        ProviderLookupResult unreachable = ProviderResponseMapping.map(TestExchanges.unavailable(EpaOperation.PROVIDER_LOOKUP), NPI);
        assertThat(unreachable.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        assertThat(unreachable.error().action()).contains("lookup.provider.url");
        ProviderLookupResult empty = map(200, null);
        assertThat(empty.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(empty.message()).isEqualTo("The provider directory answered HTTP 200 with an empty body, so there is nothing to read a provider from.");
        ProviderLookupResult malformed = map(200, "{\"npi\":");
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(malformed.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"npi\":"));
    }

    @Test
    void everyStateHasItsOwnSentenceAndTheWireViewCarriesNoMemberId() {
        List<ProviderLookupResult> results = new ArrayList<>();
        results.add(found(RECORD));
        results.add(found("{\"lastName\":\"Provider\"}"));
        results.add(found("{\"lastName\":\"Provider\",\"inNetwork\":false}"));
        results.add(map(404, null));
        results.add(map(200, "{}"));
        results.add(map(200, "{\"lastName\":null}"));
        results.add(map(503, null));
        results.add(map(400, null));
        results.add(map(200, "not json"));
        results.add(ProviderResponseMapping.map(TestExchanges.timeout(EpaOperation.PROVIDER_LOOKUP), NPI));
        assertThat(results).extracting(ProviderLookupResult::message).doesNotHaveDuplicates().allSatisfy(message -> {
            assertThat(message).endsWith(".").doesNotContain("Pat").doesNotContain(NPI);
            assertThat(message.indexOf(". ")).as(message).isEqualTo(-1);
        });
        assertThat(results).allSatisfy(result -> {
            assertThat(result.npi()).isEqualTo(NPI);
            assertThat(result.wire().request()).isNotNull();
            assertThat(result.wire().memberId()).isNull();
        });
    }

    @Test
    void everyValueOfARepeatedKeyIsListedAfterTheNoteAndBeforeTheFieldsOfTheBody() {
        ProviderLookupResult result = found("{\"npi\":\"" + NPI + "\",\"lastName\":\"Provider\",\"lastName\":\"Other\",\"extra\":1}");
        assertThat(result.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.NOTE);
        assertThat(result.additionalFields().get(0).value()).contains("repeats a key").doesNotContain("Only the last value");
        assertThat(result.additionalFields().subList(1, 4)).containsExactly(
                new AdditionalField("lastName (value 1 of 2)", Presence.VALUE, "\"Provider\""),
                new AdditionalField("lastName (value 2 of 2)", Presence.VALUE, "\"Other\""),
                new AdditionalField("extra", Presence.VALUE, "1"));
    }
}
