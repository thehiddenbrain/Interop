package com.thehiddenbrain.interop.extract.compliance;

import com.thehiddenbrain.interop.extract.audit.AuditEvent;
import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.definition.VersionStatus;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.RunService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Because every vendor layout is data, the app can answer the privacy office's questions directly: which PHI
 * elements does each vendor receive, is a BAA on file, who pulled samples and were they masked, what changes when
 * a source column goes away.
 */
@Service
public class ComplianceService {

    private final DefinitionService definitions;
    private final PartnerService partners;
    private final CatalogService catalog;
    private final AuditService audit;
    private final RunService runs;

    public ComplianceService(DefinitionService definitions, PartnerService partners, CatalogService catalog, AuditService audit, RunService runs) {
        this.definitions = definitions;
        this.partners = partners;
        this.catalog = catalog;
        this.audit = audit;
        this.runs = runs;
    }

    public List<Map<String, Object>> vendorReports() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Partner p : partners.all()) out.add(vendorReport(p.code));
        return out;
    }

    public Map<String, Object> vendorReport(String vendorCode) {
        Partner p = partners.get(vendorCode);
        List<Definition> defs = definitions.all().stream().filter(d -> vendorCode.equals(d.vendorCode) && !d.template).toList();
        Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
        List<Map<String, Object>> feeds = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        int phiCount = 0;
        int restrictedUnmasked = 0;
        for (Definition d : defs) {
            for (Definition.Version v : d.versions) {
                boolean live = VersionStatus.PRODUCTION.name().equals(v.status);
                boolean pipeline = VersionStatus.APPROVED.name().equals(v.status) || VersionStatus.PENDING_APPROVAL.name().equals(v.status) || VersionStatus.SAMPLED.name().equals(v.status);
                if (!live && !pipeline) continue;
                int fieldPhi = 0;
                for (Spec.Field f : v.spec.fields) {
                    for (Spec.Input in : f.inputs) {
                        if (in.element == null) continue;
                        Optional<CatalogModel.Element> el = catalog.findElement(in.element);
                        if (el.isEmpty()) continue;
                        boolean masked = f.rules.stream().anyMatch(r -> "MASK".equals(r.type)) || in.rules.stream().anyMatch(r -> "MASK".equals(r.type));
                        Map<String, Object> m = elements.computeIfAbsent(in.element, k -> {
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("element", in.element);
                            x.put("name", el.get().name());
                            x.put("entity", el.get().entity());
                            x.put("sensitivity", el.get().sensitivity());
                            x.put("phi", el.get().isPhi());
                            x.put("restricted", el.get().isRestricted());
                            x.put("masked", masked);
                            x.put("feeds", new ArrayList<String>());
                            x.put("live", false);
                            return x;
                        });
                        @SuppressWarnings("unchecked") List<String> fl = (List<String>) m.get("feeds");
                        String tag = d.name + " v" + v.versionNo;
                        if (!fl.contains(tag)) fl.add(tag);
                        if (live) m.put("live", true);
                        if (!masked) m.put("masked", false);
                        if (el.get().isPhi()) fieldPhi++;
                        if (el.get().isRestricted() && !masked && live) restrictedUnmasked++;
                    }
                }
                Map<String, Object> feed = new LinkedHashMap<>();
                feed.put("definitionId", d.id);
                feed.put("name", d.name);
                feed.put("versionNo", v.versionNo);
                feed.put("status", v.status);
                feed.put("fields", v.spec.fields.size());
                feed.put("phiFields", fieldPhi);
                feed.put("lastDelivered", runs.lastDelivered(d.id).map(r -> r.deliveredAt).orElse(null));
                feeds.add(feed);
            }
        }
        for (Map<String, Object> m : elements.values()) if (Boolean.TRUE.equals(m.get("phi"))) phiCount++;
        List<AuditEvent> events = audit.all().stream().filter(a -> vendorCode.equals(a.vendorCode)).toList();
        long sampleDownloads = events.stream().filter(a -> a.action.equals("SAMPLE_DOWNLOADED")).count();
        long unmaskedTouches = events.stream().filter(a -> a.phi).count();
        long apiReads = events.stream().filter(a -> a.action.equals("API_READ")).count();
        boolean liveFeeds = feeds.stream().anyMatch(f -> VersionStatus.PRODUCTION.name().equals(f.get("status")));
        if (phiCount > 0 && !p.baaOnFile) flags.add("Shares " + phiCount + " PHI elements and no BAA is on file.");
        if (restrictedUnmasked > 0) flags.add(restrictedUnmasked + " restricted element(s) such as SSN go out unmasked in a live feed.");
        long days = partners.daysToContractEnd(p, LocalDate.now());
        if (days != Long.MAX_VALUE && days < 0 && liveFeeds) flags.add("Contract ended " + p.contractEnd + " but feeds are still live.");
        else if (days != Long.MAX_VALUE && days <= 30) flags.add("Contract ends in " + days + " days (" + p.contractEnd + ").");
        if (unmaskedTouches > 0) flags.add(unmaskedTouches + " unmasked PHI access event(s) recorded in the audit log.");
        if ("APP".equals(p.pgpBy) && p.pgpKeyExpires != null && LocalDate.parse(p.pgpKeyExpires).isBefore(LocalDate.now().plusDays(60))) flags.add("PGP key " + p.pgpKeyId + " expires " + p.pgpKeyExpires + ".");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendorCode", p.code);
        out.put("vendorName", p.name);
        out.put("category", p.category);
        out.put("baaOnFile", p.baaOnFile);
        out.put("baaSignedDate", p.baaSignedDate);
        out.put("contractStart", p.contractStart);
        out.put("contractEnd", p.contractEnd);
        out.put("pgpBy", p.pgpBy);
        out.put("liveFeeds", feeds.stream().filter(f -> VersionStatus.PRODUCTION.name().equals(f.get("status"))).count());
        out.put("feeds", feeds);
        out.put("elements", new ArrayList<>(elements.values()));
        out.put("elementCount", elements.size());
        out.put("phiCount", phiCount);
        out.put("sampleDownloads", sampleDownloads);
        out.put("unmaskedTouches", unmaskedTouches);
        out.put("apiReads", apiReads);
        out.put("flags", flags);
        out.put("risk", flags.isEmpty() ? "LOW" : (flags.stream().anyMatch(f -> f.contains("no BAA") || f.contains("unmasked in a live") || f.contains("Contract ended")) ? "HIGH" : "MEDIUM"));
        return out;
    }

    public Map<String, Object> summary() {
        List<Map<String, Object>> reports = vendorReports();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendors", reports.size());
        out.put("vendorsWithPhi", reports.stream().filter(r -> (int) r.get("phiCount") > 0).count());
        out.put("vendorsWithoutBaa", reports.stream().filter(r -> (int) r.get("phiCount") > 0 && !(boolean) r.get("baaOnFile")).count());
        out.put("highRisk", reports.stream().filter(r -> "HIGH".equals(r.get("risk"))).count());
        Set<String> phiElements = new HashSet<>();
        for (Map<String, Object> r : reports) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> els = (List<Map<String, Object>>) r.get("elements");
            for (Map<String, Object> e : els) if (Boolean.TRUE.equals(e.get("phi"))) phiElements.add((String) e.get("element"));
        }
        out.put("distinctPhiElementsShared", phiElements.size());
        out.put("phiAccessEvents", audit.all().stream().filter(a -> a.phi).count());
        return out;
    }

    /** CSV export of the minimum-necessary inventory. */
    public String csv() {
        StringBuilder sb = new StringBuilder("vendor_code,vendor_name,baa_on_file,contract_end,element,element_name,sensitivity,restricted,masked,live,feeds\n");
        for (Map<String, Object> r : vendorReports()) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> els = (List<Map<String, Object>>) r.get("elements");
            for (Map<String, Object> e : els) {
                sb.append(csv(r.get("vendorCode"))).append(',').append(csv(r.get("vendorName"))).append(',').append(r.get("baaOnFile")).append(',').append(csv(r.get("contractEnd")))
                        .append(',').append(csv(e.get("element"))).append(',').append(csv(e.get("name"))).append(',').append(csv(e.get("sensitivity"))).append(',').append(e.get("restricted"))
                        .append(',').append(e.get("masked")).append(',').append(e.get("live")).append(',').append(csv(String.join("; ", (List<String>) e.get("feeds")))).append('\n');
            }
        }
        return sb.toString();
    }

    private static String csv(Object o) {
        if (o == null) return "";
        String s = o.toString();
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Change impact: what breaks if an element is retired or renamed. */
    public Map<String, Object> impact(String elementId) {
        CatalogModel.Element el = catalog.element(elementId);
        List<Map<String, Object>> usages = definitions.usages(elementId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("element", el);
        out.put("usages", usages);
        out.put("liveFeeds", usages.stream().filter(u -> VersionStatus.PRODUCTION.name().equals(u.get("status"))).count());
        out.put("vendors", usages.stream().map(u -> u.get("vendorCode")).distinct().count());
        return out;
    }

    /** Usage counts per element, for the catalog page. */
    public Map<String, Integer> usageCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Definition d : definitions.all()) {
            for (Definition.Version v : d.versions) {
                if (VersionStatus.RETIRED.name().equals(v.status)) continue;
                Set<String> seen = new HashSet<>();
                for (Spec.Field f : v.spec.fields) for (Spec.Input in : f.inputs) if (in.element != null) seen.add(in.element);
                for (Spec.FilterSpec f : v.spec.filters) if (f.element != null) seen.add(f.element);
                for (String e : seen) counts.merge(e, 1, Integer::sum);
            }
        }
        return counts;
    }
}
