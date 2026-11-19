package org.p32h.interop.epa.pave.service.cases;

import java.util.List;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunOrigin;

/**
 * One case with its member and provider resolved, ready to be handed to a protocol service.
 *
 * <p>A case is stored as identifiers only. The member's name and date of birth are looked up
 * again each time the case runs, here, and live only as long as the run does.
 *
 * @param member   the member as the lookup returned it, or with the member ID alone when the
 *                 lookup found nothing or could not be made. Null when the case names no member
 * @param provider the same for the provider
 * @param origin   the suite, the case and the replayed run this run belongs to
 */
public record CaseContext(CaseSpec spec, MemberDetail member, ProviderDetail provider, List<CodeInput> codes, RunOrigin origin) {

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "CaseContext[" + spec + "]";
    }
}
