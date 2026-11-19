package org.p32h.interop.epa.pave.service.lookup;

import org.p32h.interop.epa.pave.client.lookup.MemberLookupClient;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.stereotype.Service;

/**
 * Member lookup by ID. One ID, one member: there is no search here and no way to list members.
 *
 * <p>The ID is taken exactly as it was typed. Some member IDs are written with spaces and the
 * service accepts only the no-space form, so that form is what is sent, and the result always
 * carries both, on the result and in the wire view. The change is never made out of sight.
 *
 * <p>Nothing is kept. This class has no cache, no recent list and no history, on purpose: on the
 * live profile what comes back is a real member, and it goes to the screen and nowhere else.
 */
@Service
public class MemberLookupService implements MemberLookup {

    private final MemberLookupClient client;

    public MemberLookupService(MemberLookupClient client) {
        this.client = client;
    }

    @Override
    public MemberLookupResult lookup(String typedMemberId) {
        MemberIdForms forms = MemberIds.forms(typedMemberId);
        if (Texts.blank(forms.sent())) {
            throw ValidationException.of("memberId", "Enter a member ID. Nothing was looked up.");
        }
        MemberLookupResult result = client.lookup(forms);
        if (result == null) {
            throw new IllegalStateException("The member lookup client returned no result.");
        }
        // Set here whatever the client did, so no client, present or future, can return a result that hides what was typed.
        WireView wire = result.wire() == null ? null : new WireView(result.wire().request(), result.wire().response(), forms);
        return new MemberLookupResult(result.status(), forms, result.member(), result.attention(), result.fields(),
                result.additionalFields(), wire, result.error(), result.message());
    }
}
