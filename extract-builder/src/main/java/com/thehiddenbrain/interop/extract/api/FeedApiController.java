package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.config.ApiErrors;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.engine.Runner;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The same definition, served as an API. A vendor that prefers pulling over receiving files calls this with its
 * API key and gets the production layout as JSON rows: same rules, same filters, same audit.
 */
@RestController
@RequestMapping("/api/v1/feeds")
public class FeedApiController {

    private final DefinitionService definitions;
    private final PartnerService partners;
    private final Runner runner;
    private final AuditService audit;

    public FeedApiController(DefinitionService definitions, PartnerService partners, Runner runner, AuditService audit) {
        this.definitions = definitions;
        this.partners = partners;
        this.runner = runner;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition d : definitions.all()) {
            Optional<Definition.Version> prod = d.production();
            if (prod.isEmpty() || d.template) continue;
            Optional<Partner> p = partners.find(d.vendorCode);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vendorCode", d.vendorCode);
            m.put("slug", d.slug);
            m.put("name", d.name);
            m.put("versionNo", prod.get().versionNo);
            m.put("apiEnabled", p.isPresent() && p.get().apiEnabled);
            m.put("path", "/api/v1/feeds/" + d.vendorCode + "/" + d.slug);
            m.put("fields", prod.get().spec.fields.stream().map(f -> f.header).toList());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/{vendorCode}/{slug}")
    public Map<String, Object> read(@PathVariable String vendorCode, @PathVariable String slug,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int size,
                                    @RequestParam(required = false) String asOf,
                                    @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                    @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Partner p = partners.find(vendorCode).orElseThrow(() -> new ApiErrors.NotFound("Unknown vendor " + vendorCode));
        if (!p.apiEnabled) throw new ApiErrors.Forbidden("API access is not enabled for " + p.name);
        if (apiKey == null || !apiKey.equals(p.apiKey)) throw new ApiErrors.Forbidden("Invalid or missing X-Api-Key");
        Definition d = definitions.all().stream().filter(x -> vendorCode.equals(x.vendorCode) && slug.equals(x.slug)).findFirst().orElseThrow(() -> new ApiErrors.NotFound("Unknown feed " + slug));
        Definition.Version v = d.production().orElseThrow(() -> new ApiErrors.Conflict("No version of " + d.name + " is in production"));
        Run run = new Run();
        run.id = "api";
        Runner.Options o = new Runner.Options();
        o.mode = "PREVIEW";
        o.masking = false;
        o.rowLimit = Math.min(size, 500) * page;
        o.runDate = asOf == null ? LocalDate.now() : LocalDate.parse(asOf);
        o.businessDate = o.runDate;
        runner.execute(run, d, v, o);
        if ("FAILED".equals(run.status)) throw new ApiErrors.BadRequest(run.error);
        int from = Math.min(run.previewRows.size(), (page - 1) * Math.min(size, 500));
        List<Map<String, String>> rows = run.previewRows.subList(from, run.previewRows.size());
        audit.record(new Actor("api:" + p.code, p.name + " (API)", "VENDOR", false, "API client"), "API_READ", "feed", d.slug, d.id, d.vendorCode, true,
                Map.of("page", page, "size", size, "rows", rows.size(), "version", v.versionNo));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("feed", d.name);
        meta.put("vendorCode", d.vendorCode);
        meta.put("versionNo", v.versionNo);
        meta.put("specHash", v.specHash);
        meta.put("generatedAt", LocalDateTime.now().toString());
        meta.put("asOf", o.runDate.toString());
        meta.put("page", page);
        meta.put("size", size);
        meta.put("returned", rows.size());
        meta.put("matched", run.matched);
        out.put("meta", meta);
        out.put("rows", rows);
        return out;
    }
}
