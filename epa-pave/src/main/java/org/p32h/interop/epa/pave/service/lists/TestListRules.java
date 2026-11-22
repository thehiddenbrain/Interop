package org.p32h.interop.epa.pave.service.lists;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Npi;

/**
 * What a saved entry must hold. Only what a run would refuse, or what would make the entry
 * useless, is checked; every other value is the tester's to write. No sentence repeats the value
 * it is about: a list entry is member data.
 */
final class TestListRules {

    static final int MAX_ENTRIES = 200;
    static final String FULL = "This list holds 200 entries, the most it keeps.";
    static final Set<String> GENDERS = Set.of("female", "male", "other", "unknown");

    private static final int MAX_MEMBER_ID = 30;
    private static final int MAX_RESOURCE_ID = 64;

    private TestListRules() {
    }

    static List<FieldError> member(TestMemberInput member, LocalDate today) {
        List<FieldError> errors = new ArrayList<>();
        if (member.memberId() == null) {
            errors.add(new FieldError("memberId", "Enter the member ID as it is on the card."));
        } else if (member.memberId().length() > MAX_MEMBER_ID) {
            errors.add(new FieldError("memberId", "A member ID is at most 30 characters. Check that nothing else was pasted with it."));
        }
        LocalDate born = date(errors, "dateOfBirth", "the date of birth", member.dateOfBirth());
        if (born != null && born.isAfter(today)) {
            errors.add(new FieldError("dateOfBirth", "The date of birth is after today."));
        }
        if (member.gender() != null && !GENDERS.contains(member.gender())) {
            errors.add(new FieldError("gender", "Gender is female, male, other or unknown, or left blank."));
        }
        date(errors, "coverageStart", "the coverage start", member.coverageStart());
        date(errors, "coverageEnd", "the coverage end", member.coverageEnd());
        resourceId(errors, "vendorPatientId", "patient", member.vendorPatientId());
        return errors;
    }

    static List<FieldError> provider(TestProviderInput provider) {
        List<FieldError> errors = new ArrayList<>();
        if (provider.npi() == null && provider.vendorPractitionerId() == null) {
            errors.add(new FieldError("npi", "Enter an NPI or a vendor practitioner id."));
        } else if (provider.npi() != null) {
            String problem = Npi.problem(provider.npi());
            if (problem != null) {
                errors.add(new FieldError("npi", problem));
            }
        }
        resourceId(errors, "vendorPractitionerId", "practitioner", provider.vendorPractitionerId());
        return errors;
    }

    /** The date, or null when it is absent or not a date as yyyy-MM-dd, in which case the error is added. */
    private static LocalDate date(List<FieldError> errors, String field, String words, String value) {
        if (value == null) {
            return null;
        }
        try {
            // ISO_LOCAL_DATE resolves strictly: 2026-02-30 is refused, not moved to March.
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            errors.add(new FieldError(field, "Write " + words + " as yyyy-MM-dd, for example 1980-01-31."));
            return null;
        }
    }

    /** The vendor's id is sent as a FHIR resource id, so it holds only what such an id may hold. */
    private static void resourceId(List<FieldError> errors, String field, String what, String value) {
        if (value == null) {
            return;
        }
        boolean fits = value.length() <= MAX_RESOURCE_ID;
        for (int i = 0; fits && i < value.length(); i++) {
            char c = value.charAt(i);
            fits = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
        }
        if (!fits) {
            errors.add(new FieldError(field, "The vendor's " + what + " id is letters, digits, - and . only, at most 64 characters."));
        }
    }
}
