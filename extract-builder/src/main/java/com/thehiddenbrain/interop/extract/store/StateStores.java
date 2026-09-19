package com.thehiddenbrain.interop.extract.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.extract.audit.AuditEvent;
import com.thehiddenbrain.interop.extract.audit.Notification;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.ScheduleState;
import com.thehiddenbrain.interop.extract.runtime.Watermark;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * All mutable state, one JSON file each under {@code data/state}. On first start the seed files under
 * {@code data/seed} are copied in; "Reset demo data" wipes the state and copies them again.
 */
@Service
public class StateStores {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private JsonStore<Definition> definitions;
    private JsonStore<Run> runs;
    private JsonStore<Partner> partners;
    private JsonStore<AuditEvent> audit;
    private JsonStore<Notification> notifications;
    private JsonStore<Watermark> watermarks;
    private JsonStore<ScheduleState> schedules;
    private boolean freshlySeeded;

    public StateStores(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        open();
    }

    private void open() {
        Path state = props.stateDir();
        try {
            Files.createDirectories(state);
            if (!Files.exists(state.resolve("definitions.json"))) {
                copySeed("definitions.json");
                copySeed("partners.json");
                freshlySeeded = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        definitions = new JsonStore<>(state.resolve("definitions.json"), mapper, new TypeReference<>() {}, d -> d.id);
        runs = new JsonStore<>(state.resolve("runs.json"), mapper, new TypeReference<>() {}, r -> r.id);
        partners = new JsonStore<>(state.resolve("partners.json"), mapper, new TypeReference<>() {}, p -> p.code);
        audit = new JsonStore<>(state.resolve("audit.json"), mapper, new TypeReference<>() {}, a -> a.id);
        notifications = new JsonStore<>(state.resolve("notifications.json"), mapper, new TypeReference<>() {}, n -> n.id);
        watermarks = new JsonStore<>(state.resolve("watermarks.json"), mapper, new TypeReference<>() {}, w -> w.definitionId);
        schedules = new JsonStore<>(state.resolve("schedules.json"), mapper, new TypeReference<>() {}, s -> s.definitionId);
    }

    private void copySeed(String name) throws IOException {
        Path from = props.seedDir().resolve(name);
        if (Files.exists(from)) Files.copy(from, props.stateDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Wipe every run-time file and re-seed. Returns true when the seed was applied. */
    public synchronized boolean reset() {
        for (Path dir : new Path[]{props.stateDir(), props.samplesDir(), props.stagingDir(), props.mftDir()}) deleteTree(dir);
        freshlySeeded = false;
        open();
        return freshlySeeded;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean freshlySeeded() { return freshlySeeded; }
    public void markSeeded() { freshlySeeded = false; }

    public JsonStore<Definition> definitions() { return definitions; }
    public JsonStore<Run> runs() { return runs; }
    public JsonStore<Partner> partners() { return partners; }
    public JsonStore<AuditEvent> audit() { return audit; }
    public JsonStore<Notification> notifications() { return notifications; }
    public JsonStore<Watermark> watermarks() { return watermarks; }
    public JsonStore<ScheduleState> schedules() { return schedules; }
}
