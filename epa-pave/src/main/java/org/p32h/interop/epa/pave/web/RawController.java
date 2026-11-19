package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.raw.RawDetail;
import org.p32h.interop.epa.pave.service.raw.RawRunRequest;
import org.p32h.interop.epa.pave.service.raw.RawService;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends a collection request as written. The statuses are the ones every run endpoint uses: see
 * {@link RunResponses}.
 */
@RestController
@RequestMapping("/api/raw")
public class RawController {

    private final RawService service;

    public RawController(RawService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RunEnvelope<RawDetail>> send(@RequestBody RawRunRequest request, HttpServletRequest http) {
        return RunResponses.of(service.run(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/preview")
    public RequestPreview preview(@RequestBody RawRunRequest request, HttpServletRequest http) {
        return service.preview(request, SessionUnlock.from(http));
    }
}
