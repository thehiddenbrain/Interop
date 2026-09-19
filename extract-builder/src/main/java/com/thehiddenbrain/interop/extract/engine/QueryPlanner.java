package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel.*;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.catalog.SourceData;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.transform.Values;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Executes a compiled definition against the source files: joins entities along catalog join paths, applies the
 * one-of-many selectors, filters, the watermark window, the sort order and the row limit. It returns rows keyed by
 * element id. With a real database this class would hand the SQL in {@link Compiled#sqlText} to JDBC instead.
 */
@Service
public class QueryPlanner {

    private final CatalogService catalog;
    private final SourceData source;

    public QueryPlanner(CatalogService catalog, SourceData source) {
        this.catalog = catalog;
        this.source = source;
    }

    public static class Options {
        public LocalDate runDate = LocalDate.now();
        public LocalDateTime windowFrom;
        public LocalDateTime windowTo;
        public int rowLimit = 0;
        public List<String> cohort = List.of();
        public boolean synthetic;
        public Map<String, Object> boundParams = new LinkedHashMap<>();
    }

    public record Result(List<Map<String, Object>> rows, int scanned, int matched, Map<String, Object> boundParams) {}

    public Result execute(Compiled c, Options o) {
        if (o.synthetic) return synthetic(c, o);
        Entity root = catalog.entity(c.grain);
        List<Map<String, Object>> rootRows = source.rows(root.id());
        Map<String, Map<String, Element>> columnsByEntity = new HashMap<>();
        Map<String, List<Element>> neededByEntity = new LinkedHashMap<>();
        for (String elementId : c.elementsNeeded) {
            Optional<Element> el = catalog.findElement(elementId);
            if (el.isEmpty()) continue;
            neededByEntity.computeIfAbsent(el.get().entity(), k -> new ArrayList<>()).add(el.get());
        }
        Map<String, Joiner> joiners = new LinkedHashMap<>();
        for (Compiled.Join j : c.joins) joiners.put(j.path().to(), buildJoiner(j, o.runDate));

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> cohort = o.cohort == null ? Set.of() : new HashSet<>(o.cohort);
        int scanned = 0;
        for (Map<String, Object> rootRow : rootRows) {
            scanned++;
            if (!cohort.isEmpty()) {
                String pk = pkOf(root, rootRow);
                if (!cohort.contains(pk)) continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("__pk", pkOf(root, rootRow));
            for (Element el : neededByEntity.getOrDefault(root.id(), List.of())) row.put(el.id(), rootRow.get(el.column()));
            Map<String, Map<String, Object>> joined = new HashMap<>();
            joined.put(root.id(), rootRow);
            for (Map.Entry<String, Joiner> e : joiners.entrySet()) {
                Joiner joiner = e.getValue();
                Map<String, Object> fromRow = joined.get(joiner.fromEntity);
                Map<String, Object> target = fromRow == null ? null : joiner.find(fromRow);
                joined.put(e.getKey(), target);
                for (Element el : neededByEntity.getOrDefault(e.getKey(), List.of())) row.put(el.id(), target == null ? null : target.get(el.column()));
            }
            rows.add(row);
        }
        List<Predicate<Map<String, Object>>> predicates = predicates(c.spec, o);
        List<Map<String, Object>> matched = new ArrayList<>();
        outer:
        for (Map<String, Object> row : rows) {
            for (Predicate<Map<String, Object>> p : predicates) if (!p.test(row)) continue outer;
            matched.add(row);
        }
        matched.sort(comparator(c.spec, root));
        int matchedCount = matched.size();
        if (o.rowLimit > 0 && matched.size() > o.rowLimit) matched = new ArrayList<>(matched.subList(0, o.rowLimit));
        return new Result(matched, scanned, matchedCount, o.boundParams);
    }

    private Result synthetic(Compiled c, Options o) {
        int n = o.rowLimit > 0 ? Math.min(o.rowLimit, 50) : 25;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("__pk", "SYN" + i);
            for (String elementId : c.elementsNeeded) {
                Optional<Element> el = catalog.findElement(elementId);
                if (el.isEmpty()) continue;
                Object v = syntheticValue(el.get(), i);
                row.put(elementId, v);
            }
            rows.add(row);
        }
        return new Result(rows, n, n, o.boundParams);
    }

    private Object syntheticValue(Element el, int i) {
        String ex = el.example() == null ? "" : el.example();
        switch (el.type()) {
            case "DATE": {
                try {
                    return Values.date(ex, null).plusDays(i * 37L % 400);
                } catch (RuntimeException e) {
                    return LocalDate.of(1980, 1, 1).plusDays(i * 37L);
                }
            }
            case "TIMESTAMP": return LocalDateTime.of(2026, 9, 1, 2, 0).plusMinutes(i * 17L);
            case "DECIMAL":
            case "INTEGER": {
                try {
                    return Values.decimal(ex).add(java.math.BigDecimal.valueOf(i * 3L));
                } catch (RuntimeException e) {
                    return java.math.BigDecimal.valueOf(i);
                }
            }
            case "BOOLEAN": return i % 3 != 0;
            default: {
                if (el.values() != null && !el.values().isEmpty()) return el.values().get(i % el.values().size());
                if (ex.matches(".*\\d{3,}.*")) return ex.replaceAll("(\\d{3,})", String.format("%0" + Math.max(3, ex.replaceAll("\\D", "").length()) + "d", i * 7919 % 99999));
                return ex.isEmpty() ? "SAMPLE" + i : (i == 1 ? ex : ex + " " + i);
            }
        }
    }

    // ---- joins ----

    private static final class Joiner {
        String fromEntity;
        List<JoinOn> on;
        Map<String, List<Map<String, Object>>> index = new HashMap<>();
        Selector selector;
        LocalDate runDate;
        TargetFilter targetFilter;

        Map<String, Object> find(Map<String, Object> fromRow) {
            StringBuilder key = new StringBuilder();
            for (JoinOn o : on) key.append(Values.text(fromRow.get(o.from()))).append('\u0001');
            List<Map<String, Object>> candidates = index.get(key.toString());
            if (candidates == null || candidates.isEmpty()) return null;
            if (targetFilter != null) {
                candidates = candidates.stream().filter(r -> Values.equalsLoose(r.get(targetFilter.column()), targetFilter.equals())).toList();
                if (candidates.isEmpty()) return null;
            }
            if (selector == null) return candidates.get(0);
            switch (selector.kind()) {
                case "EFFECTIVE_DATED": {
                    Map<String, Object> best = null;
                    for (Map<String, Object> r : candidates) {
                        LocalDate eff = Values.date(r.get(selector.effectiveColumn()), null);
                        LocalDate term = Values.date(r.get(selector.termColumn()), null);
                        if (eff != null && eff.isAfter(runDate)) continue;
                        if (term != null && term.isBefore(runDate)) continue;
                        if (best == null || (eff != null && Values.compare(eff, best.get(selector.effectiveColumn())) > 0)) best = r;
                    }
                    return best;
                }
                case "LATEST_BY": {
                    Map<String, Object> best = null;
                    for (Map<String, Object> r : candidates) {
                        if (best == null || Values.compare(r.get(selector.column()), best.get(selector.column())) > 0) best = r;
                    }
                    return best;
                }
                case "FLAG": {
                    for (Map<String, Object> r : candidates) if (Boolean.TRUE.equals(Values.bool(r.get(selector.column())))) return r;
                    return null;
                }
                default: return candidates.get(0);
            }
        }
    }

    private Joiner buildJoiner(Compiled.Join j, LocalDate runDate) {
        JoinPath path = j.path();
        Joiner joiner = new Joiner();
        joiner.fromEntity = path.via() != null ? catalog.joinPath(path.via()).map(JoinPath::to).orElse(path.from()) : path.from();
        joiner.on = path.on();
        joiner.runDate = runDate;
        joiner.targetFilter = path.targetFilter();
        if (j.selector() != null && path.selectors() != null) {
            joiner.selector = path.selectors().stream().filter(s -> s.id().equals(j.selector())).findFirst().orElse(null);
        }
        for (Map<String, Object> row : source.rows(path.to())) {
            StringBuilder key = new StringBuilder();
            for (JoinOn o : path.on()) key.append(Values.text(row.get(o.to()))).append('\u0001');
            joiner.index.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(row);
        }
        return joiner;
    }

    // ---- filters ----

    private List<Predicate<Map<String, Object>>> predicates(Spec spec, Options o) {
        List<Predicate<Map<String, Object>>> out = new ArrayList<>();
        int p = 0;
        for (Spec.FilterSpec f : spec.filters == null ? List.<Spec.FilterSpec>of() : spec.filters) {
            if (f.template != null && !f.template.isBlank()) {
                FilterTemplate t = catalog.template(f.template);
                Map<String, Object> params = new HashMap<>();
                for (TemplateParam tp : t.params() == null ? List.<TemplateParam>of() : t.params()) {
                    Object v = f.params == null ? null : f.params.get(tp.name());
                    if (v == null) v = tp.defaultValue();
                    params.put(tp.name(), resolveToken(v, o.runDate));
                    o.boundParams.put(tp.name(), Values.text(resolveToken(v, o.runDate)));
                }
                for (Clause cl : t.clauses() == null ? List.<Clause>of() : t.clauses()) out.add(clausePredicate(cl, params, o.runDate));
                continue;
            }
            if (f.element == null) continue;
            String op = f.op == null ? "EQ" : f.op.toUpperCase();
            Object value = resolveToken(f.value, o.runDate);
            p++;
            o.boundParams.put("p" + p, value instanceof List<?> l ? l.stream().map(Values::text).toList() : Values.text(value));
            String element = f.element;
            out.add(row -> test(row.get(element), op, value));
        }
        if (o.windowFrom != null && o.windowTo != null && spec.scope != null && spec.scope.watermarkElements != null && !spec.scope.watermarkElements.isEmpty()) {
            List<String> wm = spec.scope.watermarkElements;
            LocalDateTime from = o.windowFrom, to = o.windowTo;
            o.boundParams.put("windowFrom", from.toString());
            o.boundParams.put("windowTo", to.toString());
            out.add(row -> {
                for (String w : wm) {
                    LocalDateTime v = Values.timestamp(row.get(w));
                    if (v != null && v.isAfter(from) && !v.isAfter(to)) return true;
                }
                return false;
            });
        }
        return out;
    }

    private Predicate<Map<String, Object>> clausePredicate(Clause cl, Map<String, Object> params, LocalDate runDate) {
        if (cl.anyOf() != null && !cl.anyOf().isEmpty()) {
            List<Predicate<Map<String, Object>>> any = cl.anyOf().stream().map(x -> clausePredicate(x, params, runDate)).toList();
            return row -> any.stream().anyMatch(pr -> pr.test(row));
        }
        Object value;
        if (cl.param() != null) value = params.get(cl.param());
        else if (cl.token() != null) {
            value = resolveToken(Map.of("kind", "TOKEN", "token", cl.token()), runDate);
            if (cl.offsetDaysParam() != null && value instanceof LocalDate d) {
                int days = Values.decimal(params.get(cl.offsetDaysParam())).intValue();
                value = d.minusDays(Boolean.TRUE.equals(cl.negate()) ? days : -days);
            }
        } else value = cl.value();
        Object v = value;
        String op = cl.op() == null ? "EQ" : cl.op().toUpperCase();
        return row -> test(row.get(cl.element()), op, v);
    }

    @SuppressWarnings("unchecked")
    public static Object resolveToken(Object value, LocalDate runDate) {
        if (value instanceof Map<?, ?> m && "TOKEN".equals(m.get("kind"))) {
            String token = String.valueOf(m.get("token"));
            int offset = m.get("offsetDays") == null ? 0 : Values.decimal(m.get("offsetDays")).intValue();
            LocalDate base = switch (token) {
                case "RUN_DATE", "BUSINESS_DATE", "TODAY" -> runDate;
                case "START_OF_MONTH" -> runDate.withDayOfMonth(1);
                case "END_OF_MONTH" -> runDate.withDayOfMonth(runDate.lengthOfMonth());
                case "START_OF_YEAR" -> runDate.withDayOfYear(1);
                default -> runDate;
            };
            return base.plusDays(offset);
        }
        if (value instanceof List<?> l) return l.stream().map(x -> resolveToken(x, runDate)).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    static boolean test(Object left, String op, Object value) {
        List<Object> values = value instanceof List<?> l ? (List<Object>) l : value == null ? List.of() : List.of(value);
        Object first = values.isEmpty() ? null : values.get(0);
        try {
            switch (op) {
                case "EQ": return Values.equalsLoose(left, first);
                case "NE": return !Values.equalsLoose(left, first);
                case "IN": return values.stream().anyMatch(v -> Values.equalsLoose(left, v));
                case "NOT_IN": return values.stream().noneMatch(v -> Values.equalsLoose(left, v));
                case "GT": return left != null && first != null && Values.compare(left, first) > 0;
                case "GTE": return left != null && first != null && Values.compare(left, first) >= 0;
                case "LT": return left != null && first != null && Values.compare(left, first) < 0;
                case "LTE": return left != null && first != null && Values.compare(left, first) <= 0;
                case "BETWEEN": return left != null && values.size() >= 2 && Values.compare(left, values.get(0)) >= 0 && Values.compare(left, values.get(1)) <= 0;
                case "IS_NULL": return Values.isBlank(left);
                case "IS_NOT_NULL": return !Values.isBlank(left);
                case "STARTS_WITH": return left != null && first != null && Values.text(left).toUpperCase().startsWith(Values.text(first).toUpperCase());
                case "CONTAINS": return left != null && first != null && Values.text(left).toUpperCase().contains(Values.text(first).toUpperCase());
                default: return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- sort ----

    private Comparator<Map<String, Object>> comparator(Spec spec, Entity root) {
        Comparator<Map<String, Object>> cmp = null;
        for (Spec.SortSpec s : spec.sort == null ? List.<Spec.SortSpec>of() : spec.sort) {
            String el = s.element;
            Comparator<Map<String, Object>> c = (a, b) -> Values.compare(a.get(el), b.get(el));
            if ("DESC".equalsIgnoreCase(s.direction)) c = c.reversed();
            cmp = cmp == null ? c : cmp.thenComparing(c);
        }
        Comparator<Map<String, Object>> pk = (a, b) -> Values.compare(a.get("__pk"), b.get("__pk"));
        return cmp == null ? pk : cmp.thenComparing(pk);
    }

    static String pkOf(Entity root, Map<String, Object> row) {
        List<String> pks = root.primaryKeys();
        if (pks.size() == 1) return Values.text(row.get(pks.get(0)));
        StringBuilder sb = new StringBuilder();
        for (String k : pks) {
            if (sb.length() > 0) sb.append('/');
            sb.append(Values.text(row.get(k)));
        }
        return sb.toString();
    }
}
