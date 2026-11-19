package org.p32h.interop.epa.pave.client.lookup;

import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberLookupResult;

/**
 * The member API boundary. The response contract of that API is not final, so the whole mapping
 * from its response to a member sits in one marked method per implementation.
 *
 * <p>Never throws for an API fault and never returns null: not found, fault and disabled are all
 * statuses on the result. Implementations log the outcome and the HTTP status only, never the
 * identifier and never any field of the response.
 */
public interface MemberLookupClient {

    MemberLookupResult lookup(MemberIdForms memberId);
}
