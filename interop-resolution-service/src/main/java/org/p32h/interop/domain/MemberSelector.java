package org.p32h.interop.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * From the records MMI returned to the one that represents the patient on the date of service.
 *
 * <ol>
 * <li>Identical records (same stored id) are merged.</li>
 * <li>Records linked through {@code legacyMemberId} in either direction (a THP&lt;-&gt;HPHC conversion)
 *     are one person with two records.</li>
 * <li>If a date of birth was supplied and at least one record carries one, only persons with a matching
 *     DOB survive; none left is a {@link DobMismatchException}.</li>
 * <li>One person left: its record covering the date of service wins, else the record whose coverage ends
 *     last. Several persons left: AMBIGUOUS.</li>
 * </ol>
 * MMI's record order is never a criterion, and nobody is ever picked "by default".
 */
public final class MemberSelector {

    private static final Logger log = LoggerFactory.getLogger(MemberSelector.class);

    public static final String MULTIPLE_PERSONS = "MULTIPLE_PERSONS";
    public static final String DOB_NOT_DISCRIMINATING = "DOB_NOT_DISCRIMINATING";
    public static final String DOB_NOT_ON_RECORDS = "DOB_NOT_ON_RECORDS";

    private final CoverageEvaluator evaluator;

    public MemberSelector(CoverageEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** A single date of service. */
    public SelectionResult select(List<MemberRecord> records, LocalDate dateOfBirth, LocalDate dateOfService) {
        return select(records, dateOfBirth, dateOfService, dateOfService);
    }

    /**
     * @param start the first date of service: the record that covers it is the one whose stored id is returned
     * @param end   the last date of service (equal to {@code start} for a single date); coverage must hold throughout
     */
    public SelectionResult select(List<MemberRecord> records, LocalDate dateOfBirth, LocalDate start, LocalDate end) {
        List<MemberRecord> merged = mergeDuplicates(records);
        List<List<MemberRecord>> persons = groupPersons(merged);

        boolean dobApplied = false;
        boolean dobUnchecked = false;
        if (dateOfBirth != null) {
            boolean anyDob = merged.stream().anyMatch(r -> r.dateOfBirth() != null);
            if (anyDob) {
                persons = persons.stream()
                        .filter(p -> p.stream().anyMatch(r -> dateOfBirth.equals(r.dateOfBirth())))
                        .toList();
                dobApplied = true;
                if (persons.isEmpty()) {
                    throw new DobMismatchException();
                }
            } else {
                log.warn("marker=DOB_UNAVAILABLE_ON_RECORDS dateOfBirth supplied but no returned record carries one; continuing unverified");
                dobUnchecked = true;
            }
        }

        if (persons.size() == 1) {
            List<MemberRecord> person = persons.get(0);
            MemberRecord chosen = chooseWithinPerson(person, start);
            List<CoverageSpan> union = personSpans(person);
            return new SelectionResult.Selected(chosen, evaluator.evaluate(union, start, end), union.size(),
                    person.stream().mapToInt(MemberRecord::unreadableSpans).sum());
        }
        return new SelectionResult.Ambiguous(dobApplied ? DOB_NOT_DISCRIMINATING : dobUnchecked ? DOB_NOT_ON_RECORDS : MULTIPLE_PERSONS,
                persons.size());
    }

    /** Every readable span of the person, across all of its records; a converted pair's history is one history. */
    private static List<CoverageSpan> personSpans(List<MemberRecord> person) {
        List<CoverageSpan> all = new ArrayList<>();
        for (MemberRecord r : person) {
            for (CoverageSpan s : r.spans()) {
                if (!all.contains(s)) {
                    all.add(s);
                }
            }
        }
        return all;
    }

    private static List<MemberRecord> mergeDuplicates(List<MemberRecord> records) {
        Map<String, MemberRecord> byKey = new LinkedHashMap<>();
        for (MemberRecord r : records) {
            String key = r.company() + "|" + r.matchKey();
            MemberRecord existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, r);
            } else {
                List<CoverageSpan> spans = new ArrayList<>(existing.spans());
                for (CoverageSpan s : r.spans()) {
                    if (!spans.contains(s)) {
                        spans.add(s);
                    }
                }
                log.warn("marker=DUPLICATE_RECORDS_MERGED two MMI records share one stored id; spans merged");
                byKey.put(key, existing.withSpans(spans, existing.unreadableSpans() + r.unreadableSpans()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Union-find over legacy links: A.legacy == B.id or B.legacy == A.id puts A and B in one person. */
    private static List<List<MemberRecord>> groupPersons(List<MemberRecord> records) {
        int n = records.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (linked(records.get(i), records.get(j))) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<MemberRecord>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(records.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private static boolean linked(MemberRecord a, MemberRecord b) {
        return (a.legacyMatchKey() != null && a.legacyMatchKey().equals(b.matchKey()))
                || (b.legacyMatchKey() != null && b.legacyMatchKey().equals(a.matchKey()));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /** Within one person (normally one record; two for a converted member) pick the record that owns the date of service. */
    private MemberRecord chooseWithinPerson(List<MemberRecord> person, LocalDate dateOfService) {
        if (person.size() == 1) {
            return person.get(0);
        }
        List<MemberRecord> active = person.stream()
                .filter(r -> evaluator.evaluate(r.spans(), dateOfService).active())
                .toList();
        if (active.size() == 1) {
            return active.get(0);
        }
        List<MemberRecord> pool = active.isEmpty() ? person : active;
        if (!active.isEmpty()) {
            log.warn("marker=CONVERTED_OVERLAP both records of a converted member cover the date of service; choosing the newer record");
            // the newer record is the one that names the other as its legacy id
            return pool.stream().filter(r -> r.legacyMatchKey() != null).findFirst().orElse(pool.get(0));
        }
        return pool.stream()
                .max(Comparator.comparing((MemberRecord r) -> r.spans().stream()
                        .map(CoverageSpan::endForSorting).max(Comparator.naturalOrder()).orElse(LocalDate.MIN)))
                .orElse(pool.get(0));
    }
}
