package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel.*;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.Spec;

import java.util.*;

/**
 * Renders the SELECT that would run against a real database for a compiled definition. The demo executes against
 * JSON files instead, but the text is shown to approvers and stored on every run so the query is always auditable.
 * Identifiers come only from the catalog; analyst values appear as bind parameters.
 */
final class SqlText {

    private SqlText() {}

    static String render(CatalogService catalog, Definition def, Spec spec, Compiled c) {
        try {
            Entity root = catalog.entity(def.grain);
            StringBuilder sb = new StringBuilder("SELECT\n");
            List<String> cols = new ArrayList<>();
            for (String elementId : c.elementsNeeded) {
                Optional<Element> el = catalog.findElement(elementId);
                if (el.isEmpty()) continue;
                Entity e = catalog.entity(el.get().entity());
                cols.add("  " + e.alias() + "." + el.get().column().toUpperCase() + " AS " + elementId.replace('.', '_'));
            }
            sb.append(String.join(",\n", cols)).append("\n");
            sb.append("FROM ").append(root.table()).append(" ").append(root.alias()).append("\n");
            for (Compiled.Join j : c.joins) {
                Entity to = catalog.entity(j.path().to());
                Entity from = catalog.entity(j.path().from());
                String fromAlias = from.alias();
                if (j.path().via() != null) {
                    Optional<JoinPath> via = catalog.joinPath(j.path().via());
                    if (via.isPresent()) fromAlias = catalog.entity(via.get().to()).alias();
                }
                sb.append("LEFT JOIN ").append(to.table()).append(" ").append(to.alias()).append(" ON ");
                List<String> on = new ArrayList<>();
                for (JoinOn o : j.path().on()) on.add(fromAlias + "." + o.from().toUpperCase() + " = " + to.alias() + "." + o.to().toUpperCase());
                if (j.path().targetFilter() != null) on.add(to.alias() + "." + j.path().targetFilter().column().toUpperCase() + " = '" + j.path().targetFilter().equals() + "'");
                sb.append(String.join(" AND ", on));
                if (j.selector() != null && j.path().selectors() != null) {
                    j.path().selectors().stream().filter(s -> s.id().equals(j.selector())).findFirst().ifPresent(s -> {
                        switch (s.kind()) {
                            case "EFFECTIVE_DATED" -> sb.append("\n  AND ").append(to.alias()).append(".").append(s.effectiveColumn().toUpperCase()).append(" <= :runDate AND (")
                                    .append(to.alias()).append(".").append(s.termColumn().toUpperCase()).append(" IS NULL OR ").append(to.alias()).append(".").append(s.termColumn().toUpperCase()).append(" >= :runDate)");
                            case "LATEST_BY" -> sb.append("\n  AND ").append(to.alias()).append(".").append(s.column().toUpperCase()).append(" = (SELECT MAX(x.").append(s.column().toUpperCase()).append(") FROM ").append(to.table()).append(" x WHERE x.").append(j.path().on().get(0).to().toUpperCase()).append(" = ").append(to.alias()).append(".").append(j.path().on().get(0).to().toUpperCase()).append(")");
                            case "FLAG" -> sb.append("\n  AND ").append(to.alias()).append(".").append(s.column().toUpperCase()).append(" = 1");
                            default -> { }
                        }
                    });
                }
                sb.append("\n");
            }
            List<String> where = new ArrayList<>();
            int p = 0;
            for (Spec.FilterSpec f : spec.filters == null ? List.<Spec.FilterSpec>of() : spec.filters) {
                if (f.template != null && !f.template.isBlank()) {
                    try {
                        where.add("(" + catalog.template(f.template).sql() + ")");
                    } catch (RuntimeException ignored) {
                    }
                    continue;
                }
                Optional<Element> el = catalog.findElement(f.element);
                if (el.isEmpty()) continue;
                String col = catalog.entity(el.get().entity()).alias() + "." + el.get().column().toUpperCase();
                String op = f.op == null ? "EQ" : f.op.toUpperCase();
                p++;
                String param = ":p" + p;
                switch (op) {
                    case "EQ" -> where.add(col + " = " + param);
                    case "NE" -> where.add(col + " <> " + param);
                    case "IN" -> where.add(col + " IN (" + param + ")");
                    case "NOT_IN" -> where.add(col + " NOT IN (" + param + ")");
                    case "GT" -> where.add(col + " > " + param);
                    case "GTE" -> where.add(col + " >= " + param);
                    case "LT" -> where.add(col + " < " + param);
                    case "LTE" -> where.add(col + " <= " + param);
                    case "BETWEEN" -> where.add(col + " BETWEEN " + param + "a AND " + param + "b");
                    case "IS_NULL" -> where.add(col + " IS NULL");
                    case "IS_NOT_NULL" -> where.add(col + " IS NOT NULL");
                    case "STARTS_WITH" -> where.add(col + " LIKE " + param + " || '%'");
                    case "CONTAINS" -> where.add(col + " LIKE '%' || " + param + " || '%'");
                    default -> { }
                }
            }
            if (spec.scope != null && "INCREMENTAL".equals(spec.scope.mode) && spec.scope.watermarkElements != null && !spec.scope.watermarkElements.isEmpty()) {
                List<String> wm = new ArrayList<>();
                for (String w : spec.scope.watermarkElements) {
                    Optional<Element> el = catalog.findElement(w);
                    if (el.isEmpty()) continue;
                    String col = catalog.entity(el.get().entity()).alias() + "." + el.get().column().toUpperCase();
                    wm.add("(" + col + " > :windowFrom AND " + col + " <= :windowTo)");
                }
                if (!wm.isEmpty()) where.add("(" + String.join(" OR ", wm) + ")");
            }
            if (!where.isEmpty()) sb.append("WHERE ").append(String.join("\n  AND ", where)).append("\n");
            List<String> order = new ArrayList<>();
            for (Spec.SortSpec s : spec.sort == null ? List.<Spec.SortSpec>of() : spec.sort) {
                Optional<Element> el = catalog.findElement(s.element);
                if (el.isEmpty()) continue;
                order.add(catalog.entity(el.get().entity()).alias() + "." + el.get().column().toUpperCase() + ("DESC".equalsIgnoreCase(s.direction) ? " DESC" : ""));
            }
            for (String pk : root.primaryKeys()) order.add(root.alias() + "." + pk.toUpperCase());
            sb.append("ORDER BY ").append(String.join(", ", order)).append("\n");
            sb.append("-- sample runs add: FETCH FIRST :rowLimit ROWS ONLY");
            return sb.toString();
        } catch (RuntimeException e) {
            return "-- SQL unavailable: " + e.getMessage();
        }
    }
}
