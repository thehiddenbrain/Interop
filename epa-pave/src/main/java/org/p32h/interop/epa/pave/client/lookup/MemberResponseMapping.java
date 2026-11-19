package org.p32h.interop.epa.pave.client.lookup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;

/**
 * EVERYTHING THIS TOOL ASSUMES ABOUT THE MEMBER API, IN ONE FILE.
 *
 * <p>The response contract of the member API is not final. What a record may be wrapped in, what
 * each field may be called and which words mean coverage is active are all assumptions, listed in
 * the tables below and applied in {@link #map}. The mock client and the live client both read a
 * response through that one method, so what a tester sees on mock is produced by the code the live
 * profile runs.
 *
 * <p>The mapping is tolerant on purpose and is meant to be narrowed: when the contract is known,
 * delete the spellings and wrapper names the service does not use. Whatever the mapping does not
 * read is never dropped. It stays in the returned fields, which is where the evidence for
 * finishing this file comes from.
 */
final class MemberResponseMapping {

    private static final LookupWords WORDS = LookupWords.MEMBER;

    /** The names a response may wrap the record in, in the order they are tried. */
    static final List<String> WRAPPERS = List.of("data", "result", "results", "member", "members");

    // Each list is in order of preference: the first spelling that carries a value is the one read.
    // subscriberId is deliberately not a spelling of the member ID. A dependent has both, and they differ.
    static final List<Spelling> MEMBER_ID = Spelling.list("memberId", "memberNumber", "memberIdentifier", "memberNo", "id");
    // A bare "first" or "last" is not a name here: beside a list of results those two say which page it is.
    static final List<Spelling> FIRST_NAME = Spelling.list("firstName", "givenName", "name.first", "name.firstName", "name.givenName",
            "name.given[0]", "name.given");
    static final List<Spelling> LAST_NAME = Spelling.list("lastName", "familyName", "surname", "name.last", "name.lastName",
            "name.familyName", "name.family", "name.surname");
    /** A name in one piece. It is shown as returned and never split: which part is the family name would be a guess. */
    static final List<Spelling> FULL_NAME = Spelling.list("fullName", "memberName", "displayName", "name", "name.text", "name.full",
            "name.fullName");
    static final List<Spelling> DATE_OF_BIRTH = Spelling.list("dateOfBirth", "dob", "birthDate", "birthday");
    static final List<Spelling> GENDER = Spelling.list("gender", "sex", "genderCode", "administrativeGender");
    static final List<Spelling> LINE_OF_BUSINESS = Spelling.list("lineOfBusiness", "lob", "businessLine", "coverage.lineOfBusiness",
            "coverage.lob", "plan.lineOfBusiness", "plan.lob", "eligibility.lineOfBusiness");
    static final List<Spelling> PLAN_NAME = Spelling.list("planName", "plan", "planDescription", "benefitPlan", "benefitPlanName",
            "productName", "plan.name", "plan.planName", "coverage.planName", "coverage.plan", "coverage.plan.name");
    /**
     * A bare {@code status} is deliberately not on this list. On a member record it could be the
     * status of the record, of the enrolment or of the request, and an error body carries one too.
     */
    static final List<Spelling> COVERAGE_STATUS = Spelling.list("coverageStatus", "eligibilityStatus", "enrollmentStatus",
            "coverage.status", "eligibility.status", "enrollment.status");
    /** A yes or no about coverage, for an API that states it that way. */
    static final List<Spelling> COVERAGE_ACTIVE = Spelling.list("coverageActive", "isCoverageActive", "activeCoverage",
            "hasActiveCoverage", "coverage.active", "coverage.isActive");
    static final List<Spelling> COVERAGE_START = Spelling.list("coverageStart", "coverageStartDate", "coverageEffectiveDate",
            "effectiveDate", "startDate", "eligibilityStartDate", "coverage.start", "coverage.startDate", "coverage.effectiveDate",
            "coverage.period.start", "eligibility.startDate", "eligibility.effectiveDate", "period.start");
    static final List<Spelling> COVERAGE_END = Spelling.list("coverageEnd", "coverageEndDate", "coverageTerminationDate",
            "terminationDate", "termDate", "endDate", "expirationDate", "eligibilityEndDate", "coverage.end", "coverage.endDate",
            "coverage.terminationDate", "coverage.termDate", "coverage.period.end", "eligibility.endDate",
            "eligibility.terminationDate", "period.end");
    static final List<Spelling> SUBSCRIBER_ID = Spelling.list("subscriberId", "subscriberNumber", "subscriberIdentifier",
            "subscriber.id", "subscriber.subscriberId", "coverage.subscriberId");

    /** The names an address object may have. All parts are read from the first one found, never from two. */
    static final List<String> ADDRESS_OBJECTS = List.of("address", "homeAddress", "residentialAddress", "mailingAddress", "primaryAddress");
    static final List<Spelling> LINE_1_IN_ADDRESS = Spelling.list("line1", "addressLine1", "address1", "street", "street1",
            "streetAddress", "line[0]", "line");
    static final List<Spelling> LINE_2_IN_ADDRESS = Spelling.list("line2", "addressLine2", "address2", "street2", "line[1]");
    static final List<Spelling> CITY_IN_ADDRESS = Spelling.list("city", "town");
    static final List<Spelling> STATE_IN_ADDRESS = Spelling.list("state", "stateCode", "province");
    static final List<Spelling> POSTAL_CODE_IN_ADDRESS = Spelling.list("postalCode", "zip", "zipCode", "postal");
    // Used only when the record has no address object and writes the parts beside its other fields.
    static final List<Spelling> LINE_1_FLAT = Spelling.list("addressLine1", "address1", "street", "streetAddress");
    static final List<Spelling> LINE_2_FLAT = Spelling.list("addressLine2", "address2", "street2");
    static final List<Spelling> CITY_FLAT = Spelling.list("city");
    static final List<Spelling> STATE_FLAT = Spelling.list("state", "stateCode");
    static final List<Spelling> POSTAL_CODE_FLAT = Spelling.list("postalCode", "zip", "zipCode");

    /**
     * Names so common in any JSON that a value under one does not show the answer is a member. An
     * error body has a name and a state too, and a search that found nothing can echo its dates.
     */
    static final Set<Spelling> GENERIC = Set.copyOf(Spelling.list("name", "state", "plan", "startDate", "endDate", "effectiveDate",
            "expirationDate"));

    /** The only word taken to mean coverage is active. Compared ignoring case, spaces and punctuation. */
    static final Set<String> ACTIVE_WORDS = Set.of("active");
    /**
     * Words taken to mean coverage is not active. Any other word is not sorted into either group:
     * the result says it is not recognised, because calling it active or inactive would be a guess.
     */
    static final Set<String> NOT_ACTIVE_WORDS = Set.of("inactive", "notactive", "terminated", "termed", "cancelled", "canceled",
            "expired", "ended", "lapsed", "suspended", "disenrolled", "draft", "enteredinerror");

    private enum Standing {
        ACTIVE, NOT_ACTIVE, UNRECOGNISED, NOT_STATED
    }

    private MemberResponseMapping() {
    }

    /**
     * THE ONE METHOD that turns a member API response into a lookup result. Mock and live both
     * call it.
     *
     * @param exchange the call as it happened: a response, or the way it failed
     * @param memberId the ID as typed and as sent
     * @param today    the date coverage dates are compared with
     */
    static MemberLookupResult map(VendorExchange exchange, MemberIdForms memberId, LocalDate today) {
        // First, so a body that could not be parsed and any note about the body are never left to the wire view alone.
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
        TrackedJson tracked = TrackedJson.of(exchange.json());

        LookupOutcomes.Ending ending = LookupOutcomes.ofCall(exchange, WORDS, tracked);
        if (ending != null) {
            additional.addAll(tracked.unconsumed());
            return ended(ending, exchange, memberId, additional);
        }

        // The envelope, apart from the fields.
        LookupEnvelope.Opened opened = LookupEnvelope.open(tracked.root(), WRAPPERS);
        if (opened.kind() != LookupEnvelope.Kind.RECORD) {
            additional.addAll(tracked.unconsumed());
            return ended(switch (opened.kind()) {
                case SEVERAL -> LookupOutcomes.several(WORDS, opened.count(), exchange);
                case NOT_A_RECORD -> LookupOutcomes.notARecord(WORDS, exchange);
                default -> LookupOutcomes.noRecord(WORDS);
            }, exchange, memberId, additional);
        }

        // The fields.
        LookupReader reader = new LookupReader(opened.scopes());
        LookupReader.Read id = reader.read(MEMBER_ID);
        LookupReader.Read firstName = reader.read(FIRST_NAME);
        LookupReader.Read lastName = reader.read(LAST_NAME);
        LookupReader.Read fullName = reader.read(FULL_NAME);
        LookupReader.Read dateOfBirth = reader.read(DATE_OF_BIRTH);
        LookupReader.Read gender = reader.read(GENDER);
        LookupReader.Read lineOfBusiness = reader.read(LINE_OF_BUSINESS);
        LookupReader.Read planName = reader.read(PLAN_NAME);
        LookupReader.Read coverageStatus = reader.read(COVERAGE_STATUS);
        LookupReader.Read coverageActive = reader.read(COVERAGE_ACTIVE);
        LookupReader.Read coverageStart = reader.read(COVERAGE_START);
        LookupReader.Read coverageEnd = reader.read(COVERAGE_END);
        LookupReader.Read subscriberId = reader.read(SUBSCRIBER_ID);
        TrackedJson.Node addressObject = reader.object(ADDRESS_OBJECTS);
        LookupReader address = addressObject == null ? reader : new LookupReader(List.of(addressObject));
        LookupReader.Read line1 = address.read(addressObject == null ? LINE_1_FLAT : LINE_1_IN_ADDRESS);
        LookupReader.Read line2 = address.read(addressObject == null ? LINE_2_FLAT : LINE_2_IN_ADDRESS);
        LookupReader.Read city = address.read(addressObject == null ? CITY_FLAT : CITY_IN_ADDRESS);
        LookupReader.Read state = address.read(addressObject == null ? STATE_FLAT : STATE_IN_ADDRESS);
        LookupReader.Read postalCode = address.read(addressObject == null ? POSTAL_CODE_FLAT : POSTAL_CODE_IN_ADDRESS);

        // Is this a member at all? It is when it says something about one: a value under a name that
        // only a member record would use. The ID alone does not count, because a service can hand
        // the ID back in the very answer that says it found nobody.
        List<LookupReader.Read> about = List.of(firstName, lastName, fullName, dateOfBirth, gender, lineOfBusiness, planName,
                coverageStatus, coverageActive, coverageStart, coverageEnd, subscriberId, line1, line2, city, state, postalCode);
        if (about.stream().noneMatch(read -> read.hasText() && !GENERIC.contains(read.spelling()))) {
            // Read again from the start, so nothing the abandoned reading touched is missing from the returned fields.
            additional.addAll(TrackedJson.of(exchange.json()).unconsumed());
            return ended(LookupEnvelope.holdsAValue(opened.record().raw(), memberId.sent()) ? LookupOutcomes.unrecognised(WORDS, exchange)
                    : LookupOutcomes.hollowRecord(WORDS), exchange, memberId, additional);
        }

        // What needs a look. A found member stays found: these are reasons to check before using them.
        List<String> attention = new ArrayList<>();
        Standing byStatus = standing(coverageStatus.text(), false);
        Standing byFlag = standing(coverageActive.text(), true);
        if ((byStatus == Standing.ACTIVE && byFlag == Standing.NOT_ACTIVE) || (byStatus == Standing.NOT_ACTIVE && byFlag == Standing.ACTIVE)) {
            attention.add("The member API contradicts itself about coverage: it returned the coverage status \"" + coverageStatus.text()
                    + "\" and " + coverageActive.source() + " as " + coverageActive.text() + ". Coverage is not taken to be active.");
        } else {
            if (byStatus == Standing.NOT_ACTIVE) {
                attention.add("Coverage is not active: the member API returned the coverage status \"" + coverageStatus.text() + "\".");
            }
            if (byFlag == Standing.NOT_ACTIVE) {
                attention.add("Coverage is not active: the member API returned " + coverageActive.source() + " as "
                        + coverageActive.text() + ".");
            }
        }
        if (byStatus == Standing.UNRECOGNISED) {
            attention.add("The coverage status \"" + coverageStatus.text() + "\" is not one this tool recognises, so it is treated as "
                    + "neither active nor inactive.");
        }
        if (byFlag == Standing.UNRECOGNISED) {
            attention.add("The value \"" + coverageActive.text() + "\" of " + coverageActive.source() + " is not a yes or a no this tool "
                    + "recognises, so it says nothing about whether coverage is active.");
        }
        LocalDate ends = LookupDates.parse(coverageEnd.text());
        if (ends != null && ends.isBefore(today)) {
            attention.add("Coverage ended on " + ends + ", which is before today, " + today + ".");
        }
        LocalDate starts = LookupDates.parse(coverageStart.text());
        if (starts != null && starts.isAfter(today)) {
            attention.add("Coverage does not start until " + starts + ", which is after today, " + today + ".");
        }
        String dateOfBirthText = date(dateOfBirth, "date of birth", false, attention);
        String coverageStartText = date(coverageStart, "coverage start date", true, attention);
        String coverageEndText = date(coverageEnd, "coverage end date", true, attention);
        if (id.hasText() && !MemberIds.noSpace(id.text()).equals(memberId.sent())) {
            attention.add(MemberIds.noSpace(id.text()).equalsIgnoreCase(memberId.sent())
                    ? "The member API writes this member ID in different letter case from the ID that was typed. The ID is sent as typed."
                    : "The member API returned a record under a different member ID from the one that was asked for. Check that this "
                    + "is the member you meant. The ID that was typed is the one used.");
        }

        // The member. The ID is always the one that was asked for, in the form that is sent, so a
        // lookup can never change which ID the next request carries.
        PostalAddress postal = line1.hasText() || line2.hasText() || city.hasText() || state.hasText() || postalCode.hasText()
                ? new PostalAddress(line1.text(), line2.text(), city.text(), state.text(), postalCode.text()) : null;
        MemberDetail member = MemberDetail.builder()
                .memberId(memberId.sent())
                .firstName(firstName.text())
                .lastName(lastName.text())
                .dateOfBirth(dateOfBirthText)
                .gender(gender.text())
                .lineOfBusiness(lineOfBusiness.text())
                .planName(planName.text())
                // Kept as the API wrote it. Which standard code a source system's own word corresponds to is not this file's call.
                .coverageStatus(coverageStatus.hasText() ? coverageStatus.text() : coverageActive.text())
                .coverageStart(coverageStartText)
                .coverageEnd(coverageEndText)
                .subscriberId(subscriberId.text())
                .address(postal)
                .build();

        List<LookupField> fields = List.of(
                id.field("memberId", "Member ID"),
                firstName.field("firstName", "First name"),
                lastName.field("lastName", "Last name"),
                fullName.field("fullName", "Name as returned"),
                dateOfBirth.field("dateOfBirth", "Date of birth"),
                gender.field("gender", "Gender"),
                lineOfBusiness.field("lineOfBusiness", "Line of business"),
                planName.field("planName", "Plan"),
                coverageStatus.field("coverageStatus", "Coverage status"),
                coverageActive.field("coverageActive", "Coverage active"),
                coverageStart.field("coverageStart", "Coverage start"),
                coverageEnd.field("coverageEnd", "Coverage end"),
                subscriberId.field("subscriberId", "Subscriber ID"),
                line1.field("addressLine1", "Address line 1"),
                line2.field("addressLine2", "Address line 2"),
                city.field("city", "City"),
                state.field("state", "State"),
                postalCode.field("postalCode", "Postal code"));

        String message;
        if (!attention.isEmpty()) {
            message = "Member found, with " + (attention.size() == 1 ? "1 thing" : attention.size() + " things")
                    + " to check before using this member.";
        } else if (byStatus == Standing.ACTIVE || byFlag == Standing.ACTIVE) {
            message = "Member found, with coverage reported as active.";
        } else {
            // Not returned is said, and is not passed off as active by saying nothing.
            message = "Member found, but the member API did not return a coverage status.";
        }

        additional.addAll(tracked.unconsumed());
        return new MemberLookupResult(LookupStatus.FOUND, memberId, member, attention, fields, additional, exchange.wire(memberId), null,
                message);
    }

    /** A result with no member in it: not found, or a fault. */
    private static MemberLookupResult ended(LookupOutcomes.Ending ending, VendorExchange exchange, MemberIdForms memberId,
                                            List<AdditionalField> additional) {
        return new MemberLookupResult(ending.status(), memberId, null, List.of(), List.of(), additional, exchange.wire(memberId),
                ending.error(), ending.message());
    }

    /**
     * What a returned value says about coverage. Nothing returned is NOT_STATED, which is never
     * read as active.
     *
     * @param yesOrNo true for a field that asks "is coverage active", where true and false are answers
     */
    private static Standing standing(String text, boolean yesOrNo) {
        if (text == null) {
            return Standing.NOT_STATED;
        }
        String word = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (yesOrNo && Set.of("true", "yes", "y").contains(word)) {
            return Standing.ACTIVE;
        }
        if (yesOrNo && Set.of("false", "no", "n").contains(word)) {
            return Standing.NOT_ACTIVE;
        }
        if (ACTIVE_WORDS.contains(word)) {
            return Standing.ACTIVE;
        }
        return NOT_ACTIVE_WORDS.contains(word) ? Standing.NOT_ACTIVE : Standing.UNRECOGNISED;
    }

    /**
     * The date in ISO form when it can be read as a date. Otherwise the text as received, with a
     * sentence saying so: a date this tool had to guess at would fail further down with no cause on screen.
     */
    private static String date(LookupReader.Read read, String what, boolean compared, List<String> attention) {
        if (!read.hasText()) {
            return null;
        }
        String iso = LookupDates.toIso(read.text());
        if (iso != null) {
            return iso;
        }
        attention.add("The " + what + " is not written in a form this tool reads as a date, so it is shown as received"
                + (compared ? " and was not compared with today's date." : "."));
        return read.text();
    }
}
