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
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
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
 * Saved test members and providers: added, replaced and removed one at a time, or imported from a
 * CSV file with a dry run first. Entries are kept as given: nothing is looked up, nothing derived.
 * They exist on both profiles, never reach the ledger, and the log holds counts only.
 *
 * <p>One member ID (in its no-space form), one NPI and one vendor practitioner id name at most one
 * entry, so that a re-import knows which entry a row replaces.
 */
@Service
public class TestListService {

    private static final Logger log = LoggerFactory.getLogger(TestListService.class);

    static final String OFF = "Saved test lists are switched off on this installation (test-lists.enabled).";
    private static final String OFF_ACTION = "Set test-lists.enabled to true in application.yml, then start PAVE again.";

    /**
     * What differs between the two lists; everything else is one piece of code for both.
     *
     * @param sameEntry   the field that two entries share when they are the same entry, or null
     *                    when they are different ones
     */
    private record Kind<E extends TestListEntry, I>(String file, Class<E> type, String idPrefix, String what, List<String> columns,
            Function<Map<String, String>, I> input, BiFunction<I, String, E> withId, Function<E, Map<String, String>> values,
            Function<I, Map<String, String>> inputValues, Function<I, List<FieldError>> rules, BiFunction<Map<String, String>,
            Map<String, String>, String> sameEntry, List<String> keyColumns, String duplicate) {
    }

    private final TestListProperties properties;
    private final TestListStore store;
    private final Clock clock;
    private final Kind<TestMember, TestMemberInput> members;
    private final Kind<TestProvider, TestProviderInput> providers;

    public TestListService(TestListProperties properties, TestListStore store, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.clock = clock;
        this.members = new Kind<>(TestListStore.MEMBERS, TestMember.class, "m-", "member", TestMember.COLUMNS, TestMemberInput::of,
                TestMemberInput::withId, TestMember::values, input -> input.withId(null).values(),
                input -> TestListRules.member(input, LocalDate.now(this.clock)), TestListService::sameMember, List.of("memberId"),
                "A saved member has this member ID already. Edit that entry instead.");
        this.providers = new Kind<>(TestListStore.PROVIDERS, TestProvider.class, "p-", "provider", TestProvider.COLUMNS,
                TestProviderInput::of, TestProviderInput::withId, TestProvider::values, input -> input.withId(null).values(),
                TestListRules::provider, TestListService::sameProvider, List.of("npi", "vendorPractitionerId"),
                "A saved provider has this %s already. Edit that entry instead.");
    }

    private static String sameMember(Map<String, String> a, Map<String, String> b) {
        String one = a.get("memberId");
        String other = b.get("memberId");
        return one != null && other != null && MemberIds.noSpace(one).equals(MemberIds.noSpace(other)) ? "memberId" : null;
    }

    private static String sameProvider(Map<String, String> a, Map<String, String> b) {
        if (a.get("npi") != null && a.get("npi").equals(b.get("npi"))) {
            return "npi";
        }
        if (a.get("vendorPractitionerId") != null && a.get("vendorPractitionerId").equals(b.get("vendorPractitionerId"))) {
            return "vendorPractitionerId";
        }
        return null;
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
                String words = "npi".equals(field) ? "NPI" : "vendor practitioner id";
                throw ValidationException.of(field, kind.duplicate().formatted(words));
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
            throw new BadInputException("The file has no " + String.join(" or ", kind.keyColumns()) + " column, so no row can be "
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
                rows.add(new ImportRow(row.line(), Status.REFUSED, label, "Its NPI and its vendor practitioner id belong to two "
                        + "different saved providers. Correct one of them."));
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
