package org.p32h.interop.epa.pave.client.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireView;

/**
 * WHAT THIS TOOL ASSUMES ABOUT THE PROVIDER DIRECTORY'S RESPONSE, AND THE ONE METHOD THAT APPLIES IT.
 *
 * <p>As with the member API, the response contract of the directory is not final. The wrapper
 * names, the field spellings and the words that mean a provider is in or out of network are all
 * assumptions. They are the tables below, and {@link #map} is the one place they are applied. The
 * mock client and the live client both call it.
 *
 * <p>The tables are kept apart from the member tables on purpose: they describe two different
 * services, and narrowing one must not change how the other is read. The rules the two lookups do
 * share are named on {@link MemberResponseMapping}.
 */
final class ProviderResponseMapping {

    private static final LookupWords WORDS = LookupWords.PROVIDER;

    /** The names a response may wrap the record in, in the order they are tried. */
    static final List<String> WRAPPERS = List.of("data", "result", "results", "provider", "providers");

    // Each list is in order of preference: the first spelling that carries a value is the one read.
    // A bare "id" is deliberately not a spelling of the NPI. A directory has its own record ids.
    static final List<Spelling> NPI = Spelling.list("npi", "npiNumber", "providerNpi", "nationalProviderIdentifier",
            "nationalProviderId", "number");
    static final List<Spelling> FIRST_NAME = Spelling.list("firstName", "givenName", "providerFirstName", "name.first",
            "name.firstName", "name.givenName", "name.given[0]", "name.given", "basic.firstName");
    static final List<Spelling> LAST_NAME = Spelling.list("lastName", "familyName", "surname", "providerLastName", "name.last",
            "name.lastName", "name.familyName", "name.family", "name.surname", "basic.lastName");
    static final List<Spelling> ORGANIZATION_NAME = Spelling.list("organizationName", "orgName", "organization", "legalBusinessName",
            "businessName", "facilityName", "practiceName", "groupName", "organization.name", "basic.organizationName");
    /**
     * A name in one piece. It is shown as returned. It is not split into a first and a last name,
     * and it is not taken for an organization either: which of the two it is would be a guess.
     */
    static final List<Spelling> NAME = Spelling.list("fullName", "providerName", "displayName", "name", "name.text", "name.full",
            "name.fullName", "basic.name");
    static final List<Spelling> SPECIALTY = Spelling.list("specialty", "speciality", "primarySpecialty", "specialtyName",
            "specialtyDescription", "taxonomyDescription", "specialty.name", "specialty.description", "taxonomy.description",
            "taxonomy.desc", "taxonomies.description", "taxonomies.desc");
    static final List<Spelling> TAXONOMY_CODE = Spelling.list("taxonomyCode", "taxonomy", "primaryTaxonomy", "primaryTaxonomyCode",
            "specialtyCode", "taxonomy.code", "taxonomies.code", "specialty.code");
    static final List<Spelling> NETWORK_STATUS = Spelling.list("networkStatus", "networkParticipation", "participationStatus",
            "network", "network.status", "participation.status");
    /** A yes or no about the network, for a directory that states it that way. */
    static final List<Spelling> IN_NETWORK = Spelling.list("inNetwork", "isInNetwork", "participating", "isParticipating",
            "network.inNetwork", "network.participating");
    static final List<Spelling> PHONE = Spelling.list("phone", "phoneNumber", "telephone", "telephoneNumber", "officePhone",
            "practicePhone", "contact.phone", "contact.phoneNumber");

    /** A fax number, which rule pd-1 accepts in place of a phone on the requesting PractitionerRole where the PAS guide in force applies it. */
    static final List<Spelling> FAX = Spelling.list("fax", "faxNumber", "officeFax", "practiceFax", "contact.fax", "contact.faxNumber");

    /** The names an address object may have. All parts are read from the first one found, never from two. */
    static final List<String> ADDRESS_OBJECTS = List.of("address", "practiceAddress", "locationAddress", "officeAddress",
            "primaryAddress", "addresses");
    static final List<Spelling> LINE_1_IN_ADDRESS = Spelling.list("line1", "addressLine1", "address1", "street", "street1",
            "streetAddress", "line[0]", "line");
    static final List<Spelling> LINE_2_IN_ADDRESS = Spelling.list("line2", "addressLine2", "address2", "street2", "line[1]");
    static final List<Spelling> CITY_IN_ADDRESS = Spelling.list("city", "town");
    static final List<Spelling> STATE_IN_ADDRESS = Spelling.list("state", "stateCode", "province");
    static final List<Spelling> POSTAL_CODE_IN_ADDRESS = Spelling.list("postalCode", "zip", "zipCode", "postal");
    /** Read only when the record itself states no phone: a directory often keeps the phone with the practice address. */
    static final List<Spelling> PHONE_IN_ADDRESS = Spelling.list("phone", "phoneNumber", "telephone", "telephoneNumber");
    // Used only when the record has no address object and writes the parts beside its other fields.
    static final List<Spelling> LINE_1_FLAT = Spelling.list("addressLine1", "address1", "street", "streetAddress");
    static final List<Spelling> LINE_2_FLAT = Spelling.list("addressLine2", "address2", "street2");
    static final List<Spelling> CITY_FLAT = Spelling.list("city");
    static final List<Spelling> STATE_FLAT = Spelling.list("state", "stateCode");
    static final List<Spelling> POSTAL_CODE_FLAT = Spelling.list("postalCode", "zip", "zipCode");

    /**
     * The fields whose values the result is read by: the NPI, compared with the one asked for, and
     * whether the provider is in network, by its status and its yes or no.
     */
    static final List<List<Spelling>> DECIDED_BY = List.of(NPI, NETWORK_STATUS, IN_NETWORK);

    /** Every field the mapping reads. Whether a record is a provider at all is read by which of them carry a value. */
    static final List<List<Spelling>> READ = List.of(NPI, FIRST_NAME, LAST_NAME, ORGANIZATION_NAME, NAME, SPECIALTY, TAXONOMY_CODE,
            NETWORK_STATUS, IN_NETWORK, PHONE, FAX, LINE_1_IN_ADDRESS, LINE_2_IN_ADDRESS, CITY_IN_ADDRESS, STATE_IN_ADDRESS,
            POSTAL_CODE_IN_ADDRESS, PHONE_IN_ADDRESS, LINE_1_FLAT, LINE_2_FLAT, CITY_FLAT, STATE_FLAT, POSTAL_CODE_FLAT);

    /** The names a record and its address are found under. */
    static final List<String> WRAPPED_IN = java.util.stream.Stream.concat(WRAPPERS.stream(), ADDRESS_OBJECTS.stream()).toList();

    /** Names so common in any JSON that a value under one does not show the answer is a provider. */
    static final Set<Spelling> GENERIC = Set.copyOf(Spelling.list("name", "state", "network"));

    /** Words taken to mean in network. Compared ignoring case, spaces and punctuation. */
    static final Set<String> IN_NETWORK_WORDS = Set.of("innetwork", "in", "inn", "participating", "par");
    /**
     * Words taken to mean out of network. Any other word is not sorted into either group: the
     * result says it is not recognised, because a tier or a plan-specific status is not a yes or a no.
     */
    static final Set<String> OUT_OF_NETWORK_WORDS = Set.of("outofnetwork", "out", "oon", "nonparticipating", "nonpar",
            "notparticipating", "notinnetwork");

    private enum Standing {
        IN, OUT, UNRECOGNISED, NOT_STATED
    }

    private ProviderResponseMapping() {
    }

    /**
     * THE ONE METHOD that turns a provider directory response into a lookup result. Mock and live
     * both call it.
     *
     * @param received the call as it happened: a response, or the way it failed
     * @param npi      the NPI that was asked for, already checked
     */
    static ProviderLookupResult map(VendorExchange received, String npi) {
        return map(received, npi, LookupReading.usual(WORDS));
    }

    /** The same, read where a lookup saved on screen says its matches and fields are (R-11). */
    static ProviderLookupResult map(VendorExchange received, String npi, LookupReading reading) {
        ProviderLookupResult result = read(received, npi, reading);
        // What the result concludes is read with the masks in place, so it says so where a mask fell on a value it rests on.
        org.p32h.interop.epa.pave.model.MaskedReading masked = LookupMasks.find(received, DECIDED_BY, READ, WRAPPED_IN);
        return masked == null ? result : result.withMaskedReading(masked.line());
    }

    private static ProviderLookupResult read(VendorExchange received, String npi, LookupReading reading) {
        LookupWords words = reading.words();
        // What the view copies out of the body must not show a secret this tool holds, any more than the wire view does.
        VendorExchange exchange = received.withReturnedSecretsMasked();
        // First, so a body that could not be parsed and any note about the body are never left to the wire view alone.
        // Every value of a key the body repeats is listed too, so an earlier value is not on the wire view alone.
        List<AdditionalField> additional = new ArrayList<>(TrackedJson.bodyFieldsWithRepeatedKeys(exchange));
        TrackedJson tracked = TrackedJson.of(exchange.json());

        LookupOutcomes.Ending ending = LookupOutcomes.ofCall(exchange, words, tracked);
        if (ending != null) {
            additional.addAll(tracked.unconsumed());
            return ended(ending, exchange, npi, additional);
        }

        // The envelope, apart from the fields.
        LookupEnvelope.Opened opened = reading.open(tracked.root(), WRAPPERS);
        if (opened.kind() != LookupEnvelope.Kind.RECORD) {
            additional.addAll(tracked.unconsumed());
            return ended(switch (opened.kind()) {
                case SEVERAL -> LookupOutcomes.several(words, opened.count(), exchange);
                case NOT_A_RECORD -> LookupOutcomes.notARecord(words, exchange);
                case NOT_THERE -> LookupOutcomes.notThere(words, exchange);
                default -> LookupOutcomes.noRecord(words);
            }, exchange, npi, additional);
        }

        // The fields.
        LookupReader reader = new LookupReader(opened.scopes());
        LookupReader.Read returnedNpi = reader.read(NPI);
        LookupReader.Read firstName = reader.read(reading.spellings("firstName", FIRST_NAME));
        LookupReader.Read lastName = reader.read(reading.spellings("lastName", LAST_NAME));
        LookupReader.Read organizationName = reader.read(reading.spellings("organizationName", ORGANIZATION_NAME));
        LookupReader.Read name = reader.read(NAME);
        LookupReader.Read specialty = reader.read(reading.spellings("specialty", SPECIALTY));
        LookupReader.Read taxonomyCode = reader.read(TAXONOMY_CODE);
        LookupReader.Read networkStatus = reader.read(NETWORK_STATUS);
        LookupReader.Read inNetwork = reader.read(IN_NETWORK);
        LookupReader.Read phone = reader.read(reading.spellings("phone", PHONE));
        LookupReader.Read fax = reader.read(reading.spellings("fax", FAX));
        TrackedJson.Node addressObject = reader.object(ADDRESS_OBJECTS);
        LookupReader address = addressObject == null ? reader : new LookupReader(List.of(addressObject));
        LookupReader.Read line1 = address.read(addressObject == null ? LINE_1_FLAT : LINE_1_IN_ADDRESS);
        LookupReader.Read line2 = address.read(addressObject == null ? LINE_2_FLAT : LINE_2_IN_ADDRESS);
        LookupReader.Read city = address.read(addressObject == null ? CITY_FLAT : CITY_IN_ADDRESS);
        LookupReader.Read state = address.read(addressObject == null ? STATE_FLAT : STATE_IN_ADDRESS);
        LookupReader.Read postalCode = address.read(addressObject == null ? POSTAL_CODE_FLAT : POSTAL_CODE_IN_ADDRESS);
        if (phone.source() == null && addressObject != null && !reading.fieldPaths().containsKey("phone")) {
            phone = address.read(PHONE_IN_ADDRESS);
        }

        // Is this a provider at all? It is when it says something about one. The NPI alone does not
        // count, because a directory can hand the NPI back in the very answer that says it found nobody.
        List<LookupReader.Read> about = List.of(firstName, lastName, organizationName, name, specialty, taxonomyCode, networkStatus,
                inNetwork, phone, fax, line1, line2, city, state, postalCode);
        if (about.stream().noneMatch(read -> read.hasText() && !GENERIC.contains(read.spelling()))) {
            // Read again from the start, so nothing the abandoned reading touched is missing from the returned fields.
            additional.addAll(TrackedJson.of(exchange.json()).unconsumed());
            return ended(LookupEnvelope.holdsAValue(opened.record().raw(), npi) ? LookupOutcomes.unrecognised(words, exchange)
                    : LookupOutcomes.hollowRecord(words), exchange, npi, additional);
        }

        // What needs a look. A found provider stays found: these are reasons to check before using them.
        List<String> attention = new ArrayList<>();
        Standing byStatus = standing(networkStatus, false);
        Standing byFlag = standing(inNetwork, true);
        if ((byStatus == Standing.IN && byFlag == Standing.OUT) || (byStatus == Standing.OUT && byFlag == Standing.IN)) {
            attention.add("The provider directory contradicts itself about the network: it returned the network status \""
                    + networkStatus.text() + "\" and " + inNetwork.source() + " as " + inNetwork.text()
                    + ". The provider is not taken to be in network.");
        } else {
            if (byStatus == Standing.OUT) {
                attention.add("This provider is out of network: the provider directory returned the network status \""
                        + networkStatus.text() + "\".");
            }
            if (byFlag == Standing.OUT) {
                attention.add("This provider is out of network: the provider directory returned " + inNetwork.source() + " as "
                        + inNetwork.text() + ".");
            }
        }
        if (byStatus == Standing.UNRECOGNISED) {
            attention.add("The network status \"" + networkStatus.text() + "\" is not one this tool recognises, so it is treated as "
                    + "neither in network nor out of network.");
        }
        if (byFlag == Standing.UNRECOGNISED) {
            attention.add("The value \"" + inNetwork.text() + "\" of " + inNetwork.source() + " is not a yes or a no this tool "
                    + "recognises, so it says nothing about whether the provider is in network.");
        }
        if (returnedNpi.hasText() && !returnedNpi.text().equals(npi)) {
            attention.add("The provider directory returned a record under a different NPI from the one that was asked for. Check that "
                    + "this is the provider you meant. The NPI that was typed is the one used.");
        }

        // The provider. The NPI is always the one that was asked for, so a lookup can never change
        // which NPI the next request carries.
        PostalAddress postal = line1.hasText() || line2.hasText() || city.hasText() || state.hasText() || postalCode.hasText()
                ? new PostalAddress(line1.text(), line2.text(), city.text(), state.text(), postalCode.text()) : null;
        ProviderDetail provider = ProviderDetail.builder()
                .npi(npi)
                .firstName(firstName.text())
                .lastName(lastName.text())
                .organizationName(organizationName.text())
                .specialty(specialty.text())
                .taxonomyCode(taxonomyCode.text())
                // A status in words is kept as the directory wrote it. A yes or a no has no word to keep.
                .networkStatus(networkStatus.hasText() ? networkStatus.text() : statusFrom(inNetwork))
                .phone(phone.text())
                .fax(fax.text())
                .address(postal)
                .build();

        List<LookupField> fields = List.of(
                returnedNpi.field("npi", "NPI"),
                firstName.field("firstName", "First name"),
                lastName.field("lastName", "Last name"),
                organizationName.field("organizationName", "Organization"),
                name.field("name", "Name as returned"),
                specialty.field("specialty", "Specialty"),
                taxonomyCode.field("taxonomyCode", "Taxonomy code"),
                networkStatus.field("networkStatus", "Network status"),
                inNetwork.field("inNetwork", "In network"),
                phone.field("phone", "Phone"),
                fax.field("fax", "Fax"),
                line1.field("addressLine1", "Address line 1"),
                line2.field("addressLine2", "Address line 2"),
                city.field("city", "City"),
                state.field("state", "State"),
                postalCode.field("postalCode", "Postal code"));
        fields = LookupReader.markMasked(fields, exchange != received);

        String message;
        if (!attention.isEmpty()) {
            message = "Provider found, with " + (attention.size() == 1 ? "1 thing" : attention.size() + " things")
                    + " to check before using this provider.";
        } else if (byStatus == Standing.IN || byFlag == Standing.IN) {
            message = "Provider found, reported as in network.";
        } else {
            // Nothing returned is said, and is not passed off as in network by saying nothing.
            message = "Provider found, but the provider directory returned no network status.";
        }

        additional.addAll(tracked.unconsumed());
        return new ProviderLookupResult(LookupStatus.FOUND, npi, provider, attention, fields, additional, wire(exchange), null, message);
    }

    /**
     * The result of a lookup that was never sent: switched off, not configured, or stopped before
     * sending.
     *
     * @param request the request as it would have gone, marked as not sent, or null when there is none to show
     */
    static ProviderLookupResult notSent(LookupOutcomes.Ending ending, String npi, WireRequest request) {
        return new ProviderLookupResult(ending.status(), npi, null, List.of(), List.of(), List.of(),
                request == null ? null : new WireView(request, null, null), ending.error(), ending.message());
    }

    /** A result with no provider in it: not found, or a fault. */
    private static ProviderLookupResult ended(LookupOutcomes.Ending ending, VendorExchange exchange, String npi,
                                              List<AdditionalField> additional) {
        return new ProviderLookupResult(ending.status(), npi, null, List.of(), List.of(), additional, wire(exchange), ending.error(),
                ending.message());
    }

    private static WireView wire(VendorExchange exchange) {
        return new WireView(exchange.request(), exchange.wireResponse(), null);
    }

    /**
     * The network status a provider carries when the directory states it only as a yes or a no:
     * the plain words for the answer. A change of form, not a guess: the name the value sits under
     * asks whether the provider is in network. The value as it was returned stays in the fields.
     * Anything that is not a yes or a no is kept as it was written.
     */
    private static String statusFrom(LookupReader.Read inNetwork) {
        Boolean yes = inNetwork.yesOrNo();
        if (yes == null) {
            return inNetwork.text();
        }
        return yes ? "in network" : "out of network";
    }

    /**
     * What a returned value says about the network. Nothing returned is NOT_STATED, which is never
     * read as in network.
     *
     * @param asksYesOrNo true for a field that asks "is this provider in network", where a yes and a no are answers
     */
    private static Standing standing(LookupReader.Read read, boolean asksYesOrNo) {
        if (!read.hasText()) {
            return Standing.NOT_STATED;
        }
        Boolean yes = asksYesOrNo ? read.yesOrNo() : null;
        if (yes != null) {
            return yes ? Standing.IN : Standing.OUT;
        }
        String word = read.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (IN_NETWORK_WORDS.contains(word)) {
            return Standing.IN;
        }
        return OUT_OF_NETWORK_WORDS.contains(word) ? Standing.OUT : Standing.UNRECOGNISED;
    }
}
