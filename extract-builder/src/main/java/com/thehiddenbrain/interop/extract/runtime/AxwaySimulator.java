package com.thehiddenbrain.interop.extract.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stands in for the Axway MFT poller so the demo shows the full path: a file appears in a partner's drop folder,
 * Axway picks it up a few seconds later, transfers it, and an acknowledgement comes back. Files move from
 * {@code out} to {@code sent} and an {@code .ack} record is written next to them.
 */
@Service
public class AxwaySimulator {

    private final AppProperties props;
    private final DemoSettings settings;
    private final RunService runs;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public AxwaySimulator(AppProperties props, DemoSettings settings, RunService runs, NotificationService notifications, ObjectMapper mapper) {
        this.props = props;
        this.settings = settings;
        this.runs = runs;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void poll() {
        if (!settings.axwaySimulator) return;
        Path mft = props.mftDir();
        if (!Files.isDirectory(mft)) return;
        try (Stream<Path> outs = Files.walk(mft, 4)) {
            List<Path> outDirs = outs.filter(p -> Files.isDirectory(p) && p.getFileName().toString().equals("out")).toList();
            for (Path out : outDirs) pickUp(out);
        } catch (IOException ignored) {
        }
    }

    private void pickUp(Path out) throws IOException {
        Instant cutoff = Instant.now().minusSeconds(settings.axwayPickupSeconds);
        try (Stream<Path> files = Files.list(out)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".tmp") || name.endsWith(".part") || name.endsWith(".done") || name.startsWith(".")) continue;
                FileTime mtime = Files.getLastModifiedTime(f);
                if (mtime.toInstant().isAfter(cutoff)) continue;
                Path sent = out.resolveSibling("sent");
                Files.createDirectories(sent);
                Path target = sent.resolve(name);
                Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                Path done = out.resolve(name + ".done");
                if (Files.exists(done)) Files.move(done, sent.resolve(name + ".done"), StandardCopyOption.REPLACE_EXISTING);
                long lines;
                try (Stream<String> s = Files.lines(target)) {
                    lines = s.count();
                }
                Map<String, Object> ack = new LinkedHashMap<>();
                ack.put("file", name);
                ack.put("receivedAt", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
                ack.put("bytes", Files.size(target));
                ack.put("lines", lines);
                ack.put("transport", "SFTP");
                ack.put("status", "TRANSFERRED");
                Path ackPath = sent.resolve(name + ".ack");
                mapper.writerWithDefaultPrettyPrinter().writeValue(ackPath.toFile(), ack);
                runs.findByDeliveryPath(f.toString()).ifPresent(run -> {
                    int records = run.rowCount == null ? (int) lines : run.rowCount;
                    runs.markTransferred(run, ackPath.toString(), records);
                    if ("PRODUCTION".equals(run.mode)) {
                        notifications.notify("TRANSFERRED", "INFO", run.definitionName + " transferred to " + run.vendorCode, name + " left Axway; the vendor acknowledged " + records + " records.", run.definitionId, run.id, List.of());
                    }
                });
            }
        }
    }
}
