package com.thehiddenbrain.interop.extract.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.config.ApiErrors;
import com.thehiddenbrain.interop.extract.engine.Compiled;
import com.thehiddenbrain.interop.extract.engine.Runner;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.runtime.DemoSettings;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.RunService;
import com.thehiddenbrain.interop.extract.runtime.Scheduler;
import com.thehiddenbrain.interop.extract.runtime.Watermark;
import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Definitions, versions and the state machine: DRAFT, SAMPLED, PENDING_APPROVAL, APPROVED, PRODUCTION, RETIRED.
 * Only a DRAFT is editable. A sample binds a hash of the spec to the run; approval requires that hash to match;
 * productionalize runs a dry run and retires the previous live version in the same step.
 */
@Service
public class DefinitionService {

    private final StateStores stores;
    private final Runner runner;
    private final RunService runs;
    private final CatalogService catalog;
    private final PartnerService partners;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Scheduler scheduler;
    private final DemoSettings settings;
    private final ObjectMapper canonical;

    public DefinitionService(StateStores stores, Runner runner, RunService runs, CatalogService catalog, PartnerService partners,
                             AuditService audit, NotificationService notifications, Scheduler scheduler, DemoSettings settings, ObjectMapper mapper) {
        this.stores = stores;
        this.runner = runner;
        this.runs = runs;
        this.catalog = catalog;
        this.partners = partners;
        this.audit = audit;
        this.notifications = notifications;
        this.scheduler = scheduler;
        this.settings = settings;
        this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    // ---------- queries ----------

    public List<Definition> all() {
        List<Definition> all = new ArrayList<>(stores.definitions().all());
        all.sort(Comparator.comparing((Definition d) -> d.template).thenComparing(d -> d.updatedAt == null ? "" : d.updatedAt, Comparator.reverseOrder()));
        return all;
    }

    public Definition get(String id) {
        return stores.definitions().get(id, "Definition");
    }

    public Definition.Version version(Definition def, int versionNo) {
        return def.version(versionNo).orElseThrow(() -> new ApiErrors.NotFound("Version " + versionNo + " of " + def.name + " not found"));
    }

    public String specHash(Spec spec) {
        try {
            return Ids.sha256(canonical.writeValueAsString(spec)).substring(0, 16);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Spec copy(Spec spec) {
        try {
            return canonical.readValue(canonical.writeValueAsString(spec), Spec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- create ----------

    public static class CreateRequest {
        public String name;
        public String vendorCode;
        public String subjectArea;
        public String grain;
        public String description;
        public String fromDefinitionId;
        public boolean template;
    }

    public Definition create(CreateRequest req, Actor actor) {
        actor.require("create definitions", "ANALYST", "ADMIN", "APPROVER");
        if (req.name == null || req.name.isBlank()) throw new ApiErrors.BadRequest("Give the definition a name");
        if (req.vendorCode == null || req.vendorCode.isBlank()) throw new ApiErrors.BadRequest("Pick a vendor");
        Definition def = new Definition();
        def.id = Ids.shortId("d");
        def.name = req.name.trim();
        def.slug = Ids.slug(req.vendorCode + "-" + req.name);
        def.vendorCode = req.vendorCode.trim().toUpperCase();
        def.description = req.description;
        def.template = req.template;
        def.owner = actor.name();
        def.createdAt = now();
        def.createdBy = actor.name();
        def.updatedAt = def.createdAt;
        Definition.Version v = new Definition.Version();
        v.versionNo = 1;
        v.createdAt = def.createdAt;
        v.createdBy = actor.name();
        if (req.fromDefinitionId != null && !req.fromDefinitionId.isBlank()) {
            Definition from = get(req.fromDefinitionId);
            Definition.Version source = from.production().orElse(from.latest());
            def.subjectArea = from.subjectArea;
            def.grain = from.grain;
            v.spec = copy(source.spec);
            v.changeNote = "Started from " + from.name + " v" + source.versionNo;
            if (from.template) def.description = def.description == null || def.description.isBlank() ? from.description : def.description;
        } else {
            def.subjectArea = req.subjectArea == null ? "MEMBER" : req.subjectArea;
            catalog.subjectArea(def.subjectArea);
            def.grain = req.grain == null || req.grain.isBlank() ? catalog.subjectArea(def.subjectArea).grains().get(0).entity() : req.grain;
            catalog.entity(def.grain);
            v.spec = new Spec();
        }
        v.specHash = specHash(v.spec);
        v.catalogChecksum = catalog.checksum();
        def.versions.add(v);
        stores.definitions().save(def);
        audit.record(actor, "DEFINITION_CREATED", def.id, def.vendorCode, Map.of("name", def.name, "from", req.fromDefinitionId == null ? "" : req.fromDefinitionId));
        return def;
    }

    public Definition.Version newVersion(String id, Actor actor, String changeNote) {
        actor.require("create versions", "ANALYST", "ADMIN", "APPROVER");
        Definition def = get(id);
        if (def.open().isPresent()) throw new ApiErrors.Conflict("Version " + def.open().get().versionNo + " is still open (" + def.open().get().status.toLowerCase().replace('_', ' ') + "). Finish or discard it first.");
        Definition.Version source = def.production().orElse(def.latest());
        Definition.Version v = new Definition.Version();
        v.versionNo = def.latest().versionNo + 1;
        v.spec = copy(source.spec);
        v.specHash = specHash(v.spec);
        v.catalogChecksum = catalog.checksum();
        v.createdAt = now();
        v.createdBy = actor.name();
        v.changeNote = changeNote;
        v.productionConfig = source.productionConfig == null ? null : copyConfig(source.productionConfig);
        def.versions.add(v);
        touch(def);
        audit.record(actor, "VERSION_CREATED", def.id, def.vendorCode, Map.of("version", v.versionNo, "note", changeNote == null ? "" : changeNote));
        return v;
    }

    private ProductionConfig copyConfig(ProductionConfig c) {
        try {
            return canonical.readValue(canonical.writeValueAsString(c), ProductionConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- edit ----------

    public Definition.Version updateSpec(String id, int versionNo, Spec spec, Actor actor) {
        actor.require("edit layouts", "ANALYST", "ADMIN", "APPROVER");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        VersionStatus status = VersionStatus.of(v.status);
        if (status != VersionStatus.DRAFT && status != VersionStatus.SAMPLED) {
            throw new ApiErrors.Conflict("Version " + versionNo + " is " + status.name().toLowerCase().replace('_', ' ') + " and frozen. Create a new version to change the layout.");
        }
        String newHash = specHash(spec);
        if (status == VersionStatus.SAMPLED && !newHash.equals(v.specHash)) {
            runs.deleteSamples(def, versionNo, actor, "Layout changed after the sample was built");
            v.status = VersionStatus.DRAFT.name();
            v.sampledRunId = null;
            v.sampledAt = null;
        }
        v.spec = spec;
        v.specHash = newHash;
        v.updatedAt = now();
        touch(def);
        audit.record(actor, "LAYOUT_SAVED", def.id, def.vendorCode, Map.of("version", versionNo, "fields", spec.fields == null ? 0 : spec.fields.size(), "hash", newHash));
        return v;
    }

    public Definition updateMeta(String id, Map<String, Object> meta, Actor actor) {
        actor.require("edit definitions", "ANALYST", "ADMIN", "APPROVER");
        Definition def = get(id);
        if (meta.get("name") != null) def.name = String.valueOf(meta.get("name"));
        if (meta.get("description") != null) def.description = String.valueOf(meta.get("description"));
        if (meta.get("owner") != null) def.owner = String.valueOf(meta.get("owner"));
        if (meta.get("grain") != null && def.production().isEmpty()) {
            catalog.entity(String.valueOf(meta.get("grain")));
            def.grain = String.valueOf(meta.get("grain"));
        }
        touch(def);
        return def;
    }

    public Compiled validate(Definition def, Definition.Version v) {
        return runner.compile(def, v.spec);
    }

    // ---------- lifecycle ----------

    public Run sample(String id, int versionNo, Actor actor, RunService.SampleOptions so) {
        actor.require("build samples", "ANALYST", "ADMIN", "APPROVER");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        VersionStatus status = VersionStatus.of(v.status);
        if (status != VersionStatus.DRAFT && status != VersionStatus.SAMPLED) throw new ApiErrors.Conflict("Samples are built from a draft; version " + versionNo + " is " + status.name().toLowerCase().replace('_', ' '));
        Compiled c = runner.compile(def, v.spec);
        if (!c.ok()) throw new ApiErrors.BadRequest("Fix the layout before building a sample", c.errors().stream().map(p -> p.where() + ": " + p.message()).toList());
        Run run = runs.sample(def, v, actor, so);
        if ("WRITTEN".equals(run.status)) {
            v.status = VersionStatus.SAMPLED.name();
            v.sampledRunId = run.id;
            v.sampledAt = run.finishedAt;
            touch(def);
        }
        return run;
    }

    public Definition.Version requestApproval(String id, int versionNo, Actor actor, String note, String vendorAcceptance) {
        actor.require("request approval", "ANALYST", "ADMIN", "APPROVER");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        if (!VersionStatus.SAMPLED.name().equals(v.status)) throw new ApiErrors.Conflict("Build a sample first; approval needs a sample of the current layout");
        Run sample = runs.lastSample(def, versionNo).orElseThrow(() -> new ApiErrors.Conflict("No sample exists for this version"));
        if (!v.specHash.equals(sample.specHash)) throw new ApiErrors.Conflict("The layout changed after the sample; build a new sample");
        List<String> blockers = new ArrayList<>();
        for (Run.Warning w : sample.warnings) if ("HIGH".equals(w.severity)) blockers.add(w.field + ": " + w.count + " " + w.code.toLowerCase().replace('_', ' ') + " (" + w.example + ")");
        if (!blockers.isEmpty()) throw new ApiErrors.BadRequest("The sample has warnings that block approval. Fix the layout or the lookup, then build a new sample.", blockers);
        v.status = VersionStatus.PENDING_APPROVAL.name();
        v.approvalRequestedAt = now();
        v.approvalRequestedBy = actor.name();
        v.approvalNote = note;
        v.vendorAcceptance = vendorAcceptance;
        touch(def);
        audit.record(actor, "APPROVAL_REQUESTED", def.id, def.vendorCode, Map.of("version", versionNo, "note", note == null ? "" : note));
        notifications.notify("APPROVAL_REQUESTED", "INFO", def.name + " v" + versionNo + " needs approval", actor.name() + " asks for approval. " + (vendorAcceptance == null ? "" : "Vendor acceptance: " + vendorAcceptance), def.id, sample.id, List.of("approvers"));
        return v;
    }

    public Definition.Version approve(String id, int versionNo, Actor actor, String note) {
        actor.require("approve layouts", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        if (!VersionStatus.PENDING_APPROVAL.name().equals(v.status)) throw new ApiErrors.Conflict("Version " + versionNo + " is not waiting for approval");
        if (!settings.allowSelfApproval && actor.name().equals(v.approvalRequestedBy)) throw new ApiErrors.Forbidden("You requested this approval; another approver must approve it (self-approval is off in Settings)");
        v.status = VersionStatus.APPROVED.name();
        v.approvedAt = now();
        v.approvedBy = actor.name();
        v.approvalNote = note == null || note.isBlank() ? v.approvalNote : note;
        touch(def);
        audit.record(actor, "APPROVED", def.id, def.vendorCode, Map.of("version", versionNo, "note", note == null ? "" : note));
        notifications.notify("APPROVED", "INFO", def.name + " v" + versionNo + " approved", "Approved by " + actor.name() + ". It can now be productionalized.", def.id, null, List.of());
        return v;
    }

    public Definition.Version reject(String id, int versionNo, Actor actor, String note) {
        actor.require("reject layouts", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        if (!VersionStatus.PENDING_APPROVAL.name().equals(v.status)) throw new ApiErrors.Conflict("Version " + versionNo + " is not waiting for approval");
        if (note == null || note.isBlank()) throw new ApiErrors.BadRequest("Say why you are rejecting it");
        v.status = VersionStatus.SAMPLED.name();
        v.rejectedAt = now();
        v.rejectedBy = actor.name();
        v.rejectNote = note;
        touch(def);
        audit.record(actor, "REJECTED", def.id, def.vendorCode, Map.of("version", versionNo, "note", note));
        notifications.notify("REJECTED", "WARN", def.name + " v" + versionNo + " sent back", actor.name() + ": " + note, def.id, null, List.of());
        return v;
    }

    public Definition.Version withdraw(String id, int versionNo, Actor actor) {
        actor.require("withdraw", "ANALYST", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        if (!VersionStatus.APPROVED.name().equals(v.status) && !VersionStatus.PENDING_APPROVAL.name().equals(v.status)) throw new ApiErrors.Conflict("Only pending or approved versions can be withdrawn");
        v.status = VersionStatus.SAMPLED.name();
        v.approvedAt = null;
        v.approvedBy = null;
        touch(def);
        audit.record(actor, "APPROVAL_WITHDRAWN", def.id, def.vendorCode, Map.of("version", versionNo));
        return v;
    }

    public Map<String, Object> dryRun(Definition def, Definition.Version v, ProductionConfig cfg) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Compiled c = runner.compile(def, v.spec);
        checks.add(check("Layout compiles", c.ok(), c.ok() ? c.fields.size() + " fields, " + c.joins.size() + " joins" : String.join("; ", c.errors().stream().map(Compiled.Problem::message).toList())));
        boolean cronOk;
        String cronMsg;
        try {
            String cron = Scheduler.cronFor(cfg.schedule);
            CronExpression.parse(cron);
            cronOk = true;
            cronMsg = cron + " in " + cfg.schedule.timezone;
        } catch (RuntimeException e) {
            cronOk = false;
            cronMsg = e.getMessage();
        }
        checks.add(check("Schedule is valid", cronOk, cronMsg));
        Optional<Partner> partner = partners.find(def.vendorCode);
        checks.add(check("MFT partner registered", partner.isPresent(), partner.map(p -> p.name + " (" + p.code + ")").orElse("No partner for " + def.vendorCode)));
        if (partner.isPresent()) {
            Map<String, Object> route = partners.testRoute(def.vendorCode, cfg.delivery.route);
            checks.add(check("Drop folder writable", Boolean.TRUE.equals(route.get("ok")), String.valueOf(route.get("folder"))));
            checks.add(check("Contract active", partners.contractActive(partner.get(), LocalDate.now()), partner.get().contractEnd == null ? "No end date on file" : "Ends " + partner.get().contractEnd));
            boolean phiShared = c.fields.stream().anyMatch(Compiled.Field::phi);
            checks.add(check("BAA on file for PHI", !phiShared || partner.get().baaOnFile, phiShared ? (partner.get().baaOnFile ? "Signed " + partner.get().baaSignedDate : "This layout shares PHI and no BAA is on file") : "No PHI in this layout"));
            if ("APP".equals(cfg.pgpBy)) {
                boolean keyOk = partner.get().pgpKeyId != null && (partner.get().pgpKeyExpires == null || LocalDate.parse(partner.get().pgpKeyExpires).isAfter(LocalDate.now()));
                checks.add(check("PGP key valid", keyOk, partner.get().pgpKeyId == null ? "No vendor key registered" : "Key " + partner.get().pgpKeyId + " expires " + partner.get().pgpKeyExpires));
            }
        }
        boolean fileNameOk = cfg.fileName.pattern != null && !cfg.fileName.pattern.isBlank();
        Runner.Options o = new Runner.Options();
        o.fileNamePattern = cfg.fileName.pattern;
        o.dateSource = cfg.fileName.dateSource;
        checks.add(check("File name resolves", fileNameOk, fileNameOk ? runner.fileName(def, v, o, v.spec.fileFormat) : "Empty pattern"));
        if (v.spec.scope != null && "INCREMENTAL".equals(v.spec.scope.mode)) {
            Optional<Watermark> wm = runs.watermark(def.id);
            boolean carried = wm.isPresent() && wm.get().elements != null && wm.get().elements.equals(v.spec.scope.watermarkElements);
            checks.add(check("Watermark", true, carried ? "Carries over from the current version: " + wm.get().value : "Starts from " + (cfg.initialWatermark == null || cfg.initialWatermark.isBlank() ? "30 days before the first run" : cfg.initialWatermark)));
        }
        boolean ok = checks.stream().allMatch(m -> Boolean.TRUE.equals(m.get("ok")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("checks", checks);
        out.put("nextRuns", scheduler.nextRuns(cfg.schedule, 5));
        return out;
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    public Definition.Version saveProductionConfig(String id, int versionNo, ProductionConfig cfg, Actor actor) {
        actor.require("edit production settings", "ANALYST", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        cfg.schedule.cron = Scheduler.cronFor(cfg.schedule);
        v.productionConfig = cfg;
        touch(def);
        if (VersionStatus.PRODUCTION.name().equals(v.status)) {
            scheduler.register(def, v);
            audit.record(actor, "PRODUCTION_CONFIG_CHANGED", def.id, def.vendorCode, Map.of("version", versionNo, "cron", cfg.schedule.cron, "route", cfg.delivery.route));
        }
        return v;
    }

    public Definition.Version productionalize(String id, int versionNo, ProductionConfig cfg, Actor actor) {
        actor.require("productionalize", "APPROVER", "ADMIN", "ANALYST");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        if (!VersionStatus.APPROVED.name().equals(v.status)) throw new ApiErrors.Conflict("Only an approved version can go to production");
        if (cfg != null) {
            cfg.schedule.cron = Scheduler.cronFor(cfg.schedule);
            v.productionConfig = cfg;
        }
        if (v.productionConfig == null) throw new ApiErrors.BadRequest("Complete the productionalize wizard first");
        Map<String, Object> dry = dryRun(def, v, v.productionConfig);
        if (!Boolean.TRUE.equals(dry.get("ok"))) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> checks = (List<Map<String, Object>>) dry.get("checks");
            throw new ApiErrors.BadRequest("The dry run found problems", checks.stream().filter(m -> !Boolean.TRUE.equals(m.get("ok"))).map(m -> m.get("name") + ": " + m.get("detail")).toList());
        }
        Optional<Definition.Version> previous = def.production();
        previous.ifPresent(p -> {
            p.status = VersionStatus.RETIRED.name();
            p.retiredAt = now();
            p.retiredReason = "Replaced by v" + v.versionNo;
        });
        Optional<Watermark> wm = runs.watermark(def.id);
        if (wm.isPresent() && (v.spec.scope == null || !"INCREMENTAL".equals(v.spec.scope.mode) || !wm.get().elements.equals(v.spec.scope.watermarkElements))) {
            stores.watermarks().delete(def.id);
        }
        v.status = VersionStatus.PRODUCTION.name();
        v.productionizedAt = now();
        v.productionizedBy = actor.name();
        v.schedulePaused = false;
        touch(def);
        scheduler.register(def, v);
        audit.record(actor, "PRODUCTIONALIZED", def.id, def.vendorCode, Map.of("version", versionNo, "replaced", previous.map(p -> p.versionNo).orElse(0), "cron", v.productionConfig.schedule.cron));
        notifications.notify("INFO", "INFO", def.name + " v" + versionNo + " is live", "Schedule " + v.productionConfig.schedule.preset.toLowerCase() + " at " + v.productionConfig.schedule.time + " " + v.productionConfig.schedule.timezone + (previous.isPresent() ? "; v" + previous.get().versionNo + " retired." : "."), def.id, null, List.of());
        return v;
    }

    public Definition.Version retire(String id, int versionNo, Actor actor, String reason) {
        actor.require("retire versions", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        v.status = VersionStatus.RETIRED.name();
        v.retiredAt = now();
        v.retiredReason = reason;
        touch(def);
        if (def.production().isEmpty()) scheduler.unregister(def.id);
        audit.record(actor, "RETIRED", def.id, def.vendorCode, Map.of("version", versionNo, "reason", reason == null ? "" : reason));
        return v;
    }

    public Definition.Version pause(String id, int versionNo, Actor actor, boolean paused) {
        actor.require("pause schedules", "APPROVER", "ADMIN", "ANALYST");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        v.schedulePaused = paused;
        touch(def);
        audit.record(actor, paused ? "SCHEDULE_PAUSED" : "SCHEDULE_RESUMED", def.id, def.vendorCode, Map.of("version", versionNo));
        return v;
    }

    public void discard(String id, int versionNo, Actor actor) {
        actor.require("discard drafts", "ANALYST", "APPROVER", "ADMIN");
        Definition def = get(id);
        Definition.Version v = version(def, versionNo);
        VersionStatus status = VersionStatus.of(v.status);
        if (status != VersionStatus.DRAFT && status != VersionStatus.SAMPLED) throw new ApiErrors.Conflict("Only a draft can be discarded");
        runs.deleteSamples(def, versionNo, actor, "Draft discarded");
        if (def.versions.size() == 1) {
            stores.definitions().delete(def.id);
            audit.record(actor, "DEFINITION_DELETED", def.id, def.vendorCode, Map.of("name", def.name));
            return;
        }
        def.versions.removeIf(x -> x.versionNo == versionNo);
        touch(def);
        audit.record(actor, "VERSION_DISCARDED", def.id, def.vendorCode, Map.of("version", versionNo));
    }

    // ---------- diff ----------

    public Map<String, Object> diff(Definition def, Definition.Version v) {
        Optional<Definition.Version> base = def.versions.stream().filter(x -> x.versionNo != v.versionNo && (VersionStatus.PRODUCTION.name().equals(x.status) || VersionStatus.RETIRED.name().equals(x.status))).max(Comparator.comparingInt(x -> x.versionNo));
        Map<String, Object> out = new LinkedHashMap<>();
        if (base.isEmpty()) {
            out.put("baseVersion", null);
            out.put("summary", "First version of this feed; nothing to compare with.");
            out.put("fields", List.of());
            return out;
        }
        Spec a = base.get().spec, b = v.spec;
        Map<String, Spec.Field> af = new LinkedHashMap<>();
        for (Spec.Field f : a.fields) af.put(f.header.toUpperCase(), f);
        Map<String, Spec.Field> bf = new LinkedHashMap<>();
        for (Spec.Field f : b.fields) bf.put(f.header.toUpperCase(), f);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Spec.Field f : b.fields) {
            Spec.Field old = af.get(f.header.toUpperCase());
            String change = old == null ? "added" : (fieldJson(old).equals(fieldJson(f)) ? (old.position == f.position ? "same" : "moved") : "changed");
            fields.add(Map.of("header", f.header, "position", f.position, "change", change, "oldPosition", old == null ? 0 : old.position));
        }
        for (Spec.Field f : a.fields) if (!bf.containsKey(f.header.toUpperCase())) fields.add(Map.of("header", f.header, "position", 0, "change", "removed", "oldPosition", f.position));
        out.put("baseVersion", base.get().versionNo);
        out.put("fields", fields);
        out.put("filtersChanged", !json(a.filters).equals(json(b.filters)));
        out.put("sortChanged", !json(a.sort).equals(json(b.sort)));
        out.put("formatChanged", !json(a.fileFormat).equals(json(b.fileFormat)));
        out.put("scopeChanged", !json(a.scope).equals(json(b.scope)));
        long added = fields.stream().filter(m -> "added".equals(m.get("change"))).count();
        long removed = fields.stream().filter(m -> "removed".equals(m.get("change"))).count();
        long changed = fields.stream().filter(m -> "changed".equals(m.get("change"))).count();
        long moved = fields.stream().filter(m -> "moved".equals(m.get("change"))).count();
        out.put("summary", String.format("vs v%d: %d added, %d removed, %d changed, %d moved%s", base.get().versionNo, added, removed, changed, moved,
                (Boolean) out.get("filtersChanged") ? ", filters changed" : ""));
        return out;
    }

    private String fieldJson(Spec.Field f) {
        try {
            Spec.Field c = canonical.readValue(canonical.writeValueAsString(f), Spec.Field.class);
            c.position = 0;
            c.id = null;
            if (c.onOverflow == null || c.onOverflow.isBlank()) c.onOverflow = "TRUNCATE";
            if (c.description != null && c.description.isBlank()) c.description = null;
            if (c.defaultValue != null && c.defaultValue.isBlank()) c.defaultValue = null;
            if (c.rules == null) c.rules = new ArrayList<>();
            for (Spec.Input in : c.inputs) if (in.rules == null) in.rules = new ArrayList<>();
            return canonical.writeValueAsString(c);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String json(Object o) {
        try {
            return canonical.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    // ---------- helpers ----------

    public void touch(Definition def) {
        def.updatedAt = now();
        stores.definitions().save(def);
    }

    public static String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Which definitions use a catalog element, for change impact analysis. */
    public List<Map<String, Object>> usages(String elementId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition def : all()) {
            for (Definition.Version v : def.versions) {
                if (VersionStatus.RETIRED.name().equals(v.status)) continue;
                List<String> where = new ArrayList<>();
                for (Spec.Field f : v.spec.fields) {
                    boolean used = f.inputs.stream().anyMatch(i -> elementId.equals(i.element));
                    if (!used) used = json(f.rules).contains("\"" + elementId + "\"");
                    if (used) where.add("field " + f.header);
                }
                if (v.spec.filters.stream().anyMatch(f -> elementId.equals(f.element))) where.add("filter");
                if (v.spec.sort.stream().anyMatch(s -> elementId.equals(s.element))) where.add("sort");
                if (v.spec.scope != null && v.spec.scope.watermarkElements != null && v.spec.scope.watermarkElements.contains(elementId)) where.add("watermark");
                if (!where.isEmpty()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("definitionId", def.id);
                    m.put("name", def.name);
                    m.put("vendorCode", def.vendorCode);
                    m.put("versionNo", v.versionNo);
                    m.put("status", v.status);
                    m.put("where", where);
                    out.add(m);
                }
            }
        }
        return out;
    }
}
