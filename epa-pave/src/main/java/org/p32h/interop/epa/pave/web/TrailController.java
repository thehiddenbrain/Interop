package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.RunTrail;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The trail of the last runs' requests and responses (R-21): what it holds, one run's entry, the
 * entry as a file, and emptying it.
 *
 * <p>A run the trail does not hold is answered 200 with a line saying why, because "not kept" is
 * an ordinary state of a run, not a fault: the trail holds only the last runs, and an installation
 * can switch it off. Only an id that is not a run id at all is refused.
 */
@RestController
@RequestMapping("/api/trail")
public class TrailController {

    private final RunTrail trail;

    public TrailController(RunTrail trail) {
        this.trail = trail;
    }

    /** What the trail holds. {@code damaged} counts the files found unreadable since the start, and is left out at 0. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"enabled", "count", "maxEntries", "bytes", "folder", "damaged"})
    public record TrailSummary(boolean enabled, int count, int maxEntries, long bytes, String folder, Integer damaged) {

        /** Counts only: the folder can name the tester's own profile. */
        @Override
        public String toString() {
            return "TrailSummary[enabled=" + enabled + ", count=" + count + ", bytes=" + bytes + "]";
        }
    }

    /**
     * One run as the trail holds it: the entry when it is kept, else the line that says why not.
     * The entry holds member data and the wire, so this prints none of it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"runId", "kept", "keptAt", "entry", "line"})
    public record TrailRun(String runId, boolean kept, String keptAt, JsonNode entry, String line) {

        @Override
        public String toString() {
            return "TrailRun[" + runId + ", kept=" + kept + "]";
        }
    }

    public record Removed(int removed) {
    }

    @GetMapping
    public TrailSummary summary() {
        RunTrail.Summary summary = trail.summary();
        return new TrailSummary(summary.enabled(), summary.count(), summary.maxEntries(), summary.bytes(), summary.folder(),
                summary.damaged() > 0 ? Integer.valueOf(summary.damaged()) : null);
    }

    @GetMapping("/{runId}")
    public TrailRun run(@PathVariable("runId") String runId) {
        refuseUnlessRunId(runId);
        RunTrail.Lookup found = trail.find(runId);
        return found.kept() ? new TrailRun(runId, true, RunTrail.keptAtText(found.keptAt()), found.entry(), null)
                : new TrailRun(runId, false, null, null, found.line());
    }

    @GetMapping("/{runId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("runId") String runId) {
        refuseUnlessRunId(runId);
        byte[] bytes = trail.bytes(runId);
        if (bytes == null) {
            throw new NotFoundException(trail.notKeptLine(), "Open the run in Audit to see what the ledger kept.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + runId + ".json").build().toString())
                // The file holds member data and the wire, so no cache between here and the tester keeps a copy.
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }

    @DeleteMapping
    public Removed empty() {
        return new Removed(trail.empty());
    }

    /** The id is never repeated: it came from the address, and only an id this tool mints is ever looked for. */
    private static void refuseUnlessRunId(String runId) {
        if (!RunTrail.isRunId(runId)) {
            throw ValidationException.of("runId", "This is not a run id. Copy the run id from Audit, or from the result that gave it.");
        }
    }
}
