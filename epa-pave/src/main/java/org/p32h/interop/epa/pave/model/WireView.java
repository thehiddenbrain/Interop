package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What went over the wire for one call. Shown on screen, collapsed by default. Never written to
 * the ledger and never logged: it carries member name, date of birth and ID by its nature.
 *
 * @param memberId the member ID as typed and as sent, when the call carried one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WireView(WireRequest request, WireResponse response, MemberIdForms memberId) {

    @Override
    public String toString() {
        return "WireView[redacted]";
    }
}
