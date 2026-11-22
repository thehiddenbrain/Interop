package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.lists.CollectionIngest;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Found;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The members, providers and procedure codes an uploaded collection states. The files are read in
 * the page and posted as text, as the environment form posts them; reading stores nothing.
 */
@RestController
@RequestMapping("/api/lists/found")
public class FoundListsController {

    private final CollectionIngest ingest;

    public FoundListsController(CollectionIngest ingest) {
        this.ingest = ingest;
    }

    @PostMapping
    public Found found(@RequestBody FoundRequest request) {
        return ingest.found(request);
    }
}
