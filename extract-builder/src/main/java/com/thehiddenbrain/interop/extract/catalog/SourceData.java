package com.thehiddenbrain.interop.extract.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel.Element;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel.Entity;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel.Lookup;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stand-in for the source database. Entity rows come from JSON files under {@code data/source}, typed
 * according to the catalog (dates become LocalDate, decimals BigDecimal), and are cached until the file changes.
 * When a real database arrives, this class is the seam to replace: the query planner only asks it for rows.
 */
@Service
public class SourceData {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final CatalogService catalog;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long modified, List<Map<String, Object>> rows) {}

    public SourceData(AppProperties props, ObjectMapper mapper, CatalogService catalog) {
        this.props = props;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    /** All rows of an entity, typed. The list is shared; callers must not mutate rows. */
    public List<Map<String, Object>> rows(String entityId) {
        Entity entity = catalog.entity(entityId);
        String typeEntity = entity.selfAliasOf() != null ? entity.selfAliasOf() : entityId;
        Map<String, String> types = new HashMap<>();
        for (Element e : catalog.elementsOf(typeEntity)) types.put(e.column(), e.type());
        if (entity.watermarkColumn() != null) types.putIfAbsent(entity.watermarkColumn(), "TIMESTAMP");
        return load(entity.source(), types);
    }

    /** All rows of a lookup table, typed loosely (strings stay strings, numbers become BigDecimal). */
    public List<Map<String, Object>> lookupRows(String lookupId) {
        Lookup l = catalog.lookup(lookupId);
        return load(l.source(), Map.of());
    }

    /** A snapshot id for a lookup: file size and modification time, so a run can record what it used. */
    public String lookupSnapshot(String lookupId) {
        Lookup l = catalog.lookup(lookupId);
        Path p = props.sourceDir().resolve(l.source());
        try {
            return Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    public boolean sourceExists(String source) {
        return Files.exists(props.sourceDir().resolve(source));
    }

    private List<Map<String, Object>> load(String source, Map<String, String> types) {
        Path p = props.sourceDir().resolve(source);
        long modified;
        try {
            modified = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Source file missing: " + p, e);
        }
        Cached c = cache.get(source);
        if (c != null && c.modified == modified) return c.rows;
        try {
            List<Map<String, Object>> raw = mapper.readValue(p.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> typed = new ArrayList<>(raw.size());
            for (Map<String, Object> row : raw) {
                Map<String, Object> t = new LinkedHashMap<>();
                for (Map.Entry<String, Object> en : row.entrySet()) t.put(en.getKey(), coerce(en.getValue(), types.get(en.getKey())));
                typed.add(Collections.unmodifiableMap(t));
            }
            List<Map<String, Object>> rows = Collections.unmodifiableList(typed);
            cache.put(source, new Cached(modified, rows));
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read source " + p, e);
        }
    }

    /** Type coercion for a raw JSON value based on the catalog type. Also the place where engine-specific quirks would be normalised. */
    public static Object coerce(Object v, String type) {
        if (v == null) return null;
        if (type == null) {
            if (v instanceof Integer || v instanceof Long) return BigDecimal.valueOf(((Number) v).longValue());
            if (v instanceof Double d) return BigDecimal.valueOf(d);
            return v;
        }
        try {
            switch (type) {
                case "DATE" -> {
                    String s = v.toString().trim();
                    if (s.isEmpty()) return null;
                    return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
                }
                case "TIMESTAMP" -> {
                    String s = v.toString().trim();
                    if (s.isEmpty()) return null;
                    return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
                }
                case "DECIMAL" -> {
                    if (v instanceof BigDecimal b) return b;
                    if (v instanceof Number n) return new BigDecimal(n.toString());
                    String s = v.toString().trim();
                    return s.isEmpty() ? null : new BigDecimal(s);
                }
                case "INTEGER" -> {
                    if (v instanceof Number n) return BigDecimal.valueOf(n.longValue());
                    String s = v.toString().trim();
                    return s.isEmpty() ? null : new BigDecimal(s);
                }
                case "BOOLEAN" -> {
                    if (v instanceof Boolean b) return b;
                    String s = v.toString().trim().toLowerCase();
                    return s.equals("true") || s.equals("y") || s.equals("1") || s.equals("yes");
                }
                default -> {
                    if (v instanceof String s) return s;
                    return v.toString();
                }
            }
        } catch (RuntimeException e) {
            return v;
        }
    }
}
