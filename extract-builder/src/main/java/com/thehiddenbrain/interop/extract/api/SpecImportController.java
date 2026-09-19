package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.specimport.SpecImportService;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/spec-import")
public class SpecImportController {

    private final SpecImportService importer;
    private final DefinitionService definitions;
    private final ApiSupport support;

    public SpecImportController(SpecImportService importer, DefinitionService definitions, ApiSupport support) {
        this.importer = importer;
        this.definitions = definitions;
        this.support = support;
    }

    @PostMapping("/propose")
    public Map<String, Object> propose(@RequestBody Map<String, String> body) {
        return importer.propose(body.getOrDefault("text", ""), body.get("subjectArea"), body.get("vendorCode"));
    }

    @PostMapping(value = "/propose-file", consumes = "multipart/form-data")
    public Map<String, Object> proposeFile(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String subjectArea, @RequestParam(required = false) String vendorCode) throws IOException {
        return importer.propose(new String(file.getBytes(), StandardCharsets.UTF_8), subjectArea, vendorCode);
    }

    public static class AcceptRequest {
        public String name;
        public String vendorCode;
        public String subjectArea;
        public String grain;
        public String description;
        public Spec spec;
    }

    @PostMapping("/accept")
    public Definition accept(@RequestBody AcceptRequest req, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        DefinitionService.CreateRequest cr = new DefinitionService.CreateRequest();
        cr.name = req.name;
        cr.vendorCode = req.vendorCode;
        cr.subjectArea = req.subjectArea;
        cr.grain = req.grain;
        cr.description = req.description == null ? "Imported from the vendor's layout spec" : req.description;
        Definition d = definitions.create(cr, actor);
        definitions.updateSpec(d.id, 1, req.spec, actor);
        return definitions.get(d.id);
    }
}
