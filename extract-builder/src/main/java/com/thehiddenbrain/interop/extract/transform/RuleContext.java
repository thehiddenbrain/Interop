package com.thehiddenbrain.interop.extract.transform;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * What a rule may know about the run beyond its inputs: the run date, vendor code, the current row (for
 * CONDITIONAL and DATE_MATH referencing other elements), lookups, masking state, and the warning sink.
 */
public class RuleContext {

    public LocalDate runDate = LocalDate.now();
    public LocalDate businessDate = LocalDate.now();
    public LocalDateTime runTime = LocalDateTime.now();
    public String vendorCode = "";
    public int fileSeq = 1;
    public int versionNo = 1;
    public long rowNumber;
    public String currentFieldId = "";
    /** Element id to value for the current row, unmasked. */
    public Map<String, Object> row = Map.of();
    /** lookupId to table. Populated by the runner once per run. */
    public Function<String, LookupTable> lookups = id -> null;
    public String maskSalt = "demo-salt";
    /** True in a masked run: PHI inputs are overlaid before rules see them. */
    public boolean masking;
    private final Map<Object, Object> maskedOriginals = new HashMap<>();
    private final Map<String, Integer> warningCounts = new LinkedHashMap<>();
    private final Map<String, String> warningExamples = new LinkedHashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Object> groupKeys = new HashMap<>();

    public interface LookupTable {
        Map<String, Object> find(List<Object> keys, LocalDate asOf);
        int size();
    }

    public void warn(String code, String message) {
        String key = currentFieldId + "|" + code;
        warningCounts.merge(key, 1, Integer::sum);
        warningExamples.putIfAbsent(key, message);
    }

    public Map<String, Integer> warningCounts() { return warningCounts; }
    public Map<String, String> warningExamples() { return warningExamples; }

    public long nextSequence(String scopeKey, Object groupKey, long start, long step) {
        Object previousGroup = groupKeys.get(scopeKey);
        boolean first = !groupKeys.containsKey(scopeKey);
        if (first || !Objects.equals(previousGroup, groupKey)) {
            groupKeys.put(scopeKey, groupKey);
            sequences.put(scopeKey, start);
            return start;
        }
        long next = sequences.getOrDefault(scopeKey, start - step) + step;
        sequences.put(scopeKey, next);
        return next;
    }

    public void resetRow(long rowNumber, Map<String, Object> row) {
        this.rowNumber = rowNumber;
        this.row = row;
        maskedOriginals.clear();
    }

    /** Record that {@code masked} stands for {@code original} on this row, so a lookup can still use the real key. */
    public Object mask(Object original) {
        Object masked = Masking.overlay(original, maskSalt);
        if (masked != null) maskedOriginals.put(masked, original);
        return masked;
    }

    public Object original(Object maybeMasked) {
        return maybeMasked != null && maskedOriginals.containsKey(maybeMasked) ? maskedOriginals.get(maybeMasked) : maybeMasked;
    }

    public boolean wasMasked(Object v) {
        return v != null && maskedOriginals.containsKey(v);
    }
}
