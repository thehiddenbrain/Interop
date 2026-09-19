package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerController {

    private final PartnerService partners;
    private final ApiSupport support;
    private final AppProperties props;

    public PartnerController(PartnerService partners, ApiSupport support, AppProperties props) {
        this.partners = partners;
        this.support = support;
        this.props = props;
    }

    @GetMapping
    public List<Partner> list() {
        return partners.all();
    }

    @GetMapping("/{code}")
    public Partner get(@PathVariable String code) {
        return partners.get(code);
    }

    @PutMapping("/{code}")
    public Partner save(@PathVariable String code, @RequestBody Partner p, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        if (p.code == null || p.code.isBlank()) p.code = code;
        return partners.save(p, support.actor(user));
    }

    @PostMapping
    public Partner create(@RequestBody Partner p, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return partners.save(p, support.actor(user));
    }

    @PostMapping("/{code}/test-route")
    public Map<String, Object> testRoute(@PathVariable String code, @RequestParam(defaultValue = "PROD") String route) {
        return partners.testRoute(code, route);
    }

    @PostMapping("/{code}/rotate-key")
    public Map<String, Object> rotate(@PathVariable String code, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return Map.of("apiKey", partners.rotateApiKey(code, support.actor(user)));
    }

    /** What sits in the partner's drop and sent folders, so the demo can show Axway's side. */
    @GetMapping("/{code}/mft")
    public Map<String, Object> mft(@PathVariable String code) {
        Partner p = partners.get(code);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String route : List.of("TEST", "PROD")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("out", listFiles(partners.outFolder(p, route)));
            r.put("sent", listFiles(partners.sentFolder(p, route)));
            r.put("folder", partners.outFolder(p, route).getParent().toString());
            out.put(route, r);
        }
        return out;
    }

    private List<Map<String, Object>> listFiles(Path dir) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getFileName().toString());
                m.put("bytes", Files.size(f));
                m.put("modified", Files.getLastModifiedTime(f).toInstant().toString());
                out.add(m);
            }
        } catch (IOException ignored) {
        }
        return out;
    }
}
