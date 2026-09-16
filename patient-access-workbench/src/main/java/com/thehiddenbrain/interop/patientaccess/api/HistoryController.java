package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.history.CurlRenderer;
import com.thehiddenbrain.interop.patientaccess.history.RequestLog;
import com.thehiddenbrain.interop.patientaccess.history.RequestRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@Tag(name = "Request history", description = "Every outbound FHIR, discovery and token request with redacted headers")
public class HistoryController {

    private final RequestLog history;

    public HistoryController(RequestLog history) {
        this.history = history;
    }

    @Operation(summary = "Newest requests first, filterable by environment, purpose (search, read, discovery, auth, conformance) and run")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RequestRecord.Summary> list(@RequestParam(required = false) String environmentId,
                                            @RequestParam(required = false) String purpose,
                                            @RequestParam(required = false) String correlationId,
                                            @RequestParam(defaultValue = "100") int limit) {
        return history.list(environmentId, purpose, correlationId, Math.min(Math.max(limit, 1), 1000));
    }

    @Operation(summary = "One request with (truncated) bodies")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RequestRecord get(@PathVariable String id) {
        return history.get(id).orElseThrow(() -> RequestLog.notFound(id));
    }

    @Operation(summary = "The request as a cURL command (tokens replaced by $TOKEN)")
    @GetMapping(value = "/{id}/curl", produces = MediaType.TEXT_PLAIN_VALUE)
    public String curl(@PathVariable String id) {
        return CurlRenderer.render(history.get(id).orElseThrow(() -> RequestLog.notFound(id)));
    }

    @Operation(summary = "Clear the in-memory history (files on disk are kept)")
    @DeleteMapping
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void clear() {
        history.clear();
    }
}
