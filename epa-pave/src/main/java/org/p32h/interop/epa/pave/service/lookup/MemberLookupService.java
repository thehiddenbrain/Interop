package org.p32h.interop.epa.pave.service.lookup;

import java.time.LocalDate;
import java.util.List;
import org.p32h.interop.epa.pave.client.lookup.MemberLookupClient;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Member lookup by ID. One ID, one member: there is no search here and no way to list members.
 *
 * <p>The ID is taken exactly as it was typed. The result carries it as typed and as the active
 * environment's member ID rule sends it in CRD, DTR and PAS (R-10), on the result and in the wire
 * view, so the change is never made out of sight. The lookup itself sends the ID in its own form
 * (R-11), which its wire request shows.
 *
 * <p>Nothing is kept. This class has no cache, no recent list and no history, on purpose: on the
 * live profile what comes back is a real member, and it goes to the screen and nowhere else.
 */
@Service
public class MemberLookupService implements MemberLookup {

    private final MemberLookupClient client;
    private final ObjectProvider<OperationGateway> gateway;

    @Autowired
    public MemberLookupService(MemberLookupClient client, ObjectProvider<OperationGateway> gateway) {
        this.client = client;
        this.gateway = gateway;
    }

    /** With no gateway: the forms follow the packed rule. */
    public MemberLookupService(MemberLookupClient client) {
        this(client, null);
    }

    @Override
    public MemberLookupResult lookup(String typedMemberId) {
        return lookup(typedMemberId, (LocalDate) null);
    }

    /** As {@link #lookup(String)}, with the date of service as the request gives it: ISO, or blank for none. */
    public MemberLookupResult lookup(String typedMemberId, String dateOfService) {
        return lookup(typedMemberId, LookupDate.parse(dateOfService));
    }

    private MemberLookupResult lookup(String typedMemberId, LocalDate dateOfService) {
        if (Texts.blank(MemberIds.noSpace(typedMemberId))) {
            throw new ValidationException("There is no member ID to look up. Nothing was sent.",
                    List.of(new FieldError("memberId", "Enter a member ID. Nothing was looked up.")));
        }
        MemberIdForms forms = MemberIds.forms(typedMemberId, rule());
        MemberLookupResult result = client.lookup(forms, dateOfService);
        if (result == null) {
            throw new IllegalStateException("The member lookup client returned no result.");
        }
        // Set here whatever the client did, so no client, present or future, can return a result that hides what was typed.
        WireView wire = result.wire() == null ? null : new WireView(result.wire().request(), result.wire().response(), forms);
        return new MemberLookupResult(result.status(), forms, result.member(), result.attention(), result.fields(),
                result.additionalFields(), wire, result.error(), result.message(), result.maskedReading());
    }

    /** The active environment's rule, so the forms on the card are the ones its requests will send. */
    private MemberIdRule rule() {
        OperationGateway active = gateway == null ? null : gateway.getIfAvailable();
        MemberIdRule rule = active == null ? null : active.memberIdRule();
        return rule == null ? MemberIdRule.PACKED : rule;
    }
}
