package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.pas.PasOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Service card's code lists and the attachment kinds and limits. A controller of its
 * own, so the screens can read the lists without the PAS services, and the lists stay one source
 * for the page and for the request.
 */
@RestController
public class PasOptionsController {

    @GetMapping("/api/pas/options")
    public PasOptions options() {
        return PasOptions.CURRENT;
    }
}
