package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.catalog.SourceData;
import com.thehiddenbrain.interop.extract.compliance.ComplianceService;
import com.thehiddenbrain.interop.extract.runtime.BusinessCalendar;
import com.thehiddenbrain.interop.extract.transform.RuleRegistry;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogService catalog;
    private final RuleRegistry rules;
    private final ComplianceService compliance;
    private final BusinessCalendar calendars;
    private final SourceData source;
    private final ApiSupport support;

    public CatalogController(CatalogService catalog, RuleRegistry rules, ComplianceService compliance, BusinessCalendar calendars, SourceData source, ApiSupport support) {
        this.catalog = catalog;
        this.rules = rules;
        this.compliance = compliance;
        this.calendars = calendars;
        this.source = source;
        this.support = support;
    }

    @GetMapping
    public Map<String, Object> catalog() {
        Map<String, Integer> usage = compliance.usageCounts();
        List<Map<String, Object>> elements = new ArrayList<>();
        for (CatalogModel.Element e : catalog.elements()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("entity", e.entity());
            m.put("column", e.column());
            m.put("name", e.name());
            m.put("type", e.type());
            m.put("sensitivity", e.sensitivity());
            m.put("phi", e.isPhi());
            m.put("restricted", e.isRestricted());
            m.put("description", e.description());
            m.put("example", e.example());
            m.put("filterable", e.isFilterable());
            m.put("watermark", e.isWatermark());
            m.put("values", e.values());
            m.put("aliases", e.aliases());
            m.put("usedBy", usage.getOrDefault(e.id(), 0));
            elements.add(m);
        }
        List<Map<String, Object>> entities = new ArrayList<>();
        for (CatalogModel.Entity en : catalog.entities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", en.id());
            m.put("subjectArea", en.subjectArea());
            m.put("name", en.name());
            m.put("table", en.table());
            m.put("source", en.source());
            m.put("primaryKey", en.primaryKeys());
            m.put("watermarkColumn", en.watermarkColumn());
            m.put("readinessCheck", en.readinessCheck());
            m.put("rows", source.sourceExists(en.source()) ? source.rows(en.id()).size() : 0);
            m.put("elements", catalog.elementsOf(en.id()).size());
            entities.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", catalog.version());
        out.put("checksum", catalog.checksum());
        out.put("subjectAreas", catalog.subjectAreas());
        out.put("entities", entities);
        out.put("joinPaths", catalog.joinPaths());
        out.put("elements", elements);
        out.put("lookups", catalog.lookups());
        out.put("filterTemplates", catalog.templates());
        out.put("rules", rules.describe());
        out.put("calendars", calendars.all());
        return out;
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return rules.describe();
    }

    @GetMapping("/impact/{elementId}")
    public Map<String, Object> impact(@PathVariable String elementId) {
        return compliance.impact(elementId);
    }

    @GetMapping("/lookups/{id}/preview")
    public List<Map<String, Object>> lookupPreview(@PathVariable String id) {
        List<Map<String, Object>> rows = source.lookupRows(id);
        return rows.subList(0, Math.min(10, rows.size()));
    }

    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("reload the catalog", "ADMIN");
        catalog.reload();
        return ApiSupport.ok("Catalog reloaded, checksum " + catalog.checksum());
    }
}
