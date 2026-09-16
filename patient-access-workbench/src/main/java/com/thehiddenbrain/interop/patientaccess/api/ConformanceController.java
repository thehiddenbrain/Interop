package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.conformance.ConformanceRun;
import com.thehiddenbrain.interop.patientaccess.conformance.ConformanceRunner;
import com.thehiddenbrain.interop.patientaccess.conformance.ConformanceSuite;
import com.thehiddenbrain.interop.patientaccess.conformance.HtmlReport;
import com.thehiddenbrain.interop.patientaccess.conformance.RunStore;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conformance")
@Tag(name = "Conformance", description = "Automated CMS-9115-F / CMS-0057-F checks against an environment")
public class ConformanceController {

    private final ConformanceSuite suite;
    private final ConformanceRunner runner;
    private final RunStore store;
    private final EnvironmentService environments;
    private final HtmlReport report;

    public ConformanceController(ConformanceSuite suite, ConformanceRunner runner, RunStore store, EnvironmentService environments, HtmlReport report) {
        this.suite = suite;
        this.runner = runner;
        this.store = store;
        this.environments = environments;
        this.report = report;
    }

    public record StartRequest(String environmentId, String patientId, List<String> groups) {
    }

    @Operation(summary = "Groups of the suite with check counts")
    @GetMapping(value = "/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ConformanceSuite.GroupInfo> groups() {
        return suite.groups();
    }

    @Operation(summary = "Every check with its citation and severity")
    @GetMapping(value = "/checks", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ConformanceSuite.CheckInfo> checks() {
        return suite.checkInfos();
    }

    @Operation(summary = "Start a run (in the background); poll GET /runs/{id} for progress")
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConformanceRun.Summary start(@RequestBody StartRequest request) {
        if (request == null || request.environmentId() == null) {
            throw new WorkbenchException(com.thehiddenbrain.interop.patientaccess.common.ErrorCode.VALIDATION_ERROR, "environmentId is required");
        }
        return runner.start(environments.require(request.environmentId()), blankToNull(request.patientId()), request.groups()).summary();
    }

    @Operation(summary = "Runs, newest first")
    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ConformanceRun.Summary> runs(@RequestParam(required = false) String environmentId) {
        return store.list(environmentId);
    }

    @Operation(summary = "A run with all results so far")
    @GetMapping(value = "/runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConformanceRun run(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> WorkbenchException.notFound("conformance run", id));
    }

    @Operation(summary = "The run as a self-contained HTML report")
    @GetMapping(value = "/runs/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public String html(@PathVariable String id) {
        return report.render(store.find(id).orElseThrow(() -> WorkbenchException.notFound("conformance run", id)));
    }

    @Operation(summary = "Stop a running run after the checks in flight finish")
    @PostMapping(value = "/runs/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConformanceRun.Summary cancel(@PathVariable String id) {
        ConformanceRun run = store.find(id).orElseThrow(() -> WorkbenchException.notFound("conformance run", id));
        runner.cancel(id);
        return run.summary();
    }

    @Operation(summary = "Delete a run")
    @DeleteMapping("/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
