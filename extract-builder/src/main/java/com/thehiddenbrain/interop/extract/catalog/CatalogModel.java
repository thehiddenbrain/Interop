package com.thehiddenbrain.interop.extract.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The developer-curated catalog: what analysts can pick from and how it maps to physical columns.
 * Everything here is read from {@code data/catalog.json} and never changed by the application.
 */
public final class CatalogModel {

    private CatalogModel() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(String version, List<SubjectArea> subjectAreas, List<Entity> entities, List<JoinPath> joinPaths,
                          List<Element> elements, List<Lookup> lookups, List<FilterTemplate> filterTemplates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubjectArea(String id, String name, String description, List<Grain> grains) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Grain(String entity, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entity(String id, String subjectArea, String name, String source, String table, String alias, Object primaryKey,
                         String watermarkColumn, List<String> defaultSort, String readinessCheck, String selfAliasOf) {
        @SuppressWarnings("unchecked")
        public List<String> primaryKeys() {
            if (primaryKey instanceof List<?> l) return (List<String>) l;
            return List.of(String.valueOf(primaryKey));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JoinPath(String id, String from, String to, String cardinality, List<JoinOn> on, List<Selector> selectors,
                           TargetFilter targetFilter, String via, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JoinOn(String from, String to) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Selector(String id, String label, String kind, String column, String effectiveColumn, String termColumn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetFilter(String column, String equals) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Element(String id, String entity, String column, String name, String type, String sensitivity, String description,
                          List<String> aliases, String example, Boolean filterable, List<String> values, Boolean watermark, Boolean restricted) {
        public boolean isPhi() { return "PHI".equals(sensitivity) || "PII".equals(sensitivity); }
        public boolean isFilterable() { return Boolean.TRUE.equals(filterable); }
        public boolean isWatermark() { return Boolean.TRUE.equals(watermark); }
        public boolean isRestricted() { return Boolean.TRUE.equals(restricted); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lookup(String id, String name, String source, List<String> keyColumns, List<String> columns, String defaultReturn,
                         Boolean unique, String owner, String refresh) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FilterTemplate(String id, String entity, String label, String description, List<TemplateParam> params, String sql,
                                 List<Clause> clauses) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateParam(String name, String type, String label, Object defaultValue) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public TemplateParam(@com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                             @com.fasterxml.jackson.annotation.JsonProperty("type") String type,
                             @com.fasterxml.jackson.annotation.JsonProperty("label") String label,
                             @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue) {
            this.name = name; this.type = type; this.label = label; this.defaultValue = defaultValue;
        }
    }

    /** One predicate of a filter template. Either a leaf (element, op, value/param/token) or {@code anyOf} leaves OR-ed together. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Clause(String element, String op, Object value, String param, String token, String offsetDaysParam, Boolean negate,
                         List<Clause> anyOf) {}

    /** Convenience view returned to the UI: the element plus the entity it belongs to. */
    public record ElementView(Element element, Entity entity, int usedBy) {}

    public static Map<String, Object> asMap(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
