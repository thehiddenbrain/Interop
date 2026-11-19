package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member lookup by member ID and provider lookup by NPI.
 *
 * <p>A lookup that was really asked always answers 200, with how it ended in the body: found, not
 * found, disabled and fault alike. A member who does not exist is a normal answer, and the browser
 * shows each state differently. Only a request that is refused before any lookup answers 422, from
 * the shared error shape: a blank member ID, an NPI whose check digit is wrong, or an address that
 * cannot be taken at its word.
 *
 * <p>There is one identifier in and one record out. Neither endpoint takes a name or a pattern,
 * and there is no endpoint that lists, so this tool cannot be used to enumerate members.
 *
 * <p>The identifier is the last segment of the path, so a caller must percent-encode it. A member
 * ID written with spaces arrives intact that way, and the result shows it as typed.
 */
@RestController
@RequestMapping("/api")
public class LookupController {

    /**
     * How {@link ApiGuardFilter} and {@link ResponseHeadersFilter} know an API request: the address
     * as it was written starts with this. Neither offers the text, so it is repeated here, and it
     * has to change when they change how they decide.
     */
    private static final String API_ADDRESS = "/api/";

    private final MemberLookup members;
    private final ProviderLookup providers;

    public LookupController(MemberLookup members, ProviderLookup providers) {
        this.members = members;
        this.providers = providers;
    }

    @GetMapping("/members/{memberId}")
    public MemberLookupResult member(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        requirePlainAddress(request, "memberId");
        return members.lookup(memberId);
    }

    @GetMapping("/providers/{npi}")
    public ProviderLookupResult provider(@PathVariable("npi") String npi, HttpServletRequest request) {
        requirePlainAddress(request, "npi");
        return providers.lookup(npi);
    }

    /**
     * Refuses a lookup whose address cannot be taken at its word, before anything is looked up.
     *
     * <p><b>An address that is not written as {@code /api/...}.</b> The framework finds this
     * controller for other spellings of its path as well, such as {@code /%61pi/members/...}. The
     * filter that keeps other web pages away from the API, and the one that tells a browser never
     * to store an API answer, both decide what an API request is from the address as it was
     * written, and neither takes such a spelling for one. A lookup answered there would hand a
     * member to a caller nobody checked, in a response a browser may keep. So the test those two
     * filters apply is applied here too, and a lookup is only answered for a request both have seen.
     *
     * <p><b>A semicolon that is not percent-encoded.</b> The framework reads what follows such a
     * semicolon as a parameter of the path and takes it off before the identifier reaches a
     * handler. The lookup would then be made for a shorter identifier than the one that was typed,
     * the result would name the shorter one as "typed", and nothing would show that anything had
     * been cut. A semicolon that belongs to an identifier arrives as {@code %3B} and is not
     * affected.
     */
    private static void requirePlainAddress(HttpServletRequest request, String field) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(API_ADDRESS)) {
            throw refused(field, "The address is not written the way this tool's API is addressed, which is /api/ followed by the "
                    + "lookup and the identifier. Write the path plainly, percent-encode the identifier only, and ask again.");
        }
        if (path.indexOf(';') >= 0) {
            throw refused(field, "The address holds a semicolon that is not percent-encoded, and everything after it would be cut off "
                    + "the identifier. Percent-encode the identifier and ask again.");
        }
    }

    /** The message never repeats the address: it holds the identifier. */
    private static ValidationException refused(String field, String message) {
        return new ValidationException("This lookup cannot be made as it was addressed. Nothing was sent.",
                List.of(new FieldError(field, message)));
    }
}
