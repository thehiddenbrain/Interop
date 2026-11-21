package org.p32h.interop.epa.pave.service;

import org.p32h.interop.epa.pave.model.MemberLookupResult;

/** Member lookup by identifier. One ID returns one member; there is no search and no enumeration. */
public interface MemberLookup {

    /**
     * Looks up one member by the ID exactly as the tester typed it. The ID is sent in its no-space
     * form and the result carries both forms.
     *
     * @throws org.p32h.interop.epa.pave.service.error.ValidationException with field
     *         {@code memberId} when the ID is blank once whitespace is removed. Answered with 422
     */
    MemberLookupResult lookup(String typedMemberId);
}
