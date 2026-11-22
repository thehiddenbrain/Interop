package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsService;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSaved;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSettingsInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupsView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member and provider lookups saved on screen (R-11, plan 5.3 and 5.5): read for the Members
 * and Providers pages, saved in place of application.yml's, and removed to bring application.yml's
 * back. Exists on both profiles: on mock the settings are kept and shown, and lookups answer from
 * the fixtures whatever they say (R-29).
 *
 * <p>No answer carries a secret: a key, a token or a header value is shown as set or not, with the
 * reference it is when it is wholly one.
 */
@RestController
@RequestMapping("/api/config/lookups")
public class LookupSettingsController {

    private final LookupSettingsService lookups;

    public LookupSettingsController(LookupSettingsService lookups) {
        this.lookups = lookups;
    }

    @GetMapping
    public LookupsView lookups() {
        return lookups.views();
    }

    @PutMapping("/{kind}")
    public LookupSaved save(@PathVariable("kind") String kind, @RequestBody LookupSettingsInput body) {
        return lookups.save(kind(kind), body);
    }

    @DeleteMapping("/{kind}")
    public LookupSaved remove(@PathVariable("kind") String kind) {
        return lookups.remove(kind(kind));
    }

    /** The path names a lookup by word; anything else names nothing here. Not repeated back: it is the caller's text. */
    private static LookupKind kind(String word) {
        LookupKind kind = LookupKind.parse(word);
        if (kind == null) {
            throw new NotFoundException("There is a member lookup and a provider lookup, and no other: use /api/config/lookups/member or "
                    + "/api/config/lookups/provider.");
        }
        return kind;
    }
}
