package org.p32h.interop.epa.pave.service.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import java.util.List;

/**
 * What a request would be, before it is sent: where it would go and the body the tool would
 * build. A tester can read it, or take the body as a starting point and edit it. Every protocol
 * answers its preview in this one shape.
 *
 * <p>Nothing is sent and nothing is recorded for a preview.
 *
 * @param url         the url template beside what it resolved to
 * @param requestName the collection request the target came from, or null
 * @param scripted    true when that request carries a pre-request script this tool will not run
 * @param body        the request body, or null when the tool could not build it
 * @param notes       what was left out of the body, or worked out for it, and why
 * @param memberId    the member ID as typed and as it is sent
 * @param blocked     why sending this would be refused by the production guardrail, or null when
 *                    it would be sent. The target is still shown, so a tester can look before
 *                    deciding whether to unlock
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestPreview(EpaOperation operation, String method, ResolvedUrl url, EnvironmentRef environment, String requestName,
                             boolean scripted, String body, List<String> notes, MemberIdForms memberId, String blocked) {

    public RequestPreview {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static RequestPreview of(EpaOperation operation, PreparedCall call, String body, List<String> notes, MemberIdForms memberId) {
        return new RequestPreview(operation, call.method(), call.url(), call.environment(), call.requestName(), call.scripted(), body,
                notes, memberId, null);
    }

    /**
     * The preview of a write the guardrail would refuse. The body is not built, because building
     * it resolves placeholders against a call the gateway declined to prepare.
     */
    public static RequestPreview blocked(EpaOperation operation, ProdWriteBlockedException refusal, MemberIdForms memberId) {
        return new RequestPreview(operation, refusal.method(), refusal.target(), refusal.environment(), refusal.requestName(), false,
                null, List.of("The body is not shown, because sending this request is blocked."), memberId,
                refusal.getMessage() + (refusal.action() == null ? "" : " " + refusal.action()));
    }

    /** Redacted, because the body carries member data. */
    @Override
    public String toString() {
        return "RequestPreview[" + operation + "]";
    }
}
