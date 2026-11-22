package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.draft.DraftViews.Draft;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts;
import org.p32h.interop.epa.pave.service.draft.EnvironmentDrafts.FileText;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Fill the form from these files" (R-08): the files the page read, posted as text, answered with
 * what the environment form can be filled with. Stores nothing. Any file, however broken, gets
 * 200 and a line about that file (R-07); the only refusal is a request that gives no file at all.
 */
@RestController
@RequestMapping("/api/config/environments")
public class DraftController {

    /**
     * What {@code POST /api/config/environments/draft} takes.
     *
     * @param environmentExport the Postman environment export, or absent
     * @param collectionExport  the Postman collection export, or absent
     * @param forId             the id of the environment being edited, or absent for a new one
     */
    public record DraftRequest(FileText environmentExport, FileText collectionExport, String forId) {

        /** Never the texts: an export holds credentials. */
        @Override
        public String toString() {
            return "DraftRequest[" + (environmentExport == null ? "" : "environment ") + (collectionExport == null ? "" : "collection") + "]";
        }
    }

    private final EnvironmentDrafts drafts;

    public DraftController(EnvironmentDrafts drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/draft")
    public Draft draft(@RequestBody DraftRequest body) {
        if (body == null || (body.environmentExport() == null && body.collectionExport() == null)) {
            throw ValidationException.of(EnvironmentDrafts.ENVIRONMENT_SLOT,
                    "Choose an environment export, a collection export or both, then fill the form from them.");
        }
        return drafts.draft(body.environmentExport(), body.collectionExport(), body.forId());
    }
}
