package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.compliance.ComplianceService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.definition.VersionStatus;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.runtime.DemoSettings;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.RunService;
import com.thehiddenbrain.interop.extract.runtime.ScheduleState;
import com.thehiddenbrain.interop.extract.runtime.Scheduler;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** The numbers on the home page: what is live, what happened in the last 30 days, what needs a person, what it saved. */
@Service
public class DashboardService {

    private final DefinitionService definitions;
    private final RunService runs;
    private final PartnerService partners;
    private final ComplianceService compliance;
    private final Scheduler scheduler;
    private final AuditService audit;
    private final NotificationService notifications;
    private final DemoSettings settings;

    public DashboardService(DefinitionService definitions, RunService runs, PartnerService partners, ComplianceService compliance, Scheduler scheduler,
                            AuditService audit, NotificationService notifications, DemoSettings settings) {
        this.definitions = definitions;
        this.runs = runs;
        this.partners = partners;
        this.compliance = compliance;
        this.scheduler = scheduler;
        this.audit = audit;
        this.notifications = notifications;
        this.settings = settings;
    }

    public Map<String, Object> build() {
        List<Definition> defs = definitions.all().stream().filter(d -> !d.template).toList();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (VersionStatus s : VersionStatus.values()) byStatus.put(s.name(), 0L);
        int versions = 0;
        for (Definition d : defs) for (Definition.Version v : d.versions) { byStatus.merge(v.status, 1L, Long::sum); versions++; }
        long live = byStatus.getOrDefault("PRODUCTION", 0L);
        long pending = byStatus.getOrDefault("PENDING_APPROVAL", 0L);

        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Run> recent = runs.all().stream().filter(r -> "PRODUCTION".equals(r.mode) && r.startedAt != null && LocalDateTime.parse(r.startedAt).isAfter(since)).toList();
        long delivered = recent.stream().filter(r -> r.status.equals("DELIVERED") || r.status.equals("TRANSFERRED")).count();
        long failed = recent.stream().filter(r -> r.status.equals("FAILED")).count();
        long held = runs.all().stream().filter(r -> "HELD".equals(r.status)).count();
        long rowsDelivered = recent.stream().filter(r -> r.rowCount != null && (r.status.equals("DELIVERED") || r.status.equals("TRANSFERRED"))).mapToLong(r -> r.rowCount).sum();
        double onTime = recent.isEmpty() ? 100 : Math.round(1000.0 * delivered / Math.max(1, delivered + failed)) / 10.0;

        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (Definition d : defs) {
            Optional<Definition.Version> prod = d.production();
            if (prod.isEmpty()) continue;
            Optional<ScheduleState> st = scheduler.state(d.id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("definitionId", d.id);
            m.put("name", d.name);
            m.put("vendorCode", d.vendorCode);
            m.put("versionNo", prod.get().versionNo);
            m.put("nextFireAt", st.map(s -> s.nextFireAt).orElse(null));
            m.put("paused", Boolean.TRUE.equals(prod.get().schedulePaused));
            m.put("lastRun", runs.forDefinition(d.id).stream().filter(r -> "PRODUCTION".equals(r.mode)).findFirst().map(r -> Map.of("id", r.id, "status", r.status, "at", r.startedAt == null ? "" : r.startedAt, "rows", r.rowCount == null ? 0 : r.rowCount)).orElse(null));
            m.put("sla", prod.get().productionConfig == null ? null : prod.get().productionConfig.sla.deliverBy);
            upcoming.add(m);
        }
        upcoming.sort(Comparator.comparing(m -> m.get("nextFireAt") == null ? "9" : (String) m.get("nextFireAt")));

        Map<String, Object> comp = compliance.summary();
        List<Map<String, Object>> attention = new ArrayList<>();
        for (Run r : runs.all()) {
            if ("HELD".equals(r.status)) attention.add(item("HELD", r.definitionName + " is held before delivery", r.heldReason, r.definitionId, r.id));
            else if ("FAILED".equals(r.status) && "PRODUCTION".equals(r.mode) && recent.contains(r) && !retried(r)) attention.add(item("FAILED", r.definitionName + " failed", r.error, r.definitionId, r.id));
        }
        for (Definition d : defs) for (Definition.Version v : d.versions) if (VersionStatus.PENDING_APPROVAL.name().equals(v.status)) attention.add(item("APPROVAL", d.name + " v" + v.versionNo + " waits for approval", "Requested by " + v.approvalRequestedBy, d.id, null));
        for (var p : partners.all()) {
            long days = partners.daysToContractEnd(p, LocalDate.now());
            if (days != Long.MAX_VALUE && days <= 30) attention.add(item("CONTRACT", p.name + " contract " + (days < 0 ? "ended" : "ends in " + days + " days"), p.contractEnd, null, null));
        }

        int hours = settings.hoursPerHandBuiltExtract;
        long feedsBuilt = defs.stream().filter(d -> d.versions.stream().anyMatch(v -> VersionStatus.PRODUCTION.name().equals(v.status) || VersionStatus.RETIRED.name().equals(v.status) || VersionStatus.APPROVED.name().equals(v.status))).count();
        long changesShipped = Math.max(0, versions - defs.size());
        long hoursSaved = feedsBuilt * hours + changesShipped * (hours / 4);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definitions", defs.size());
        out.put("live", live);
        out.put("byStatus", byStatus);
        out.put("pendingApprovals", pending);
        out.put("delivered30d", delivered);
        out.put("failed30d", failed);
        out.put("held", held);
        out.put("rowsDelivered30d", rowsDelivered);
        out.put("onTimePct", onTime);
        out.put("vendors", partners.all().size());
        out.put("upcoming", upcoming);
        out.put("attention", attention);
        out.put("compliance", comp);
        out.put("hoursSaved", hoursSaved);
        out.put("hoursPerExtract", hours);
        out.put("feedsBuilt", feedsBuilt);
        out.put("changesShipped", changesShipped);
        out.put("recentActivity", audit.recent(8, null, null, null, null));
        out.put("unreadNotifications", notifications.unread());
        out.put("deliveriesByDay", deliveriesByDay(recent));
        return out;
    }

    private boolean retried(Run r) {
        return runs.all().stream().anyMatch(x -> r.id.equals(x.retryOfRunId));
    }

    private static Map<String, Object> item(String kind, String title, String detail, String definitionId, String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("detail", detail);
        m.put("definitionId", definitionId);
        m.put("runId", runId);
        return m;
    }

    private List<Map<String, Object>> deliveriesByDay(List<Run> recent) {
        Map<String, long[]> days = new TreeMap<>();
        for (int i = 29; i >= 0; i--) days.put(LocalDate.now().minusDays(i).toString(), new long[]{0, 0, 0});
        for (Run r : recent) {
            String d = r.startedAt.substring(0, 10);
            long[] c = days.get(d);
            if (c == null) continue;
            if (r.status.equals("DELIVERED") || r.status.equals("TRANSFERRED")) c[0]++;
            else if (r.status.equals("FAILED")) c[1]++;
            else if (r.status.equals("HELD")) c[2]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : days.entrySet()) out.add(Map.of("day", e.getKey(), "delivered", e.getValue()[0], "failed", e.getValue()[1], "held", e.getValue()[2]));
        return out;
    }
}
