package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member lookup by member ID and provider lookup by NPI.
 *
 * <p>A lookup that was really asked always answers 200, with how it ended in the body: found, not
 * found, disabled and fault alike. A member who does not exist is a normal answer, and the browser
 * shows each state differently. Only an identifier that is refused before any lookup, a blank
 * member ID or an NPI whose check digit is wrong, answers 422, from the shared error shape.
 *
 * <p>There is one identifier in and one record out. Neither endpoint takes a name or a pattern,
 * and there is no endpoint that lists, so this tool cannot be used to enumerate members.
 */
@RestController
@RequestMapping("/api")
public class LookupController {

    private final MemberLookup members;
    private final ProviderLookup providers;

    public LookupController(MemberLookup members, ProviderLookup providers) {
        this.members = members;
        this.providers = providers;
    }

    @GetMapping("/members/{memberId}")
    public MemberLookupResult member(@PathVariable("memberId") String memberId) {
        return members.lookup(memberId);
    }

    @GetMapping("/providers/{npi}")
    public ProviderLookupResult provider(@PathVariable("npi") String npi) {
        return providers.lookup(npi);
    }
}
