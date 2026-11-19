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
 * shows each state differently. Only an identifier that is refused before any lookup, such as a
 * blank member ID or an NPI whose check digit is wrong, answers 422, from the shared error shape.
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

    private final MemberLookup members;
    private final ProviderLookup providers;

    public LookupController(MemberLookup members, ProviderLookup providers) {
        this.members = members;
        this.providers = providers;
    }

    @GetMapping("/members/{memberId}")
    public MemberLookupResult member(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        requireWholeIdentifier(request, "memberId");
        return members.lookup(memberId);
    }

    @GetMapping("/providers/{npi}")
    public ProviderLookupResult provider(@PathVariable("npi") String npi, HttpServletRequest request) {
        requireWholeIdentifier(request, "npi");
        return providers.lookup(npi);
    }

    /**
     * Refuses a request whose path holds a semicolon that is not percent-encoded.
     *
     * <p>The framework reads what follows such a semicolon as a parameter of the path and takes it
     * off before the identifier reaches a handler. The lookup would then be made for a shorter
     * identifier than the one that was typed, the result would name the shorter one as "typed",
     * and nothing would show that anything had been cut. A semicolon that belongs to an identifier
     * arrives as {@code %3B} and is not affected.
     */
    private static void requireWholeIdentifier(HttpServletRequest request, String field) {
        String path = request.getRequestURI();
        if (path != null && path.indexOf(';') >= 0) {
            throw new ValidationException("This lookup cannot be made as it was addressed. Nothing was sent.",
                    List.of(new FieldError(field, "The address holds a semicolon that is not percent-encoded, and everything after it "
                            + "would be cut off the identifier. Percent-encode the identifier and ask again.")));
        }
    }
}
