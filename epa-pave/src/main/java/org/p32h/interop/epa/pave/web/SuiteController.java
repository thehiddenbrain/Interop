package org.p32h.interop.epa.pave.web;

import java.io.IOException;
import java.util.List;
import org.p32h.interop.epa.pave.model.suite.SuitePreview;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.suite.SuiteFileReader;
import org.p32h.interop.epa.pave.service.suite.SuiteService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Suite runs: read a suite file, run it in the background, follow its progress, export its results.
 *
 * <p>A suite file is uploaded for one run and is not stored. The page reads the file itself and
 * posts its text as JSON (R-06, R-30), so no multipart machinery is on the screen's path; the
 * multipart form stays for curl and scripts. Starting a run answers 202 with the
 * run as it stands, because the cases are still running when the answer goes out. The screen then
 * asks for the run by id until its state is no longer RUNNING.
 */
@RestController
@RequestMapping("/api/suites")
public class SuiteController {

    private final SuiteService service;

    public SuiteController(SuiteService service) {
        this.service = service;
    }

    /** The suite runs this tool holds, newest first, without their rows. */
    public record SuiteRunList(List<SuiteRunView> runs) {
    }

    /**
     * A suite file as the page read it, for a preview or a run. One shape for both, so the page
     * posts the same body to either and a preview never refuses what the run would take.
     *
     * @param fileName    the name the file was chosen under: it says CSV or JSON, and names the suite
     * @param text        the file's text, decoded as UTF-8 by the page
     * @param testerRef   a reference recorded on every case that gives none of its own, or absent
     * @param compareWith the id of an earlier suite run to compare with, or absent
     * @param configEpoch the configuration epoch the screen was drawn under, or absent to not check
     */
    public record SuiteFileRequest(String fileName, String text, String testerRef, String compareWith, Long configEpoch) {

        /** Never the text or the reference: a suite file holds member IDs, and a reference is free text. */
        @Override
        public String toString() {
            return "SuiteFileRequest[]";
        }
    }

    /** What the tool makes of a suite file. Nothing is sent. 200 even when the file has problems: they are the answer. */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuitePreview preview(@RequestParam("file") MultipartFile file) {
        return service.preview(file.getOriginalFilename(), text(file));
    }

    /** As the multipart preview, with the file posted as the text the page read from it. */
    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuitePreview previewText(@RequestBody SuiteFileRequest body) {
        return service.preview(body.fileName(), text(body.text()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SuiteRunView> start(@RequestParam("file") MultipartFile file,
                                              @RequestParam(name = "testerRef", required = false) String testerRef,
                                              @RequestParam(name = "compareWith", required = false) String compareWith,
                                              @RequestParam(name = "configEpoch", required = false) String configEpoch) {
        Long epoch = Params.optional("configEpoch", configEpoch, Long::valueOf, "a whole number");
        SuiteRunView run = service.start(file.getOriginalFilename(), text(file), testerRef, compareWith, epoch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    /** As the multipart start, with the file posted as the text the page read from it. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuiteRunView> startText(@RequestBody SuiteFileRequest body) {
        SuiteRunView run = service.start(body.fileName(), text(body.text()), body.testerRef(), body.compareWith(), body.configEpoch());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping
    public SuiteRunList list() {
        return new SuiteRunList(service.list());
    }

    /** One suite run. Pass rows=false while polling for progress, to leave the rows out. */
    @GetMapping("/{suiteRunId}")
    public SuiteRunView get(@PathVariable("suiteRunId") String suiteRunId,
                            @RequestParam(name = "rows", required = false) String rows) {
        Boolean withRows = Params.optional("rows", rows, SuiteController::yesNo, "true or false");
        return service.get(suiteRunId, withRows == null || withRows);
    }

    @PostMapping("/{suiteRunId}/stop")
    public SuiteRunView stop(@PathVariable("suiteRunId") String suiteRunId) {
        return service.stop(suiteRunId);
    }

    @GetMapping("/{suiteRunId}/results.csv")
    public ResponseEntity<String> results(@PathVariable("suiteRunId") String suiteRunId) {
        String csv = service.resultsCsv(suiteRunId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8));
        // The id is one this tool minted, so it is safe in a file name.
        headers.setContentDisposition(ContentDisposition.attachment().filename("suite-results-" + suiteRunId.strip() + ".csv").build());
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }

    private static Boolean yesNo(String text) {
        return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("not true or false");
        };
    }

    /**
     * The posted text, held to the limit an uploaded file is held to. Measured in UTF-8 bytes, as
     * the file was, so a file refused as multipart is refused as text too.
     */
    private static String text(String text) {
        if (text == null) {
            throw ValidationException.of("text", "The suite file holds no text. Choose it again.");
        }
        return SuiteFileReader.decode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String text(MultipartFile file) {
        try {
            return SuiteFileReader.decode(file.getBytes());
        } catch (IOException e) {
            throw new BadInputException("The uploaded suite file could not be read.", "Choose the file again.");
        }
    }
}
