package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.definition.*;
import com.thehiddenbrain.interop.extract.engine.Compiled;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.RunService;
import com.thehiddenbrain.interop.extract.runtime.Scheduler;
import com.thehiddenbrain.interop.extract.transform.RuleRegistry;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/definitions")
public class DefinitionController {

    private final DefinitionService definitions;
    private final RunService runs;
    private final Scheduler scheduler;
    private final RuleRegistry rules;
    private final ApiSupport support;

    public DefinitionController(DefinitionService definitions, RunService runs, Scheduler scheduler, RuleRegistry rules, ApiSupport support) {
        this.definitions = definitions;
        this.runs = runs;
        this.scheduler = scheduler;
        this.rules = rules;
        this.support = support;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition d : definitions.all()) out.add(summary(d));
        return out;
    }

    private Map<String, Object> summary(Definition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("slug", d.slug);
        m.put("name", d.name);
        m.put("vendorCode", d.vendorCode);
        m.put("subjectArea", d.subjectArea);
        m.put("grain", d.grain);
        m.put("template", d.template);
        m.put("description", d.description);
        m.put("owner", d.owner);
        m.put("updatedAt", d.updatedAt);
        m.put("createdAt", d.createdAt);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Definition.Version v : d.versions) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("versionNo", v.versionNo);
            vm.put("status", v.status);
            vm.put("fields", v.spec == null || v.spec.fields == null ? 0 : v.spec.fields.size());
            vm.put("changeNote", v.changeNote);
            vm.put("updatedAt", v.updatedAt == null ? v.createdAt : v.updatedAt);
            vm.put("schedulePaused", v.schedulePaused);
            vm.put("approvalRequestedBy", v.approvalRequestedBy);
            vm.put("approvedBy", v.approvedBy);
            versions.add(vm);
        }
        m.put("versions", versions);
        d.production().ifPresent(v -> m.put("productionVersion", v.versionNo));
        d.open().ifPresent(v -> m.put("openVersion", v.versionNo));
        runs.forDefinition(d.id).stream().filter(r -> "PRODUCTION".equals(r.mode)).findFirst().ifPresent(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id);
            rm.put("status", r.status);
            rm.put("at", r.deliveredAt != null ? r.deliveredAt : r.startedAt);
            rm.put("rows", r.rowCount);
            rm.put("error", r.error);
            rm.put("heldReason", r.heldReason);
            m.put("lastRun", rm);
        });
        scheduler.state(d.id).ifPresent(s -> m.put("nextFireAt", s.nextFireAt));
        return m;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Definition d = definitions.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", d);
        m.put("summary", summary(d));
        m.put("watermark", runs.watermark(d.id).orElse(null));
        m.put("schedule", scheduler.state(d.id).orElse(null));
        return m;
    }

    @PostMapping
    public Definition create(@RequestBody DefinitionService.CreateRequest req, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.create(req, support.actor(user));
    }

    @PatchMapping("/{id}")
    public Definition updateMeta(@PathVariable String id, @RequestBody Map<String, Object> meta, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.updateMeta(id, meta, support.actor(user));
    }

    @PostMapping("/{id}/versions")
    public Definition.Version newVersion(@PathVariable String id, @RequestBody(required = false) Map<String, String> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.newVersion(id, support.actor(user), body == null ? null : body.get("changeNote"));
    }

    @GetMapping("/{id}/versions/{no}")
    public Definition.Version version(@PathVariable String id, @PathVariable int no) {
        return definitions.version(definitions.get(id), no);
    }

    @PutMapping("/{id}/versions/{no}/spec")
    public Map<String, Object> saveSpec(@PathVariable String id, @PathVariable int no, @RequestBody Spec spec, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Definition.Version v = definitions.updateSpec(id, no, spec, support.actor(user));
        Definition d = definitions.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", v);
        out.put("validation", validation(definitions.validate(d, v)));
        return out;
    }

    @PostMapping("/{id}/versions/{no}/validate")
    public Map<String, Object> validate(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) Spec spec) {
        Definition d = definitions.get(id);
        Definition.Version v = definitions.version(d, no);
        Compiled c = spec == null ? definitions.validate(d, v) : definitions.validate(d, specOf(v, spec));
        return validation(c);
    }

    private Definition.Version specOf(Definition.Version v, Spec spec) {
        Definition.Version copy = new Definition.Version();
        copy.versionNo = v.versionNo;
        copy.status = v.status;
        copy.spec = spec;
        return copy;
    }

    private Map<String, Object> validation(Compiled c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", c.ok());
        out.put("problems", c.problems);
        out.put("sqlText", c.sqlText);
        out.put("elementsNeeded", c.elementsNeeded);
        List<Map<String, Object>> joins = new ArrayList<>();
        for (Compiled.Join j : c.joins) joins.add(Map.of("path", j.path().id(), "to", j.path().to(), "cardinality", j.path().cardinality(), "selector", j.selector() == null ? "" : j.selector()));
        out.put("joins", joins);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Compiled.Field f : c.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id());
            fm.put("header", f.header());
            fm.put("outputType", f.outputType());
            fm.put("phi", f.phi());
            List<String> chips = new ArrayList<>();
            for (Compiled.Input in : f.inputs()) for (Compiled.RuleInstance r : in.rules()) chips.add(r.rule().type() + " " + r.rule().summarize(r.params()));
            if (f.combiner() != null) chips.add(f.combiner().rule().type() + " " + f.combiner().rule().summarize(f.combiner().params()));
            for (Compiled.RuleInstance r : f.rules()) chips.add(r.rule().type() + " " + r.rule().summarize(r.params()));
            fm.put("chips", chips);
            fields.add(fm);
        }
        out.put("fields", fields);
        out.put("lookups", c.lookupsUsed);
        return out;
    }

    @PostMapping("/{id}/versions/{no}/preview")
    public Run preview(@PathVariable String id, @PathVariable int no, @RequestParam(defaultValue = "20") int rows, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Definition d = definitions.get(id);
        Definition.Version v = definitions.version(d, no);
        Compiled c = definitions.validate(d, v);
        if (!c.ok()) throw new com.thehiddenbrain.interop.extract.config.ApiErrors.BadRequest("Fix the layout before previewing", c.errors().stream().map(p -> p.where() + ": " + p.message()).toList());
        return runs.preview(d, v, support.actor(user), Math.min(rows, 50));
    }

    @PostMapping("/{id}/versions/{no}/sample")
    public Run sample(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) RunService.SampleOptions so, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.sample(id, no, support.actor(user), so == null ? new RunService.SampleOptions() : so);
    }

    @PostMapping("/{id}/versions/{no}/request-approval")
    public Definition.Version requestApproval(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) Map<String, String> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Map<String, String> b = body == null ? Map.of() : body;
        return definitions.requestApproval(id, no, support.actor(user), b.get("note"), b.get("vendorAcceptance"));
    }

    @PostMapping("/{id}/versions/{no}/approve")
    public Definition.Version approve(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) Map<String, String> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.approve(id, no, support.actor(user), body == null ? null : body.get("note"));
    }

    @PostMapping("/{id}/versions/{no}/reject")
    public Definition.Version reject(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) Map<String, String> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.reject(id, no, support.actor(user), body == null ? null : body.get("note"));
    }

    @PostMapping("/{id}/versions/{no}/withdraw")
    public Definition.Version withdraw(@PathVariable String id, @PathVariable int no, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.withdraw(id, no, support.actor(user));
    }

    @GetMapping("/{id}/versions/{no}/diff")
    public Map<String, Object> diff(@PathVariable String id, @PathVariable int no) {
        Definition d = definitions.get(id);
        return definitions.diff(d, definitions.version(d, no));
    }

    @PostMapping("/{id}/versions/{no}/dry-run")
    public Map<String, Object> dryRun(@PathVariable String id, @PathVariable int no, @RequestBody ProductionConfig cfg) {
        Definition d = definitions.get(id);
        Definition.Version v = definitions.version(d, no);
        cfg.schedule.cron = Scheduler.cronFor(cfg.schedule);
        return definitions.dryRun(d, v, cfg);
    }

    @PutMapping("/{id}/versions/{no}/production-config")
    public Definition.Version saveConfig(@PathVariable String id, @PathVariable int no, @RequestBody ProductionConfig cfg, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.saveProductionConfig(id, no, cfg, support.actor(user));
    }

    @PostMapping("/{id}/versions/{no}/productionalize")
    public Definition.Version productionalize(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) ProductionConfig cfg, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.productionalize(id, no, cfg, support.actor(user));
    }

    @PostMapping("/{id}/versions/{no}/retire")
    public Definition.Version retire(@PathVariable String id, @PathVariable int no, @RequestBody(required = false) Map<String, String> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.retire(id, no, support.actor(user), body == null ? null : body.get("reason"));
    }

    @PostMapping("/{id}/versions/{no}/pause")
    public Definition.Version pause(@PathVariable String id, @PathVariable int no, @RequestParam boolean paused, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return definitions.pause(id, no, support.actor(user), paused);
    }

    @DeleteMapping("/{id}/versions/{no}")
    public Map<String, Object> discard(@PathVariable String id, @PathVariable int no, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        definitions.discard(id, no, support.actor(user));
        return ApiSupport.ok("Discarded");
    }

    @GetMapping("/{id}/runs")
    public List<Run> runsFor(@PathVariable String id) {
        return runs.forDefinition(id);
    }

    @PostMapping("/{id}/run-now")
    public Run runNow(@PathVariable String id, @RequestParam(defaultValue = "MANUAL") String trigger, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("start a production run", "ANALYST", "APPROVER", "ADMIN");
        Definition d = definitions.get(id);
        Definition.Version v = d.production().orElseThrow(() -> new com.thehiddenbrain.interop.extract.config.ApiErrors.Conflict("No version of " + d.name + " is in production"));
        RunService.ProductionOptions po = new RunService.ProductionOptions();
        po.trigger = "SCHEDULE".equals(trigger) ? "SCHEDULE" : "MANUAL";
        return runs.production(d, v, actor, po);
    }

    @PostMapping("/{id}/rerun")
    public Run rerun(@PathVariable String id, @RequestParam String businessDate, @RequestParam(defaultValue = "false") boolean commitWatermark, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("re-run a feed", "ANALYST", "APPROVER", "ADMIN");
        Definition d = definitions.get(id);
        Definition.Version v = d.production().orElseThrow(() -> new com.thehiddenbrain.interop.extract.config.ApiErrors.Conflict("No version of " + d.name + " is in production"));
        return runs.rerunAsOf(d, v, actor, LocalDate.parse(businessDate), commitWatermark);
    }

    @GetMapping("/{id}/next-runs")
    public List<Map<String, Object>> nextRuns(@PathVariable String id, @RequestParam(defaultValue = "5") int count) {
        Definition d = definitions.get(id);
        Definition.Version v = d.production().orElse(d.latest());
        if (v.productionConfig == null) return List.of();
        return scheduler.nextRuns(v.productionConfig.schedule, count);
    }

    @PostMapping("/schedule-preview")
    public List<Map<String, Object>> schedulePreview(@RequestBody ProductionConfig.Schedule s, @RequestParam(defaultValue = "6") int count) {
        return scheduler.nextRuns(s, count);
    }
}
