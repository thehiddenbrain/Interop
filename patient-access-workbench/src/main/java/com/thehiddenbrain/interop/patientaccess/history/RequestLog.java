package com.thehiddenbrain.interop.patientaccess.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Every outbound request (FHIR, discovery, token) ends up here: a bounded in-memory ring for the UI
 * and, when {@code paw.history.persist} is on, an append-only JSONL file per day under
 * {@code <data-dir>/history/}. Records arrive already redacted.
 */
@Component
public class RequestLog {

    private static final Logger log = LoggerFactory.getLogger(RequestLog.class);
    private static final ObjectMapper MAPPER = JsonFile.MAPPER;

    private final int maxEntries;
    private final boolean persist;
    private final Path folder;
    private final Clock clock;
    private final Deque<RequestRecord> ring = new ArrayDeque<>();
    private BufferedWriter writer;
    private LocalDate writerDay;

    public RequestLog(WorkbenchProperties properties, Clock clock) {
        this(properties.dataDirPath().resolve("history"), properties.history().maxEntries(), properties.history().persist(), clock);
    }

    public RequestLog(Path folder, int maxEntries, boolean persist, Clock clock) {
        this.folder = folder;
        this.maxEntries = Math.max(10, maxEntries);
        this.persist = persist;
        this.clock = clock;
        if (persist) {
            loadRecent();
        }
    }

    public Path folder() {
        return folder;
    }

    public synchronized void record(RequestRecord record) {
        ring.addFirst(record);
        while (ring.size() > maxEntries) {
            ring.removeLast();
        }
        if (persist) {
            append(record);
        }
    }

    public synchronized List<RequestRecord.Summary> list(String environmentId, String purpose, String correlationId, int limit) {
        List<RequestRecord.Summary> out = new ArrayList<>();
        for (RequestRecord r : ring) {
            if (environmentId != null && !environmentId.equals(r.environmentId())) {
                continue;
            }
            if (purpose != null && !purpose.equals(r.purpose())) {
                continue;
            }
            if (correlationId != null && !correlationId.equals(r.correlationId())) {
                continue;
            }
            out.add(r.summaryView());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public synchronized Optional<RequestRecord> get(String id) {
        return ring.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public synchronized List<RequestRecord> byCorrelation(String correlationId) {
        return ring.stream().filter(r -> correlationId.equals(r.correlationId())).collect(Collectors.toList());
    }

    public synchronized int size() {
        return ring.size();
    }

    public synchronized void clear() {
        ring.clear();
    }

    private void append(RequestRecord record) {
        try {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (writer == null || !today.equals(writerDay)) {
                if (writer != null) {
                    writer.close();
                }
                Files.createDirectories(folder);
                writer = Files.newBufferedWriter(folder.resolve("requests-" + today + ".jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDay = today;
            }
            writer.write(MAPPER.writeValueAsString(record));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.warn("cannot append to request history in {}: {}", folder, e.getMessage());
        }
    }

    /** Loads the newest entries of the current and previous day so the History tab survives a restart. */
    private void loadRecent() {
        if (!Files.isDirectory(folder)) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<RequestRecord> loaded = new ArrayList<>();
        for (LocalDate day : List.of(today.minusDays(1), today)) {
            Path file = folder.resolve("requests-" + day + ".jsonl");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        loaded.add(MAPPER.readValue(line, RequestRecord.class));
                    } catch (IOException bad) {
                        log.debug("skipping unreadable history line in {}", file);
                    }
                }
            } catch (IOException e) {
                log.warn("cannot read history file {}: {}", file, e.getMessage());
            }
        }
        Iterator<RequestRecord> it = loaded.listIterator(Math.max(0, loaded.size() - maxEntries));
        while (it.hasNext()) {
            ring.addFirst(it.next());
        }
        if (!ring.isEmpty()) {
            log.info("loaded {} request history entries from {}", ring.size(), folder);
        }
    }

    public static WorkbenchException notFound(String id) {
        return WorkbenchException.notFound("history entry", id);
    }
}
