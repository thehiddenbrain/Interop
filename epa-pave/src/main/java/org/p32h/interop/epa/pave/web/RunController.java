package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.ReplayReport;
import org.p32h.interop.epa.pave.service.ledger.RunCsv;
import org.p32h.interop.epa.pave.service.ledger.RunIds;
import org.p32h.interop.epa.pave.service.ledger.RunReplay;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The test run ledger over HTTP: the history list, one record, a note on a record, the CSV for
 * sign-off, replay, and whether tracking is alive.
 *
 * <p>A note or a replay whose run could not be written still answers 200. What the ledger did is
 * in the receipt, exactly as it is on the result of a run, so the screen has one way to learn that
 * something was not recorded and one place to say so.
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    /** A note is a remark about a run. Much longer than this and it is something else, pasted in: most likely a request or a response. */
    static final int LONGEST_NOTE = 4000;
    static final int LONGEST_TESTER_REF = 200;

    private static final String OPERATIONS = "one of " + String.join(", ", Arrays.stream(EpaOperation.values()).map(Enum::name).toList());

    private final RunLedger ledger;
    private final RunReplay replay;

    public RunController(RunLedger ledger, RunReplay replay) {
        this.ledger = ledger;
        this.replay = replay;
    }

    /**
     * What a query answers.
     *
     * @param count     how many runs are in this answer
     * @param truncated true when a limit was given and the ledger holds more matching runs than
     *                  it. A list cut short says so, so it is never read as the whole history
     * @param limit     the limit that was applied, or left out when there was none
     * @param runs      the runs, newest first, each with its latest note
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunList(int count, boolean truncated, Integer limit, List<RunRecord> runs) {
    }

    /**
     * A note on a run. A value that is left out is not changed. A value that is given and blank is cleared.
     */
    public record NoteRequest(String note, String testerRef) {

        /** A note is free text a tester typed, so it is not printed. */
        @Override
        public String toString() {
            return "NoteRequest[note " + (note == null ? "not given" : "given") + ", testerRef " + (testerRef == null ? "not given" : "given") + "]";
        }
    }

    /**
     * What adding a note answers.
     *
     * @param receipt what the ledger did. When {@code recorded} is false the note was not kept and the message says why
     * @param run     the run as the ledger now holds it: with the note when it was kept, without it when it was not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoteResult(LedgerReceipt receipt, RunRecord run) {
    }

    /** What to run again: a date range, run ids, or both. With both, the ids are the selection and each must lie in the range. */
    public record ReplayRequest(String from, String to, List<String> runIds) {
    }

    @GetMapping
    public RunList query(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         @RequestParam(required = false) String protocol, @RequestParam(required = false) String operation,
                         @RequestParam(required = false) String result, @RequestParam(required = false) String outcome,
                         @RequestParam(required = false) String chainId, @RequestParam(required = false) String suiteRunId,
                         @RequestParam(required = false) String limit) {
        List<FieldError> errors = new ArrayList<>();
        LocalDate first = read(errors, () -> Params.date("from", from));
        LocalDate last = read(errors, () -> Params.date("to", to));
        Protocol onlyProtocol = read(errors, () -> Params.optional("protocol", protocol, Protocol::parse,
                "one of crd, dtr, pas, subscription, lookup or raw"));
        EpaOperation onlyOperation = read(errors, () -> Params.optional("operation", operation, EpaOperation::parse, OPERATIONS));
        TestResult onlyResult = read(errors, () -> Params.optional("result", result, TestResult::parse, "pass, fail or unverified"));
        Integer most = read(errors, () -> Params.integer("limit", limit));
        if (most != null && most < 0) {
            errors.add(new FieldError("limit", "The limit must be zero, which means no limit, or more."));
        }
        inOrder(errors, first, last);
        refuse(errors);

        boolean limited = most != null && most > 0;
        // One run more than was asked for is read, so the answer can say whether the list was cut short.
        Integer toRead = null;
        if (limited) {
            toRead = most == Integer.MAX_VALUE ? most : most + 1;
        }
        // A parameter that is there and blank, as a form sends an empty field, is not a filter.
        List<RunRecord> runs = ledger.query(RunQuery.builder().from(first).to(last).protocol(onlyProtocol).operation(onlyOperation)
                .result(onlyResult).outcome(Texts.trimToNull(outcome)).chainId(Texts.trimToNull(chainId))
                .suiteRunId(Texts.trimToNull(suiteRunId)).limit(toRead).build());
        boolean truncated = limited && runs.size() > most;
        List<RunRecord> shown = truncated ? List.copyOf(runs.subList(0, most)) : runs;
        return new RunList(shown.size(), truncated, limited ? most : null, shown);
    }

    @GetMapping("/health")
    public LedgerHealth health() {
        return ledger.health();
    }

    /**
     * The runs of a date range as CSV, newest first as the history list shows them. With no dates
     * it is every run in the ledger. See {@link RunCsv} for the columns, the quoting and how a
     * cell is kept from being run as a formula.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        List<FieldError> errors = new ArrayList<>();
        LocalDate first = read(errors, () -> Params.date("from", from));
        LocalDate last = read(errors, () -> Params.date("to", to));
        inOrder(errors, first, last);
        refuse(errors);

        byte[] csv = RunCsv.bytes(ledger.query(RunQuery.between(first, last)));
        String name = "pave-runs" + (first == null && last == null ? "-all" : (first == null ? "" : "-from-" + first)
                + (last == null ? "" : "-to-" + last)) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                // The file can hold member IDs when they are recorded, so no cache between here and the tester keeps a copy.
                .cacheControl(CacheControl.noStore())
                .body(csv);
    }

    @PostMapping("/replay")
    public ReplayReport replay(@RequestBody ReplayRequest request) {
        List<FieldError> errors = new ArrayList<>();
        LocalDate first = read(errors, () -> Params.date("from", request.from()));
        LocalDate last = read(errors, () -> Params.date("to", request.to()));
        refuse(errors);
        return replay.replay(first, last, request.runIds());
    }

    @GetMapping("/{runId}")
    public RunRecord one(@PathVariable String runId) {
        return ledger.find(runId).orElseThrow(() -> RunIds.notFound(runId));
    }

    @PostMapping("/{runId}/note")
    public NoteResult note(@PathVariable String runId, @RequestBody NoteRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (request.note() == null && request.testerRef() == null) {
            errors.add(new FieldError("note", "Give a note, a tester reference or both. Nothing was changed."));
        }
        if (request.note() != null && request.note().length() > LONGEST_NOTE) {
            errors.add(new FieldError("note", "A note can be at most " + LONGEST_NOTE + " characters, and this one has "
                    + request.note().length() + ". A note is kept as written, so do not paste a request or a response into it."));
        }
        if (request.testerRef() != null && request.testerRef().length() > LONGEST_TESTER_REF) {
            errors.add(new FieldError("testerRef", "A tester reference can be at most " + LONGEST_TESTER_REF + " characters."));
        }
        refuse(errors);
        LedgerReceipt receipt = ledger.addNote(runId, request.note(), request.testerRef());
        return new NoteResult(receipt, receipt.record());
    }

    /** Reads one parameter, keeping its problem for later so a tester sees every bad value at once and not one per attempt. */
    private static <T> T read(List<FieldError> errors, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (ValidationException e) {
            errors.addAll(e.fieldErrors());
            return null;
        }
    }

    private static void inOrder(List<FieldError> errors, LocalDate first, LocalDate last) {
        if (first != null && last != null && first.isAfter(last)) {
            errors.add(new FieldError("from", "The from date is after the to date."));
        }
    }

    private static void refuse(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException("The request has " + errors.size() + " problem(s). Nothing was done.", errors);
        }
    }
}
