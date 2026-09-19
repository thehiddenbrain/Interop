package com.thehiddenbrain.interop.extract.seed;

import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.definition.VersionStatus;
import com.thehiddenbrain.interop.extract.runtime.*;
import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import com.thehiddenbrain.interop.extract.users.Users;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * On first start (or after a reset) the seed definitions and partners are already copied in; this class makes the
 * demo look alive: it recomputes spec hashes, builds real samples for the versions that should have one, runs each
 * production feed once so real files exist, and back-fills a month of run history relative to today.
 */
@Service
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final StateStores stores;
    private final DefinitionService definitions;
    private final RunService runs;
    private final Scheduler scheduler;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CatalogService catalog;
    private final Users users;
    private final DemoSettings settings;

    public DemoSeeder(StateStores stores, DefinitionService definitions, RunService runs, Scheduler scheduler, AuditService audit,
                      NotificationService notifications, CatalogService catalog, Users users, DemoSettings settings) {
        this.stores = stores;
        this.definitions = definitions;
        this.runs = runs;
        this.scheduler = scheduler;
        this.audit = audit;
        this.notifications = notifications;
        this.catalog = catalog;
        this.users = users;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (stores.freshlySeeded()) seed(false);
    }

    public synchronized void seed(boolean afterReset) {
        long t0 = System.currentTimeMillis();
        boolean simulator = settings.axwaySimulator;
        settings.axwaySimulator = false;
        try {
            Actor analyst = users.get("analyst");
            Actor analyst2 = users.get("analyst2");
            Actor approver = users.get("approver");
            Actor admin = users.get("admin");
            for (Definition def : stores.definitions().all()) {
                for (Definition.Version v : def.versions) {
                    v.specHash = definitions.specHash(v.spec);
                    v.catalogChecksum = catalog.checksum();
                    if (v.productionConfig != null) v.productionConfig.schedule.cron = Scheduler.cronFor(v.productionConfig.schedule);
                }
                stores.definitions().save(def);
            }
            // real samples for the open versions that the story says were sampled
            sampleAndAdvance("d-acme-elig", 3, analyst, VersionStatus.SAMPLED, null);
            sampleAndAdvance("d-bluebird-roster", 2, analyst, VersionStatus.PENDING_APPROVAL, "Outreach program needs language and age; vendor confirmed the two new trailing columns.");
            // one real production run per live feed, so files exist in the drop folders; then a month of history scaled to the real counts
            Map<String, Integer> counts = new HashMap<>();
            for (String id : List.of("d-acme-elig", "d-harbor-claims", "d-bluebird-roster")) {
                Definition def = stores.definitions().get(id, "Definition");
                Definition.Version v = def.production().orElseThrow();
                RunService.ProductionOptions po = new RunService.ProductionOptions();
                po.trigger = "SCHEDULE";
                po.businessDate = LocalDate.now();
                Run r = runs.production(def, v, Actor.SYSTEM, po);
                log.info("Seed run {} for {}: {} ({} rows)", r.id, def.name, r.status, r.rowCount);
                counts.put(id, r.rowCount == null ? 100 : Math.max(10, r.rowCount));
                scheduler.register(def, v);
            }
            backfillHistory(counts);
            seedAudit(analyst, analyst2, approver, admin);
            if (afterReset) notifications.notify("INFO", "INFO", "Demo data reset", "Definitions, partners, runs and audit were restored to the seed.", null, null, List.of());
            stores.markSeeded();
            log.info("Demo seed complete in {} ms", System.currentTimeMillis() - t0);
        } finally {
            settings.axwaySimulator = simulator;
        }
    }

    private void sampleAndAdvance(String defId, int versionNo, Actor actor, VersionStatus target, String approvalNote) {
        Definition def = stores.definitions().get(defId, "Definition");
        Definition.Version v = definitions.version(def, versionNo);
        Run sample = definitions.sample(defId, versionNo, actor, new RunService.SampleOptions());
        log.info("Seed sample {} for {} v{}: {} ({} rows)", sample.id, def.name, versionNo, sample.status, sample.rowCount);
        if (target == VersionStatus.PENDING_APPROVAL && "WRITTEN".equals(sample.status)) {
            try {
                definitions.requestApproval(defId, versionNo, actor, approvalNote, "Email from feeds@bluebirdrx.example, 09/17");
            } catch (RuntimeException e) {
                log.warn("Could not move {} v{} to pending approval: {}", def.name, versionNo, e.getMessage());
            }
        }
    }

    /** Synthetic but plausible history for the live feeds, dated relative to today. Files for these runs are marked as purged. */
    private void backfillHistory(Map<String, Integer> counts) {
        Random rnd = new Random(42);
        LocalDate today = LocalDate.now();
        List<Run> history = new ArrayList<>();
        int acme = counts.getOrDefault("d-acme-elig", 100), bb = counts.getOrDefault("d-bluebird-roster", 300), hb = counts.getOrDefault("d-harbor-claims", 20);
        history.addAll(series("d-acme-elig", "Acme Dental eligibility", "ACMEDENTAL", 2, today, 30, d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY, "02:01", acme, Math.max(2, acme / 8), "ACMEDENTAL_ELIG_", ".txt", rnd, Map.of(3, "FAILED")));
        history.addAll(series("d-bluebird-roster", "Bluebird PBM member roster", "BLUEBIRD", 1, today, 30, d -> d.getDayOfWeek() == DayOfWeek.MONDAY, "05:31", bb, Math.max(2, bb / 20), "BLUEBIRD_ROSTER_", ".csv", rnd, Map.of()));
        history.addAll(series("d-harbor-claims", "Harbor Behavioral claims", "HARBORBH", 1, today, 30, d -> true, "03:02", hb, Math.max(2, hb / 5), "HARBORBH_BH_CLAIMS_", ".txt", rnd, Map.of(9, "SKIPPED_EMPTY")));
        for (Run r : history) stores.runs().save(r);
    }

    private interface DayFilter { boolean test(LocalDate d); }

    private List<Run> series(String defId, String name, String vendor, int versionNo, LocalDate today, int days, DayFilter filter, String time, int base, int spread,
                             String prefix, String ext, Random rnd, Map<Integer, String> anomalies) {
        List<Run> out = new ArrayList<>();
        for (int i = days; i >= 1; i--) {
            LocalDate d = today.minusDays(i);
            if (!filter.test(d)) continue;
            Run r = new Run();
            r.id = Ids.shortId("r");
            r.definitionId = defId;
            r.definitionName = name;
            r.vendorCode = vendor;
            r.versionNo = versionNo;
            r.mode = "PRODUCTION";
            r.trigger = "SCHEDULE";
            r.startedBy = "Scheduler";
            r.businessDate = d.toString();
            r.startedAt = d + "T" + time + ":" + String.format("%02d", rnd.nextInt(50));
            r.finishedAt = LocalDateTime.parse(r.startedAt).plusSeconds(20 + rnd.nextInt(90)).toString();
            r.durationMs = 20000 + rnd.nextInt(90000);
            r.fileName = prefix + d.toString().replace("-", "") + ext;
            r.rowCount = Math.max(0, base + rnd.nextInt(spread * 2 + 1) - spread);
            r.route = "PROD";
            r.archived = true;
            r.engineVersion = "1.0.0";
            String anomaly = anomalies.get(i);
            if ("FAILED".equals(anomaly)) {
                r.status = "FAILED";
                r.error = "Query failed: statement timeout after 1800 s on DW.MEMBER (nightly load still running)";
                r.rowCount = null;
                out.add(r);
                Run retry = new Run();
                retry.id = Ids.shortId("r");
                retry.definitionId = defId; retry.definitionName = name; retry.vendorCode = vendor; retry.versionNo = versionNo;
                retry.mode = "PRODUCTION"; retry.trigger = "RETRY"; retry.startedBy = "Priya Okafor"; retry.businessDate = d.toString();
                retry.startedAt = d + "T06:12:40"; retry.finishedAt = d + "T06:13:31"; retry.durationMs = 51000;
                retry.fileName = r.fileName; retry.rowCount = base + rnd.nextInt(spread); retry.route = "PROD"; retry.archived = true; retry.retryOfRunId = r.id;
                retry.status = "TRANSFERRED"; retry.deliveredAt = d + "T06:13:31"; retry.transferredAt = d + "T06:15:02"; retry.vendorReceivedCount = retry.rowCount;
                retry.deliveryReceipt = "Renamed into mft/" + vendor + "/prod/out/" + retry.fileName + " with control file";
                retry.sha256 = Ids.sha256(retry.fileName + d);
                retry.engineVersion = "1.0.0";
                out.add(retry);
                continue;
            }
            if ("SKIPPED_EMPTY".equals(anomaly)) {
                r.status = "SKIPPED_EMPTY";
                r.rowCount = 0;
                r.error = "No rows in the window; the zero-row policy is skip.";
                out.add(r);
                continue;
            }
            r.status = "TRANSFERRED";
            r.deliveredAt = r.finishedAt;
            r.transferredAt = LocalDateTime.parse(r.finishedAt).plusMinutes(1 + rnd.nextInt(4)).toString();
            r.vendorReceivedCount = r.rowCount;
            r.deliveryReceipt = "Renamed into mft/" + vendor + "/prod/out/" + r.fileName + " with control file";
            r.sha256 = Ids.sha256(r.fileName + d);
            r.bytes = (long) r.rowCount * (90 + rnd.nextInt(40));
            out.add(r);
        }
        return out;
    }

    private void seedAudit(Actor analyst, Actor analyst2, Actor approver, Actor admin) {
        LocalDate today = LocalDate.now();
        List<Object[]> events = List.of(
                new Object[]{today.minusDays(9), analyst2, "DEFINITION_CREATED", "d-northwind-claims", "NWVISION", false},
                new Object[]{today.minusDays(8), analyst2, "SAMPLE_RUN", "d-northwind-claims", "NWVISION", false},
                new Object[]{today.minusDays(8), analyst2, "SAMPLE_SENT_TEST_ROUTE", "d-northwind-claims", "NWVISION", false},
                new Object[]{today.minusDays(4), analyst2, "APPROVAL_REQUESTED", "d-northwind-claims", "NWVISION", false},
                new Object[]{today.minusDays(3), approver, "APPROVED", "d-northwind-claims", "NWVISION", false},
                new Object[]{today.minusDays(4), analyst, "VERSION_CREATED", "d-acme-elig", "ACMEDENTAL", false},
                new Object[]{today.minusDays(2), admin, "DEFINITION_CREATED", "d-cascade-provider", "CASCADE", false},
                new Object[]{today.minusDays(1), analyst, "SAMPLE_DOWNLOADED", "d-acme-elig", "ACMEDENTAL", false},
                new Object[]{today.minusDays(12), approver, "SAMPLE_DOWNLOADED", "d-bluebird-roster", "BLUEBIRD", true},
                new Object[]{today.minusDays(27), admin, "PARTNER_SAVED", "partner:HARBORBH", "HARBORBH", false}
        );
        for (Object[] e : events) {
            var ev = audit.record((Actor) e[1], (String) e[2], "definition", (String) e[3], ((String) e[3]).startsWith("partner:") ? null : (String) e[3], (String) e[4], (Boolean) e[5], Map.of("seeded", true));
            ev.at = ((LocalDate) e[0]).atTime(9 + Math.abs(ev.id.hashCode() % 8), Math.abs(ev.id.hashCode() % 60)).toString();
            stores.audit().save(ev);
        }
        notifications.notify("APPROVAL_REQUESTED", "INFO", "Bluebird PBM member roster v2 needs approval", "Jordan Rivera asks for approval. Vendor acceptance: email from feeds@bluebirdrx.example, 09/17", "d-bluebird-roster", null, List.of("approvers"));
    }
}
