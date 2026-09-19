package com.thehiddenbrain.interop.extract.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A small keyed collection persisted as one JSON file. The application has no database by design: every
 * mutable record (definitions, runs, partners, audit events) lives in one of these under {@code data/state}.
 * Writes are atomic (write to a temp file, then move) so a crash never leaves a half-written file.
 */
public class JsonStore<T> {

    private final Path file;
    private final ObjectMapper mapper;
    private final TypeReference<List<T>> listType;
    private final Function<T, String> idOf;
    private final Map<String, T> items = new LinkedHashMap<>();

    public JsonStore(Path file, ObjectMapper mapper, TypeReference<List<T>> listType, Function<T, String> idOf) {
        this.file = file;
        this.mapper = mapper;
        this.listType = listType;
        this.idOf = idOf;
        load();
    }

    public synchronized void load() {
        items.clear();
        if (Files.exists(file)) {
            try {
                List<T> list = mapper.readValue(file.toFile(), listType);
                for (T t : list) items.put(idOf.apply(t), t);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
        }
    }

    public synchronized List<T> all() {
        return new ArrayList<>(items.values());
    }

    public synchronized List<T> where(Predicate<T> p) {
        return items.values().stream().filter(p).toList();
    }

    public synchronized Optional<T> find(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public synchronized T get(String id, String what) {
        T t = items.get(id);
        if (t == null) throw new com.thehiddenbrain.interop.extract.config.ApiErrors.NotFound(what + " not found: " + id);
        return t;
    }

    public synchronized T save(T t) {
        items.put(idOf.apply(t), t);
        flush();
        return t;
    }

    public synchronized T update(String id, String what, UnaryOperator<T> change) {
        T t = get(id, what);
        T changed = change.apply(t);
        items.put(id, changed);
        flush();
        return changed;
    }

    public synchronized boolean delete(String id) {
        boolean removed = items.remove(id) != null;
        if (removed) flush();
        return removed;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void replaceAll(List<T> list) {
        items.clear();
        for (T t : list) items.put(idOf.apply(t), t);
        flush();
    }

    private void flush() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(items.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
