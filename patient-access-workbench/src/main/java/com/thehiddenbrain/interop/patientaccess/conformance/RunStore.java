package com.thehiddenbrain.interop.patientaccess.conformance;

import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Conformance runs live in memory while running and as {@code <data-dir>/conformance/<runId>.json} afterwards. */
@Component
public class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    private final Path folder;
    private final Map<String, ConformanceRun> runs = new ConcurrentHashMap<>();

    @Autowired
    public RunStore(WorkbenchProperties properties) {
        this(properties.dataDirPath().resolve("conformance"));
    }

    public RunStore(Path folder) {
        this.folder = folder;
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                    try {
                        new JsonFile(p).read(ConformanceRun.class).ifPresent(r -> runs.put(r.id(), r));
                    } catch (WorkbenchException e) {
                        log.warn("skipping unreadable conformance run {}: {}", p, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("cannot list {}: {}", folder, e.getMessage());
            }
            log.info("loaded {} conformance run(s) from {}", runs.size(), folder);
        }
    }

    public void save(ConformanceRun run) {
        runs.put(run.id(), run);
        if (!ConformanceRun.RUNNING.equals(run.status())) {
            new JsonFile(folder.resolve(run.id() + ".json")).write(run);
        }
    }

    public Optional<ConformanceRun> find(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public List<ConformanceRun.Summary> list(String environmentId) {
        List<ConformanceRun.Summary> out = new ArrayList<>();
        for (ConformanceRun r : runs.values()) {
            if (environmentId == null || environmentId.equals(r.environmentId())) {
                out.add(r.summary());
            }
        }
        out.sort(Comparator.comparing(ConformanceRun.Summary::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    public void delete(String id) {
        if (runs.remove(id) == null) {
            throw WorkbenchException.notFound("conformance run", id);
        }
        new JsonFile(folder.resolve(id + ".json")).delete();
    }
}
