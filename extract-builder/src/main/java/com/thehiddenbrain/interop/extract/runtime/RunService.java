package com.thehiddenbrain.interop.extract.runtime;

import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.config.ApiErrors;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.ProductionConfig;
import com.thehiddenbrain.interop.extract.engine.Runner;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

/**
 * Orchestrates runs around the engine: sample runs and previews for analysts, production runs for the scheduler,
 * quality gates that hold a file, delivery, watermark commits, retries and re-deliveries. Every decision here is
 * recorded on the run and in the audit log.
 */
@Service
public class RunService {

    private final StateStores stores;
    private final Runner runner;
    private final PartnerService partners;
    private final Deliverer deliverer;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AppProperties props;
    private final CatalogService catalog;

    public RunService(StateStores stores, Runner runner, PartnerService partners, Deliverer deliverer, AuditService audit,
                      NotificationService notifications, AppProperties props, CatalogService catalog) {
        this.stores = stores;
        this.runner = runner;
        this.partners = partners;
        this.deliverer = deliverer;
        this.audit = audit;
        this.notifications = notifications;
        this.props = props;
        this.catalog = catalog;
    }

    // ---------- queries ----------

    public Run get(String id) { return stores.runs().get(id, "Run"); }

    public List<Run> all() {
        List<Run> all = new ArrayList<>(stores.runs().all());
        all.sort(Comparator.comparing((Run r) -> r.startedAt == null ? "" : r.startedAt).reversed());
        return all;
    }

    public List<Run> forDefinition(String definitionId) {
        return all().stream().filter(r -> definitionId.equals(r.definitionId)).toList();
    }

    public Optional<Run> lastDelivered(String definitionId) {
        return forDefinition(definitionId).stream().filter(r -> "PRODUCTION".equals(r.mode) && (r.status.equals("DELIVERED") || r.status.equals("TRANSFERRED"))).findFirst();
    }

    public Optional<Run> lastSample(Definition def, int versionNo) {
        return forDefinition(def.id).stream().filter(r -> "SAMPLE".equals(r.mode) && r.versionNo == versionNo && !"DELETED".equals(r.status)).findFirst();
    }

    // ---------- sample & preview ----------

    public static class SampleOptions {
        public boolean unmasked;
        public boolean synthetic;
        public List<String> cohort = List.of();
        public Integer maxRows;
    }

    public Run preview(Definition def, Definition.Version v, Actor actor, int rows) {
        Run run = newRun(def, v, "PREVIEW", "MANUAL", actor);
        Runner.Options o = new Runner.Options();
        o.mode = "PREVIEW";
        o.masking = true;
        o.rowLimit = rows;
        o.cohort = v.spec.sample == null ? List.of() : v.spec.sample.cohort;
        o.synthetic = v.spec.sample != null && v.spec.sample.synthetic;
        runner.execute(run, def, v, o);
        audit.record(actor, "PREVIEWED", "version", def.id + "/v" + v.versionNo, def.id, def.vendorCode, true, Map.of("rows", run.rowCount == null ? 0 : run.rowCount, "masked", true));
        return run;
    }

    public Run sample(Definition def, Definition.Version v, Actor actor, SampleOptions so) {
        Partner partner = partners.find(def.vendorCode).orElse(null);
        boolean canUnmask = actor.phiUnmasked() && partner != null && partner.baaOnFile && partner.allowUnmaskedSamples;
        boolean masked = !(so.unmasked && canUnmask);
        Run run = newRun(def, v, "SAMPLE", "MANUAL", actor);
        if (so.unmasked && !canUnmask) {
            run.heldReason = actor.phiUnmasked() ? "Unmasked samples need a BAA on file and the partner flag 'allow unmasked samples'." : "Your account does not hold the PHI unmasked privilege.";
        }
        Runner.Options o = new Runner.Options();
        o.mode = "SAMPLE";
        o.masking = masked;
        o.rowLimit = so.maxRows != null ? so.maxRows : (v.spec.sample == null ? 200 : v.spec.sample.maxRows);
        o.cohort = !so.cohort.isEmpty() ? so.cohort : (v.spec.sample == null ? List.of() : v.spec.sample.cohort);
        o.synthetic = so.synthetic || (v.spec.sample != null && v.spec.sample.synthetic);
        o.targetDir = props.samplesDir().resolve(def.id).resolve(run.id);
        o.fileSeq = 1;
        runner.execute(run, def, v, o);
        stores.runs().save(run);
        audit.record(actor, "SAMPLE_RUN", "run", run.id, def.id, def.vendorCode, !masked,
                Map.of("rows", run.rowCount == null ? 0 : run.rowCount, "masked", masked, "synthetic", o.synthetic, "status", run.status, "version", v.versionNo));
        return run;
    }

    public Path download(String runId, Actor actor) {
        Run run = get(runId);
        if (run.filePath == null || !Files.exists(Path.of(run.filePath))) throw new ApiErrors.NotFound("The file for run " + runId + " is no longer available");
        if ("PRODUCTION".equals(run.mode)) actor.require("download production files", "ADMIN", "APPROVER");
        audit.record(actor, "SAMPLE".equals(run.mode) ? "SAMPLE_DOWNLOADED" : "FILE_DOWNLOADED", "run", run.id, run.definitionId, run.vendorCode, !run.masked,
                Map.of("file", run.fileName, "rows", run.rowCount == null ? 0 : run.rowCount, "masked", run.masked));
        return Path.of(run.filePath);
    }

    public Run sendSampleToTestRoute(String runId, Actor actor) {
        Run run = get(runId);
        if (!"SAMPLE".equals(run.mode)) throw new ApiErrors.BadRequest("Only sample runs go to the test route");
        Partner partner = partners.get(run.vendorCode);
        deliverer.deliver(run, partner, "TEST", true);
        run.status = "DELIVERED";
        stores.runs().save(run);
        audit.record(actor, "SAMPLE_SENT_TEST_ROUTE", "run", run.id, run.definitionId, run.vendorCode, !run.masked, Map.of("file", run.fileName, "folder", run.deliveryPath));
        notifications.notify("DELIVERED", "INFO", "Sample sent to " + partner.name + " test route", run.fileName + " is in the Axway test drop folder.", run.definitionId, run.id, List.of(partner.contactEmail == null ? "" : partner.contactEmail));
        return run;
    }

    public void deleteSamples(Definition def, int versionNo, Actor actor, String reason) {
        for (Run r : forDefinition(def.id)) {
            if (!"SAMPLE".equals(r.mode) || r.versionNo != versionNo || "DELETED".equals(r.status)) continue;
            deleteFile(r.filePath);
            r.status = "DELETED";
            r.error = reason;
            stores.runs().save(r);
        }
        audit.record(actor, "SAMPLES_DELETED", "version", def.id + "/v" + versionNo, def.id, def.vendorCode, false, Map.of("reason", reason));
    }

    // ---------- production ----------

    public static class ProductionOptions {
        public String trigger = "MANUAL";
        public LocalDate businessDate = LocalDate.now();
        public boolean commitWatermark = true;
        public String retryOfRunId;
        public LocalDateTime windowFrom;
        public LocalDateTime windowTo;
    }

    public Run production(Definition def, Definition.Version v, Actor actor, ProductionOptions po) {
        Partner partner = partners.find(def.vendorCode).orElseThrow(() -> new ApiErrors.BadRequest("No MFT partner registered for " + def.vendorCode));
        ProductionConfig cfg = v.productionConfig == null ? new ProductionConfig() : v.productionConfig;
        Run run = newRun(def, v, "PRODUCTION", po.trigger, actor);
        run.configSnapshot = cfg;
        run.retryOfRunId = po.retryOfRunId;
        run.route = cfg.delivery.route;
        if (!partners.contractActive(partner, po.businessDate)) {
            run.status = "SKIPPED_CONTRACT";
            run.error = "Contract with " + partner.name + " ended " + partner.contractEnd + "; nothing was sent.";
            run.finishedAt = Runner.now();
            run.businessDate = po.businessDate.toString();
            stores.runs().save(run);
            notifications.notify("FAILED", "WARN", "Run skipped: contract ended", run.error, def.id, run.id, cfg.notifications.onFailure);
            return run;
        }
        Runner.Options o = new Runner.Options();
        o.mode = "PRODUCTION";
        o.masking = false;
        o.runDate = po.businessDate;
        o.businessDate = po.businessDate;
        o.targetDir = props.stagingDir().resolve(run.id);
        o.fileNamePattern = cfg.fileName.pattern;
        o.dateSource = cfg.fileName.dateSource;
        o.fileSeq = (int) forDefinition(def.id).stream().filter(r -> "PRODUCTION".equals(r.mode) && po.businessDate.toString().equals(r.businessDate)).count() + 1;
        boolean incremental = v.spec.scope != null && "INCREMENTAL".equals(v.spec.scope.mode);
        if (incremental) {
            if (po.windowFrom != null) {
                o.windowFrom = po.windowFrom;
                o.windowTo = po.windowTo;
            } else {
                Optional<Watermark> wm = stores.watermarks().find(def.id);
                LocalDateTime from = wm.map(w -> LocalDateTime.parse(w.value)).orElseGet(() -> initialWatermark(v, cfg));
                LocalDateTime to = LocalDateTime.now().minusMinutes(v.spec.scope.lagMinutes).truncatedTo(ChronoUnit.SECONDS);
                if (!to.isAfter(from)) to = from.plusSeconds(1);
                o.windowFrom = from;
                o.windowTo = to;
            }
        }
        CatalogModel.Entity grain = catalog.entity(def.grain);
        run.gate = grain.readinessCheck() == null ? "No readiness gate declared for " + grain.name() : grain.readinessCheck() + ": passed at " + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toLocalTime();

        runner.execute(run, def, v, o);
        if ("FAILED".equals(run.status)) {
            stores.runs().save(run);
            notifications.notify("FAILED", "CRITICAL", def.name + " failed", run.error, def.id, run.id, cfg.notifications.onFailure);
            audit.record(actor, "RUN_FAILED", "run", run.id, def.id, def.vendorCode, false, Map.of("error", run.error));
            return run;
        }
        if (run.rowCount != null && run.rowCount == 0) {
            switch (cfg.delivery.zeroRowPolicy) {
                case "SKIP" -> {
                    run.status = "SKIPPED_EMPTY";
                    run.error = "No rows in the window; the zero-row policy is skip.";
                    if (incremental && po.commitWatermark) commitWatermark(def, v, run);
                    stores.runs().save(run);
                    return run;
                }
                case "FAIL" -> {
                    run.status = "FAILED";
                    run.error = "No rows in the window and the zero-row policy is fail.";
                    stores.runs().save(run);
                    notifications.notify("FAILED", "CRITICAL", def.name + " produced no rows", run.error, def.id, run.id, cfg.notifications.onFailure);
                    return run;
                }
                default -> { }
            }
        }
        run.qualityFindings = qualityGate(def, run, cfg.quality);
        boolean hold = cfg.quality.enabled && cfg.quality.holdOnBreach && run.qualityFindings.stream().anyMatch(f -> "HOLD".equals(f.severity));
        if (hold) {
            run.status = "HELD";
            run.heldReason = String.join(" ", run.qualityFindings.stream().filter(f -> "HOLD".equals(f.severity)).map(f -> f.message).toList());
            stores.runs().save(run);
            notifications.notify("HELD", "WARN", def.name + " held before delivery", run.heldReason, def.id, run.id, cfg.notifications.onHold.isEmpty() ? cfg.notifications.onFailure : cfg.notifications.onHold);
            audit.record(actor, "RUN_HELD", "run", run.id, def.id, def.vendorCode, false, Map.of("reason", run.heldReason));
            return run;
        }
        deliverAndCommit(def, v, run, partner, cfg, actor, incremental && po.commitWatermark);
        return run;
    }

    private LocalDateTime initialWatermark(Definition.Version v, ProductionConfig cfg) {
        String s = cfg.initialWatermark != null && !cfg.initialWatermark.isBlank() ? cfg.initialWatermark : v.spec.scope.initialWatermark;
        if (s != null && !s.isBlank()) {
            try {
                return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
            } catch (RuntimeException ignored) {
            }
        }
        return LocalDate.now().minusDays(30).atStartOfDay();
    }

    private void deliverAndCommit(Definition def, Definition.Version v, Run run, Partner partner, ProductionConfig cfg, Actor actor, boolean commit) {
        try {
            deliverer.deliver(run, partner, cfg.delivery.route, cfg.delivery.controlFile);
            run.status = "DELIVERED";
            if (commit) commitWatermark(def, v, run);
            stores.runs().save(run);
            audit.record(actor, "RUN_DELIVERED", "run", run.id, def.id, def.vendorCode, false, Map.of("file", run.fileName, "rows", run.rowCount == null ? 0 : run.rowCount, "sha256", run.sha256 == null ? "" : run.sha256));
            if (!cfg.notifications.onDelivered.isEmpty()) {
                notifications.notify("DELIVERED", "INFO", def.name + " delivered", run.fileName + " with " + run.rowCount + " rows is in the " + cfg.delivery.route.toLowerCase() + " drop folder.", def.id, run.id, cfg.notifications.onDelivered);
            }
        } catch (RuntimeException e) {
            run.status = "FAILED";
            run.error = e.getMessage();
            stores.runs().save(run);
            notifications.notify("FAILED", "CRITICAL", def.name + " delivery failed", run.error, def.id, run.id, cfg.notifications.onFailure);
        }
    }

    private void commitWatermark(Definition def, Definition.Version v, Run run) {
        if (run.windowTo == null) return;
        Watermark w = stores.watermarks().find(def.id).orElseGet(Watermark::new);
        w.definitionId = def.id;
        w.value = run.windowTo;
        w.elements = v.spec.scope.watermarkElements;
        w.lastRunId = run.id;
        w.updatedAt = Runner.now();
        stores.watermarks().save(w);
    }

    private List<Run.Finding> qualityGate(Definition def, Run run, ProductionConfig.Quality q) {
        List<Run.Finding> out = new ArrayList<>();
        if (q == null || !q.enabled) return out;
        Optional<Run> prev = lastDelivered(def.id);
        if (prev.isPresent() && prev.get().rowCount != null && prev.get().rowCount > 0 && run.rowCount != null) {
            double pct = 100.0 * Math.abs(run.rowCount - prev.get().rowCount) / prev.get().rowCount;
            if (pct > q.rowCountVariancePct) {
                out.add(new Run.Finding("ROW_COUNT_VARIANCE", "HOLD", String.format("Row count moved %.0f%% from the last delivered run (%d to %d); the limit is %d%%.", pct, prev.get().rowCount, run.rowCount, q.rowCountVariancePct), run.rowCount, prev.get().rowCount));
            } else {
                out.add(new Run.Finding("ROW_COUNT_VARIANCE", "PASS", String.format("Row count within %d%% of the last delivered run (%d vs %d).", q.rowCountVariancePct, run.rowCount, prev.get().rowCount), run.rowCount, prev.get().rowCount));
            }
        } else {
            out.add(new Run.Finding("ROW_COUNT_VARIANCE", "PASS", "First delivered run; no baseline to compare with yet.", run.rowCount, null));
        }
        for (Run.FieldStat st : run.fieldStats.values()) {
            if (st.rows > 0 && st.nullRatePct > q.nullRateMaxPct) {
                out.add(new Run.Finding("NULL_RATE", "HOLD", st.header + " is empty on " + st.nullRatePct + "% of rows; the limit is " + q.nullRateMaxPct + "%.", st.nullRatePct, q.nullRateMaxPct));
            }
        }
        int misses = run.warnings.stream().filter(w -> "LOOKUP_MISS".equals(w.code)).mapToInt(w -> w.count).sum();
        if (misses > q.lookupMissMax) {
            out.add(new Run.Finding("LOOKUP_MISSES", "HOLD", misses + " lookup misses; the limit is " + q.lookupMissMax + ".", misses, q.lookupMissMax));
        } else {
            out.add(new Run.Finding("LOOKUP_MISSES", "PASS", misses == 0 ? "No lookup misses." : misses + " lookup misses, within the limit of " + q.lookupMissMax + ".", misses, q.lookupMissMax));
        }
        int truncated = run.fieldStats.values().stream().mapToInt(s -> s.truncated).sum();
        if (truncated > 0) out.add(new Run.Finding("TRUNCATION", "WARN", truncated + " values were cut to their field's maximum length.", truncated, 0));
        return out;
    }

    public Run release(String runId, Actor actor, Definition def, Definition.Version v) {
        actor.require("release a held file", "APPROVER", "ADMIN");
        Run run = get(runId);
        if (!"HELD".equals(run.status)) throw new ApiErrors.BadRequest("Run " + runId + " is not held");
        Partner partner = partners.get(def.vendorCode);
        run.releasedBy = actor.name();
        run.trigger = "RELEASE";
        boolean incremental = v.spec.scope != null && "INCREMENTAL".equals(v.spec.scope.mode);
        audit.record(actor, "RUN_RELEASED", "run", run.id, def.id, def.vendorCode, false, Map.of("reason", run.heldReason == null ? "" : run.heldReason));
        deliverAndCommit(def, v, run, partner, run.configSnapshot == null ? new ProductionConfig() : run.configSnapshot, actor, incremental);
        return run;
    }

    public Run retry(String runId, Actor actor, Definition def, Definition.Version v) {
        Run failed = get(runId);
        if (!"FAILED".equals(failed.status) && !"SKIPPED_CONTRACT".equals(failed.status)) throw new ApiErrors.BadRequest("Only failed runs can be retried");
        ProductionOptions po = new ProductionOptions();
        po.trigger = "RETRY";
        po.businessDate = failed.businessDate == null ? LocalDate.now() : LocalDate.parse(failed.businessDate);
        po.retryOfRunId = failed.id;
        if (failed.windowFrom != null) {
            po.windowFrom = LocalDateTime.parse(failed.windowFrom);
            po.windowTo = LocalDateTime.parse(failed.windowTo);
        }
        audit.record(actor, "RUN_RETRIED", "run", failed.id, def.id, def.vendorCode, false, Map.of());
        return production(def, v, actor, po);
    }

    public Run redeliver(String runId, Actor actor, Definition def) {
        actor.require("re-deliver a file", "APPROVER", "ADMIN", "ANALYST");
        Run run = get(runId);
        if (run.filePath == null || !Files.exists(Path.of(run.filePath))) throw new ApiErrors.BadRequest("The file for this run was purged by retention; re-run the window instead");
        Partner partner = partners.get(def.vendorCode);
        String route = run.route == null ? "PROD" : run.route;
        deliverer.deliver(run, partner, route, run.configSnapshot == null || run.configSnapshot.delivery.controlFile);
        run.status = "DELIVERED";
        run.transferredAt = null;
        run.ackPath = null;
        stores.runs().save(run);
        audit.record(actor, "RUN_REDELIVERED", "run", run.id, def.id, def.vendorCode, false, Map.of("file", run.fileName, "attempt", run.attempts.size()));
        return run;
    }

    public Run rerunAsOf(Definition def, Definition.Version v, Actor actor, LocalDate businessDate, boolean commit) {
        ProductionOptions po = new ProductionOptions();
        po.trigger = "RERUN";
        po.businessDate = businessDate;
        po.commitWatermark = commit;
        if (v.spec.scope != null && "INCREMENTAL".equals(v.spec.scope.mode)) {
            po.windowFrom = businessDate.minusDays(1).atStartOfDay();
            po.windowTo = businessDate.atTime(23, 59, 59);
        }
        audit.record(actor, "RUN_RERUN_AS_OF", "definition", def.id, def.id, def.vendorCode, false, Map.of("businessDate", businessDate.toString(), "commitWatermark", commit));
        return production(def, v, actor, po);
    }

    public void markTransferred(Run run, String ackPath, int received) {
        run.status = "TRANSFERRED";
        run.transferredAt = Runner.now();
        run.ackPath = ackPath;
        run.vendorReceivedCount = received;
        stores.runs().save(run);
    }

    public Optional<Run> findByDeliveryPath(String path) {
        return stores.runs().all().stream().filter(r -> path.equals(r.deliveryPath) && "DELIVERED".equals(r.status)).findFirst();
    }

    public void save(Run run) { stores.runs().save(run); }

    public Optional<Watermark> watermark(String definitionId) { return stores.watermarks().find(definitionId); }

    public void purgeExpiredSamples(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        for (Run r : stores.runs().all()) {
            if ("SAMPLE".equals(r.mode) && r.filePath != null && r.startedAt != null && LocalDateTime.parse(r.startedAt).isBefore(cutoff)) {
                deleteFile(r.filePath);
                r.archived = true;
                r.filePath = null;
                stores.runs().save(r);
            }
        }
    }

    private Run newRun(Definition def, Definition.Version v, String mode, String trigger, Actor actor) {
        Run run = new Run();
        run.id = Ids.shortId("r");
        run.definitionId = def.id;
        run.definitionName = def.name;
        run.vendorCode = def.vendorCode;
        run.versionNo = v.versionNo;
        run.mode = mode;
        run.trigger = trigger;
        run.status = "QUEUED";
        run.startedBy = actor.name();
        return run;
    }

    private static void deleteFile(String path) {
        if (path == null) return;
        try {
            Path p = Path.of(path);
            Files.deleteIfExists(p);
            Path dir = p.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(dir);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
