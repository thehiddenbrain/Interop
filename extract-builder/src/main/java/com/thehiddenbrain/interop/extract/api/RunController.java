package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.DefinitionService;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.runtime.RunService;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runs;
    private final DefinitionService definitions;
    private final ApiSupport support;

    public RunController(RunService runs, DefinitionService definitions, ApiSupport support) {
        this.runs = runs;
        this.definitions = definitions;
        this.support = support;
    }

    @GetMapping
    public List<Run> list(@RequestParam(required = false) String mode, @RequestParam(required = false) String status, @RequestParam(defaultValue = "200") int limit) {
        return runs.all().stream()
                .filter(r -> mode == null || mode.equals(r.mode))
                .filter(r -> status == null || status.equals(r.status))
                .limit(limit)
                .toList();
    }

    @GetMapping("/{id}")
    public Run get(@PathVariable String id) {
        return runs.get(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Path p = runs.download(id, support.actor(user));
        Run run = runs.get(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + run.fileName + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(p));
    }

    @GetMapping(value = "/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public String content(@PathVariable String id, @RequestParam(defaultValue = "60") int lines, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Path p = runs.download(id, support.actor(user));
        try {
            List<String> all = java.nio.file.Files.readAllLines(p);
            return String.join("\n", all.subList(0, Math.min(lines, all.size()))) + (all.size() > lines ? "\n… " + (all.size() - lines) + " more lines" : "");
        } catch (java.io.IOException e) {
            throw new com.thehiddenbrain.interop.extract.config.ApiErrors.NotFound("File unreadable");
        }
    }

    @PostMapping("/{id}/send-test-route")
    public Run sendTest(@PathVariable String id, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        return runs.sendSampleToTestRoute(id, support.actor(user));
    }

    @PostMapping("/{id}/release")
    public Run release(@PathVariable String id, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Run run = runs.get(id);
        Definition d = definitions.get(run.definitionId);
        return runs.release(id, support.actor(user), d, definitions.version(d, run.versionNo));
    }

    @PostMapping("/{id}/retry")
    public Run retry(@PathVariable String id, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Actor actor = support.actor(user);
        actor.require("retry a run", "ANALYST", "APPROVER", "ADMIN");
        Run run = runs.get(id);
        Definition d = definitions.get(run.definitionId);
        return runs.retry(id, actor, d, definitions.version(d, run.versionNo));
    }

    @PostMapping("/{id}/redeliver")
    public Run redeliver(@PathVariable String id, @RequestHeader(value = ApiSupport.USER_HEADER, required = false) String user) {
        Run run = runs.get(id);
        return runs.redeliver(id, support.actor(user), definitions.get(run.definitionId));
    }
}
