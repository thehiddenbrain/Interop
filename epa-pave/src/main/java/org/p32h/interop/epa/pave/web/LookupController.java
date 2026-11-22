package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.lookup.MemberLookupService;
import org.p32h.interop.epa.pave.service.lookup.ProviderLookupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member lookup by member ID and provider lookup by NPI, each optionally with the case's date of
 * service ({@code dateOfService}, ISO), which a lookup saved on screen may send in its body (R-11).
 * Without it nothing is sent for the date: it is never guessed.
 *
 * <p>A lookup that was really asked always answers 200, with how it ended in the body: found, not
 * found, disabled and fault alike. A member who does not exist is a normal answer, and the browser
 * shows each state differently. Only a request that is refused before any lookup answers 422, from
 * the shared error shape: a blank member ID or an NPI whose check digit is wrong.
 *
 * <p>There is one identifier in and one record out. Neither endpoint takes a name or a pattern,
 * and there is no endpoint that lists, so this tool cannot be used to enumerate members.
 *
 * <p>The identifier is the last segment of the path, or the query parameter of the same name, so a
 * caller must percent-encode it. A member
 * ID written with spaces arrives intact that way, and the result shows it as typed. An address
 * that holds a semicolon not percent-encoded never reaches a lookup: {@link RequestShapeFilter}
 * refuses it for every address, because the framework would cut the identifier there and the
 * result would name the shorter one as typed. {@link ApiGuardFilter} keeps other web pages away.
 */
@RestController
@RequestMapping("/api")
public class LookupController {

    private final MemberLookupService members;
    private final ProviderLookupService providers;

    public LookupController(MemberLookupService members, ProviderLookupService providers) {
        this.members = members;
        this.providers = providers;
    }

    @GetMapping("/members/{memberId}")
    public MemberLookupResult member(@PathVariable("memberId") String memberId,
                                     @RequestParam(name = "dateOfService", required = false) String dateOfService) {
        return members.lookup(memberId, dateOfService);
    }

    @GetMapping("/providers/{npi}")
    public ProviderLookupResult provider(@PathVariable("npi") String npi,
                                         @RequestParam(name = "dateOfService", required = false) String dateOfService) {
        return providers.lookup(npi, dateOfService);
    }

    /**
     * The same member lookup with the identifier in the query: {@code /api/members?memberId=...}.
     * The server refuses an encoded slash or backslash in a path with a bare 400 before any
     * controller runs, so an identifier that holds one can be looked up only this way. Still one
     * identifier and one record: without it the answer is the same 422 as for a blank one.
     */
    @GetMapping("/members")
    public MemberLookupResult memberByQuery(@RequestParam(name = "memberId", required = false) String memberId,
                                            @RequestParam(name = "dateOfService", required = false) String dateOfService) {
        return members.lookup(memberId == null ? "" : memberId, dateOfService);
    }

    /** The same provider lookup with the NPI in the query: {@code /api/providers?npi=...}. */
    @GetMapping("/providers")
    public ProviderLookupResult providerByQuery(@RequestParam(name = "npi", required = false) String npi,
                                                @RequestParam(name = "dateOfService", required = false) String dateOfService) {
        return providers.lookup(npi == null ? "" : npi, dateOfService);
    }
}
