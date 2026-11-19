package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import java.util.List;

/**
 * What a CRD request would be, before it is sent: where it would go and the body the tool would
 * build. A tester can read it, or take the body as a starting point and edit it.
 *
 * @param url         the url template beside what it resolved to
 * @param requestName the collection request the target came from, or null
 * @param scripted    true when that request carries a pre-request script this tool will not run
 * @param body        the request body as JSON
 * @param notes       what was left out of the body, or worked out for it, and why
 * @param memberId    the member ID as typed and as it is sent
 */
public record CrdPreview(EpaOperation operation, String method, ResolvedUrl url, EnvironmentRef environment, String requestName,
                         boolean scripted, String body, List<String> notes, MemberIdForms memberId) {

    /** Redacted, because the body carries member data. */
    @Override
    public String toString() {
        return "CrdPreview[" + operation + "]";
    }
}
