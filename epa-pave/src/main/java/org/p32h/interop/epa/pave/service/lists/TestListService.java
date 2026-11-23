package org.p32h.interop.epa.pave.service.lists;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Counts;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Finds;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundCode;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundMember;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundProvider;
import org.p32h.interop.epa.pave.service.lists.TestListViews.CodeList;
import org.p32h.interop.epa.pave.service.lists.TestListViews.DiagnosisList;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportRequest;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportResult;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportRow;
import org.p32h.interop.epa.pave.service.lists.TestListViews.MemberList;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ProviderList;
import org.p32h.interop.epa.pave.service.lists.TestListViews.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Saved test members, providers, procedure codes and diagnosis codes: added, replaced and removed one at a time, or
 * imported from a CSV file with a dry run first. Entries are kept as given: nothing is looked up, nothing derived.
 * They exist on both profiles, never reach the ledger, and the log holds counts only.
 *
 * <p>One member ID (in its no-space form), one vendor patient id, one NPI, one vendor practitioner id
 * and one code in one code system name at most one entry, so that a re-import knows which entry a
 * row replaces.
 *
 * <p>A list of diagnosis codes holds codes alone, as the list of procedure codes does: no member,
 * no patient id and no name stands in an entry, so it says of no one whose diagnosis a code is.
 */
@Service
public class TestListService {

    private static final Logger log = LoggerFactory.getLogger(TestListService.class);

    /** The file of the saved procedure codes, beside the two the store names. */
    static final String CODES = "codes.json";

    /**
     * The file of the saved diagnosis codes. An installation written before this list existed has
     * none, and a list never saved reads as empty, so such a store is read as it is.
     */
    static final String DIAGNOSES = "diagnoses.json";

    static final String OFF = "Saved test lists are switched off on this installation (test-lists.enabled).";
    private static final String OFF_ACTION = "Set test-lists.enabled to true in application.yml, then start PAVE again.";

    /**
     * What differs between the two lists; everything else is one piece of code for both.
     *
     * @param sameEntry   the field that two entries share when they are the same entry, or null
     *                    when they are different ones
     * @param duplicate   the refusal of an entry that shares that field with a saved one, by the field
     * @param twoEntries  the refusal of an imported row whose identifying fields belong to two saved entries
     */
    private record Kind<E extends TestListEntry, I>(String file, Class<E> type, String idPrefix, String what, List<String> columns,
            Function<Map<String, String>, I> input, BiFunction<I, String, E> withId, Function<E, Map<String, String>> values,
            Function<I, Map<String, String>> inputValues, Function<I, List<FieldError>> rules, BiFunction<Map<String, String>,
            Map<String, String>, String> sameEntry, List<String> keyColumns, Function<String, String> duplicate, String twoEntries) {
    }

    private final TestListProperties properties;
    private final TestListStore store;
    private final Clock clock;
    private final Kind<TestMember, TestMemberInput> members;
    private final Kind<TestProvider, TestProviderInput> providers;
    private final Kind<TestCode, TestCodeInput> codes;
    private final Kind<TestCode, TestCodeInput> diagnoses;

    public TestListService(TestListProperties properties, TestListStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
        this.members = new Kind<>(TestListStore.MEMBERS, TestMember.class, "m-", "member", TestMember.COLUMNS, TestMemberInput::of,
                TestMemberInput::withId, TestMember::values, input -> input.withId(null).values(),
                input -> TestListRules.member(input, LocalDate.now(this.clock)), TestListService::sameMember,
                List.of("memberId", "vendorPatientId"),
                field -> "A saved member has this " + ("memberId".equals(field) ? "member ID" : "vendor patient id")
                        + " already. Edit that entry instead.",
                "Its member ID and its vendor patient id belong to two different saved members. Correct one of them.");
        this.providers = new Kind<>(TestListStore.PROVIDERS, TestProvider.class, "p-", "provider", TestProvider.COLUMNS,
                TestProviderInput::of, TestProviderInput::withId, TestProvider::values, input -> input.withId(null).values(),
                TestListRules::provider, TestListService::sameProvider, List.of("npi", "vendorPractitionerId", "organization"),
                field -> "organization".equals(field)
                        ? "A saved provider has this organization, with no NPI and no vendor practitioner id, already. Edit that entry instead."
                        : "A saved provider has this " + ("npi".equals(field) ? "NPI" : "vendor practitioner id")
                        + " already. Edit that entry instead.",
                "Its NPI and its vendor practitioner id belong to two different saved providers. Correct one of them.");
        this.codes = new Kind<>(CODES, TestCode.class, "c-", "procedure code", TestCode.COLUMNS, TestCodeInput::of,
                TestCodeInput::withId, TestCode::values, input -> input.withId(null).values(), TestListRules::code,
                TestListService::sameCode, List.of("code"),
                field -> "A saved procedure code has this code and code system already. Edit that entry instead.",
                "This row matches two saved procedure codes. Correct one of them.");
        // The same entry as a procedure code, under a file and words of its own: a diagnosis code
        // is matched, limited and imported as a procedure code is.
        this.diagnoses = new Kind<>(DIAGNOSES, TestCode.class, "d-", "diagnosis code", TestCode.COLUMNS, TestCodeInput::of,
                TestCodeInput::withId, TestCode::values, input -> input.withId(null).values(), TestListRules::diagnosis,
                TestListService::sameCode, List.of("code"),
                field -> "A saved diagnosis code has this code and code system already. Edit that entry instead.",
                "This row matches two saved diagnosis codes. Correct one of them.");
    }

    /**
     * The same procedure code: the same code in the same system as written, a code with no system
     * being its own entry. 97110 of CPT and 97110 stated with no system are two entries, because
     * the Service card sends the system with the code and the two are sent differently.
     */
    private static String sameCode(Map<String, String> a, Map<String, String> b) {
        String code = a.get("code");
        if (code == null || !code.equals(b.get("code"))) {
            return null;
        }
        String system = a.get("system") == null ? "" : a.get("system");
        return system.equals(b.get("system") == null ? "" : b.get("system")) ? "code" : null;
    }

    /**
     * The same member: the same member ID in its no-space form, or the same vendor patient id. The
     * vendor's id counts since an entry may hold it alone (see {@link TestListRules#member}), and
     * a second entry for the one patient of the vendor's server would only be a second name for it.
     */
    private static String sameMember(Map<String, String> a, Map<String, String> b) {
        String one = a.get("memberId");
        String other = b.get("memberId");
        if (one != null && other != null && MemberIds.noSpace(one).equals(MemberIds.noSpace(other))) {
            return "memberId";
        }
        if (a.get("vendorPatientId") != null && a.get("vendorPatientId").equals(b.get("vendorPatientId"))) {
            return "vendorPatientId";
        }
        return null;
    }

    private static String sameProvider(Map<String, String> a, Map<String, String> b) {
        if (a.get("npi") != null && a.get("npi").equals(b.get("npi"))) {
            return "npi";
        }
        if (a.get("vendorPractitionerId") != null && a.get("vendorPractitionerId").equals(b.get("vendorPractitionerId"))) {
            return "vendorPractitionerId";
        }
        // A facility known by its name alone is told by that name, as nothing else tells it.
        if (organizationAlone(a) && organizationAlone(b) && a.get("organization").equals(b.get("organization"))) {
            return "organization";
        }
        return null;
    }

    private static boolean organizationAlone(Map<String, String> values) {
        return values.get("npi") == null && values.get("vendorPractitionerId") == null && values.get("organization") != null;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public MemberList members() {
        if (!enabled()) {
            return new MemberList(false, List.of(), 0, null);
        }
        TestListStore.Loaded<TestMember> loaded = store.read(members.file(), TestMember.class);
        return new MemberList(true, loaded.entries(), loaded.entries().size(), loaded.problem());
    }

    public ProviderList providers() {
        if (!enabled()) {
            return new ProviderList(false, List.of(), 0, null);
        }
        TestListStore.Loaded<TestProvider> loaded = store.read(providers.file(), TestProvider.class);
        return new ProviderList(true, loaded.entries(), loaded.entries().size(), loaded.problem());
    }

    public CodeList codes() {
        if (!enabled()) {
            return new CodeList(false, List.of(), 0, null);
        }
        TestListStore.Loaded<TestCode> loaded = store.read(codes.file(), TestCode.class);
        return new CodeList(true, loaded.entries(), loaded.entries().size(), loaded.problem());
    }

    public DiagnosisList diagnoses() {
        if (!enabled()) {
            return new DiagnosisList(false, List.of(), 0, null);
        }
        TestListStore.Loaded<TestCode> loaded = store.read(diagnoses.file(), TestCode.class);
        return new DiagnosisList(true, loaded.entries(), loaded.entries().size(), loaded.problem());
    }

    public TestCode addDiagnosis(TestCodeInput input) {
        return add(diagnoses, input);
    }

    public TestCode replaceDiagnosis(String id, TestCodeInput input) {
        return replace(diagnoses, id, input);
    }

    public boolean removeDiagnosis(String id) {
        return remove(diagnoses, id);
    }

    public ImportResult importDiagnoses(ImportRequest request) {
        return importRows(diagnoses, request);
    }

    public TestCode addCode(TestCodeInput input) {
        return add(codes, input);
    }

    public TestCode replaceCode(String id, TestCodeInput input) {
        return replace(codes, id, input);
    }

    public boolean removeCode(String id) {
        return remove(codes, id);
    }

    public ImportResult importCodes(ImportRequest request) {
        return importRows(codes, request);
    }

    public TestMember addMember(TestMemberInput input) {
        return add(members, input);
    }

    public TestMember replaceMember(String id, TestMemberInput input) {
        return replace(members, id, input);
    }

    public boolean removeMember(String id) {
        return remove(members, id);
    }

    public ImportResult importMembers(ImportRequest request) {
        return importRows(members, request);
    }

    public TestProvider addProvider(TestProviderInput input) {
        return add(providers, input);
    }

    public TestProvider replaceProvider(String id, TestProviderInput input) {
        return replace(providers, id, input);
    }

    public boolean removeProvider(String id) {
        return remove(providers, id);
    }

    public ImportResult importProviders(ImportRequest request) {
        return importRows(providers, request);
    }

    /**
     * What adding the entries a collection states did, by list.
     *
     * @param notAdded each entry a list's rules refused, with the reason
     */
    public record FoundAdd(Counts members, Counts providers, Counts codes, Counts diagnoses, List<String> notAdded) {

        public FoundAdd {
            notAdded = notAdded == null ? List.of() : List.copyOf(notAdded);
        }

        @Override
        public String toString() {
            return "FoundAdd[" + members + ", " + providers + ", " + codes + ", " + diagnoses + "]";
        }
    }

    /**
     * Adds what a collection states to the saved lists. An entry a list holds already (the same
     * member ID or vendor patient id, NPI or vendor practitioner id) is left as it is; an entry
     * that does not fit a full list is counted, never dropped in silence. A procedure code or a
     * diagnosis code is the same when its code and its code system are. Each entry is labelled
     * with the request it was found in, so that it is told apart from one typed by hand, and a
     * provider with whether it requests or performs, so that a servicing provider is told from the
     * requester when it is picked.
     */
    public synchronized FoundAdd addFound(Finds finds) {
        requireEnabled();
        List<String> notAdded = new ArrayList<>();
        List<TestMemberInput> memberInputs = new ArrayList<>();
        List<String> memberFrom = new ArrayList<>();
        for (FoundMember found : finds.members()) {
            memberInputs.add(new TestMemberInput(label(found.from()), found.memberId(), found.firstName(), found.lastName(),
                    found.dateOfBirth(), found.gender(), null, found.plan(), found.subscriberId(), found.coverageStatus(),
                    found.coverageStart(), found.coverageEnd(), found.vendorPatientId()));
            memberFrom.add(found.from());
        }
        List<TestProviderInput> providerInputs = new ArrayList<>();
        List<String> providerFrom = new ArrayList<>();
        for (FoundProvider found : finds.providers()) {
            providerInputs.add(new TestProviderInput(providerLabel(found), found.npi(), found.vendorPractitionerId(), found.firstName(),
                    found.lastName(), found.organization(), null, found.phone(), found.fax(), null));
            providerFrom.add(found.from());
        }
        Counts memberCounts = addAll(members, memberInputs, memberFrom, notAdded);
        Counts providerCounts = addAll(providers, providerInputs, providerFrom, notAdded);
        List<TestCodeInput> codeInputs = new ArrayList<>();
        List<String> codeFrom = new ArrayList<>();
        for (FoundCode found : finds.codes()) {
            codeInputs.add(new TestCodeInput(label(found.from()), found.code(), found.system(), found.description()));
            codeFrom.add(found.from());
        }
        Counts codeCounts = addAll(codes, codeInputs, codeFrom, notAdded);
        List<TestCodeInput> diagnosisInputs = new ArrayList<>();
        List<String> diagnosisFrom = new ArrayList<>();
        for (FoundCode found : finds.diagnoses()) {
            // The code, its system and its words: nothing of whose diagnosis it is goes into a list.
            diagnosisInputs.add(new TestCodeInput(label(found.from()), found.code(), found.system(), found.description()));
            diagnosisFrom.add(found.from());
        }
        Counts diagnosisCounts = addAll(diagnoses, diagnosisInputs, diagnosisFrom, notAdded);
        return new FoundAdd(memberCounts, providerCounts, codeCounts, diagnosisCounts, notAdded);
    }

    /**
     * The label of a provider found in a collection: "Performer in <request>" for one an order or a
     * claim names as who performs the service, "Requester in <request>" for the one that orders,
     * both when it is both, else where it came from.
     */
    static String providerLabel(FoundProvider found) {
        String requester = Texts.trimToNull(found.requesterIn());
        String performer = Texts.trimToNull(found.performerIn());
        if (requester != null && performer != null) {
            return requester.equals(performer) ? "Requester and performer in " + performer
                    : "Requester in " + requester + ", performer in " + performer;
        }
        if (performer != null) {
            return "Performer in " + performer;
        }
        return requester != null ? "Requester in " + requester : label(found.from());
    }

    /** The label of an entry found in a collection: where it came from, in the tester's list of labels. */
    static String label(String from) {
        return "From " + (Texts.present(from) ? from : "the collection");
    }

    private <E extends TestListEntry, I> Counts addAll(Kind<E, I> kind, List<I> inputs, List<String> from, List<String> notAdded) {
        TestListStore.Loaded<E> loaded = store.read(kind.file(), kind.type());
        if (!loaded.readable() && !inputs.isEmpty()) {
            // A list whose file cannot be read takes nothing, and the other lists are still added
            // to: the store refuses to write over such a file, and one damaged list is no reason to
            // lose what the others take.
            notAdded.add(loaded.problem());
            // Counts only. Never an entry.
            log.info("Test {} from a collection: {} not added, the list's file could not be read", kind.what() + "s", inputs.size());
            return new Counts(0, 0, 0, 0, inputs.size());
        }
        List<E> entries = new ArrayList<>(loaded.entries());
        int added = 0;
        int already = 0;
        int full = 0;
        int refused = 0;
        for (int i = 0; i < inputs.size(); i++) {
            I input = inputs.get(i);
            Map<String, String> values = kind.inputValues().apply(input);
            boolean there = false;
            for (E entry : entries) {
                there = there || kind.sameEntry().apply(values, kind.values().apply(entry)) != null;
            }
            if (there) {
                already++;
                continue;
            }
            List<FieldError> errors = kind.rules().apply(input);
            if (!errors.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                errors.forEach(error -> reasons.add(error.message()));
                notAdded.add("A " + kind.what() + " found in \"" + from.get(i) + "\" was not added: " + String.join(" ", reasons));
                refused++;
                continue;
            }
            if (entries.size() >= TestListRules.MAX_ENTRIES) {
                full++;
                continue;
            }
            entries.add(kind.withId().apply(input, newId(kind, entries)));
            added++;
        }
        if (added > 0) {
            store.write(kind.file(), entries);
        }
        // Counts only. Never an entry.
        log.info("Test {} from a collection: {} added, {} there already, {} did not fit, {} refused", kind.what() + "s", added, already,
                full, refused);
        return new Counts(added, already, full, refused);
    }

    /** The members template: the header and three rows of placeholders, never a real member. */
    public static String memberTemplate() {
        List<Map<String, String>> rows = List.of(
                row("label", "Needs prior authorization", "memberId", "S123456789", "firstName", "Sample", "lastName", "Placeholder-One",
                        "dateOfBirth", "1980-01-31", "gender", "female", "lineOfBusiness", "Commercial", "plan", "Sample plan",
                        "subscriberId", "S123456789", "coverageStatus", "active", "coverageStart", "2026-01-01", "coverageEnd",
                        "2026-12-31"),
                row("label", "No authorization needed", "memberId", "S123456788", "firstName", "Sample", "lastName", "Placeholder-Two",
                        "dateOfBirth", "1975-06-15", "gender", "male", "lineOfBusiness", "Medicare", "plan", "Sample plan",
                        "subscriberId", "S123456788", "coverageStatus", "active", "coverageStart", "2026-01-01"),
                row("label", "Vendor patient id", "memberId", "S123456787", "firstName", "Sample", "lastName", "Placeholder-Three",
                        "dateOfBirth", "1990-11-02", "gender", "unknown", "lineOfBusiness", "Commercial", "subscriberId", "S123456787",
                        "vendorPatientId", "patient-sample-3"));
        return ListCsv.write(TestMember.COLUMNS, rows);
    }

    /** The procedure codes template: a code of each common system, and one with no system stated. */
    public static String codeTemplate() {
        List<Map<String, String>> rows = List.of(
                row("label", "Therapy, needs prior authorization", "code", "97110", "system", "http://www.ama-assn.org/go/cpt",
                        "description", "Therapeutic exercise"),
                row("label", "Equipment", "code", "E0601", "system", "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                        "description", "Continuous positive airway pressure device"),
                row("label", "No system stated", "code", "95810", "description", "Sleep study, attended"));
        return ListCsv.write(TestCode.COLUMNS, rows);
    }

    /**
     * The diagnosis codes template: two ICD-10-CM codes and one with no system stated. Codes and
     * descriptions only: a row names no member.
     */
    public static String diagnosisTemplate() {
        List<Map<String, String>> rows = List.of(
                row("label", "Sleep study", "code", "G47.33", "system", "http://hl7.org/fhir/sid/icd-10-cm", "description",
                        "Obstructive sleep apnea (adult) (pediatric)"),
                row("label", "Second diagnosis", "code", "E11.9", "system", "http://hl7.org/fhir/sid/icd-10-cm", "description",
                        "Type 2 diabetes mellitus without complications"),
                row("label", "No system stated", "code", "R06.83", "description", "Snoring"));
        return ListCsv.write(TestCode.COLUMNS, rows);
    }

    /** The providers template: an NPI, then two vendor practitioner ids without one. */
    public static String providerTemplate() {
        List<Map<String, String>> rows = List.of(
                row("label", "Ordering provider", "npi", "1234567893", "firstName", "Sample", "lastName", "Placeholder-Provider",
                        "specialty", "Sleep Medicine", "phone", "617-555-0101", "fax", "617-555-0102", "placeOfService", "11"),
                row("label", "Vendor practitioner", "vendorPractitionerId", "P123", "organization", "Sample Clinic", "specialty",
                        "Cardiology", "phone", "617-555-0103", "placeOfService", "22"),
                row("label", "Second vendor practitioner", "vendorPractitionerId", "P124", "firstName", "Sample", "lastName",
                        "Placeholder-Practitioner", "phone", "617-555-0104"));
        return ListCsv.write(TestProvider.COLUMNS, rows);
    }

    private static Map<String, String> row(String... namesAndValues) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            row.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return row;
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ValidationException(OFF, List.of(), OFF_ACTION);
        }
    }

    private <E extends TestListEntry, I> List<E> current(Kind<E, I> kind) {
        // A file that cannot be read is refused by the store on write; reading it here only makes the refusal come first.
        TestListStore.Loaded<E> loaded = store.read(kind.file(), kind.type());
        return new ArrayList<>(loaded.entries());
    }

    private synchronized <E extends TestListEntry, I> E add(Kind<E, I> kind, I input) {
        requireEnabled();
        requireValid(kind, input);
        List<E> entries = current(kind);
        requireUnique(kind, entries, input, null);
        if (entries.size() >= TestListRules.MAX_ENTRIES) {
            throw new ValidationException(TestListRules.FULL, List.of(), "Remove an entry you no longer use, then add this one.");
        }
        E added = kind.withId().apply(input, newId(kind, entries));
        entries.add(added);
        store.write(kind.file(), entries);
        return added;
    }

    private synchronized <E extends TestListEntry, I> E replace(Kind<E, I> kind, String id, I input) {
        requireEnabled();
        List<E> entries = current(kind);
        int at = indexOf(entries, id);
        if (at < 0) {
            throw NotFoundException.noLongerHeld("This saved " + kind.what(), "saved " + kind.what() + "s");
        }
        requireValid(kind, input);
        requireUnique(kind, entries, input, id);
        E replaced = kind.withId().apply(input, entries.get(at).id());
        entries.set(at, replaced);
        store.write(kind.file(), entries);
        return replaced;
    }

    /** False when no entry has the id: removed already, by this window or another. */
    private synchronized <E extends TestListEntry, I> boolean remove(Kind<E, I> kind, String id) {
        requireEnabled();
        List<E> entries = current(kind);
        int at = indexOf(entries, id);
        if (at < 0) {
            return false;
        }
        entries.remove(at);
        store.write(kind.file(), entries);
        return true;
    }

    private static <E extends TestListEntry> int indexOf(List<E> entries, String id) {
        for (int i = 0; id != null && i < entries.size(); i++) {
            if (id.equals(entries.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private static <E extends TestListEntry, I> void requireValid(Kind<E, I> kind, I input) {
        if (input == null) {
            throw new BadInputException("The request has no body.", "Send the " + kind.what() + "'s fields as JSON.");
        }
        List<FieldError> errors = kind.rules().apply(input);
        if (!errors.isEmpty()) {
            throw new ValidationException("The " + kind.what() + " has " + ValidationException.problems(errors.size())
                    + ". Nothing was saved.", errors);
        }
    }

    private static <E extends TestListEntry, I> void requireUnique(Kind<E, I> kind, List<E> entries, I input, String exceptId) {
        Map<String, String> values = kind.inputValues().apply(input);
        for (E entry : entries) {
            String field = entry.id().equals(exceptId) ? null : kind.sameEntry().apply(values, kind.values().apply(entry));
            if (field != null) {
                throw ValidationException.of(field, kind.duplicate().apply(field));
            }
        }
    }

    private static <E extends TestListEntry, I> String newId(Kind<E, I> kind, List<E> entries) {
        Set<String> taken = new HashSet<>();
        entries.forEach(entry -> taken.add(entry.id()));
        String id;
        do {
            id = kind.idPrefix() + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (taken.contains(id));
        return id;
    }

    /**
     * Two files that are often chosen by mistake, told apart from a CSV whose columns are wrong:
     * one saved with semicolons or tabs, as Excel does in some regions, and a JSON file. Without
     * this both read as one column with a long name, and "no memberId column" sends the tester to
     * the wrong cure.
     */
    static void refuseWrongKindOfFile(String csv) {
        int start = 0;
        while (start < csv.length() && (Character.isWhitespace(csv.charAt(start)) || csv.charAt(start) == '\uFEFF')) {
            start++;
        }
        if (start < csv.length() && (csv.charAt(start) == '{' || csv.charAt(start) == '[')) {
            throw new BadInputException("This is a JSON file, not a CSV file.", "Choose the CSV saved from Excel or from the template.");
        }
        int end = csv.indexOf('\n', start);
        String first = csv.substring(start, end < 0 ? csv.length() : end);
        if (first.indexOf(',') < 0 && (first.indexOf(';') >= 0 || first.indexOf('\t') >= 0)) {
            throw new BadInputException("This file is separated by semicolons (or tabs).",
                    "In Excel, save it as CSV UTF-8 (comma delimited), then choose it again.");
        }
    }

    private synchronized <E extends TestListEntry, I> ImportResult importRows(Kind<E, I> kind, ImportRequest request) {
        requireEnabled();
        if (request == null || request.csv() == null || request.csv().isBlank()) {
            throw ValidationException.of("csv", "Choose a CSV file with a header row.");
        }
        if (request.dryRun() == null) {
            throw ValidationException.of("dryRun", "Say whether this is a dry run: true shows what each row would do, false imports.");
        }
        refuseWrongKindOfFile(request.csv());
        ListCsv.Table table = ListCsv.read(request.csv(), kind.columns());
        if (table == null) {
            throw new BadInputException("The file holds no header row.", "Start from the CSV template and keep its first row.");
        }
        if (kind.keyColumns().stream().noneMatch(table.columnsUsed()::contains)) {
            List<String> keys = kind.keyColumns();
            String named = keys.size() < 2 ? String.join("", keys)
                    : String.join(", ", keys.subList(0, keys.size() - 1)) + " or " + keys.get(keys.size() - 1);
            throw new BadInputException("The file has no " + named + " column, so no row can be "
                    + "matched to a saved " + kind.what() + ".", "Start from the CSV template and keep its first row.");
        }
        List<E> working = current(kind);
        // The entries this file has already added or replaced, by index in working, with the line that did it.
        Map<Integer, Integer> touchedBy = new LinkedHashMap<>();
        List<ImportRow> rows = new ArrayList<>();
        int added = 0;
        int replaced = 0;
        int refused = 0;
        for (ListCsv.Row row : table.rows()) {
            String label = row.cells().get("label");
            if (row.problem() != null) {
                rows.add(new ImportRow(row.line(), Status.REFUSED, label, row.problem()));
                refused++;
                continue;
            }
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < working.size(); i++) {
                if (kind.sameEntry().apply(row.cells(), kind.values().apply(working.get(i))) != null) {
                    matches.add(i);
                }
            }
            if (matches.size() > 1) {
                rows.add(new ImportRow(row.line(), Status.REFUSED, label, kind.twoEntries()));
                refused++;
                continue;
            }
            Integer match = matches.isEmpty() ? null : matches.get(0);
            if (match != null && touchedBy.containsKey(match)) {
                rows.add(new ImportRow(row.line(), Status.REFUSED, label, "Line " + touchedBy.get(match) + " is the same "
                        + kind.what() + ". Only the first of the two is imported."));
                refused++;
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (match != null) {
                // A column the file lacks keeps the stored value; a blank cell clears it.
                values.putAll(kind.values().apply(working.get(match)));
            }
            values.putAll(row.cells());
            I input = kind.input().apply(values);
            List<FieldError> errors = kind.rules().apply(input);
            label = kind.inputValues().apply(input).get("label");
            if (!errors.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                errors.forEach(error -> reasons.add(error.message()));
                rows.add(new ImportRow(row.line(), Status.REFUSED, label, String.join(" ", reasons)));
                refused++;
                continue;
            }
            if (match == null) {
                if (working.size() >= TestListRules.MAX_ENTRIES) {
                    rows.add(new ImportRow(row.line(), Status.REFUSED, label, TestListRules.FULL));
                    refused++;
                    continue;
                }
                working.add(kind.withId().apply(input, newId(kind, working)));
                touchedBy.put(working.size() - 1, row.line());
                rows.add(new ImportRow(row.line(), Status.ADD, label, null));
                added++;
            } else {
                working.set(match, kind.withId().apply(input, working.get(match).id()));
                touchedBy.put(match, row.line());
                rows.add(new ImportRow(row.line(), Status.REPLACE, label, null));
                replaced++;
            }
        }
        boolean apply = !request.dryRun();
        if (apply && added + replaced > 0) {
            store.write(kind.file(), working);
        }
        // Counts only. Never a row.
        log.info("Test {} import{}: {} to add, {} to replace, {} refused", kind.what() + "s", apply ? "" : " (dry run)", added, replaced,
                refused);
        return new ImportResult(table.columnsUsed(), table.columnsIgnored(), rows, added, replaced, refused, apply);
    }
}
