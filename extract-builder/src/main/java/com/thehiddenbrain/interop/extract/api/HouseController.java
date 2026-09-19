package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.audit.AuditEvent;
import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.audit.Notification;
import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.compliance.ComplianceService;
import com.thehiddenbrain.interop.extract.runtime.DemoSettings;
import com.thehiddenbrain.interop.extract.seed.DemoSeeder;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import com.thehiddenbrain.interop.extract.users.Users;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Dashboard, audit, notifications, compliance, users and settings: the pages around the builder. */
@RestController
@RequestMapping("/api/v1")
public class HouseController {

    private final DashboardService dashboard;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ComplianceService compliance;
    private final Users users;
    private final DemoSettings settings;
    private final StateStores stores;
    private final DemoSeeder seeder;
    private final ApiSupport support;

    public HouseController(DashboardService dashboard, AuditService audit, NotificationService notifications, ComplianceService compliance, Users users,
                           DemoSettings settings, StateStores stores, DemoSeeder seeder, ApiSupport support) {
        this.dashboard = dashboard;
        this.audit = audit;
        this.notifications = notifications;
        this.compliance = compliance;
        this.users = users;
        this.settings = settings;
        this.stores = stores;
        this.seeder = seeder;
        this.support = support;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboard.build();
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(defaultValue = "300") int limit, @RequestParam(required = false) String definitionId,
                                  @RequestParam(required = false) String vendorCode, @RequestParam(required = false) String actor, @RequestParam(required = false) Boolean phi) {
        return audit.recent(limit, definitionId, vendorCode, actor, phi);
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("unread", notifications.unread(), "items", notifications.recent(limit));
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAll() {
        notifications.markAllRead();
        return ApiSupport.ok("All read");
    }

    @GetMapping("/compliance/summary")
    public Map<String, Object> complianceSummary() {
        return compliance.summary();
    }

    @GetMapping("/compliance/vendors")
    public List<Map<String, Object>> complianceVendors() {
        return compliance.vendorReports();
    }

    @GetMapping("/compliance/vendors/{code}")
    public Map<String, Object> complianceVendor(@PathVariable String code) {
        return compliance.vendorReport(code);
    }

    @GetMapping(value = "/compliance/export.csv", produces = "text/csv")
    public String complianceCsv(@RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        audit.record(actor, "COMPLIANCE_EXPORTED", "report", "minimum-necessary", null, null, false, Map.of());
        return compliance.csv();
    }

    @GetMapping("/users")
    public List<Actor> users() {
        return users.all();
    }

    @GetMapping("/me")
    public Actor me(@RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return support.actor(user);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return settings.asMap();
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("change settings", "ADMIN");
        settings.apply(body);
        audit.record(actor, "SETTINGS_CHANGED", "settings", "demo", null, null, false, body);
        return settings.asMap();
    }

    @PostMapping("/demo/reset")
    public Map<String, Object> reset(@RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("reset the demo", "ADMIN");
        stores.reset();
        seeder.seed(true);
        return ApiSupport.ok("Demo data reset");
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of("status", "UP", "definitions", stores.definitions().size(), "runs", stores.runs().size());
    }
}
