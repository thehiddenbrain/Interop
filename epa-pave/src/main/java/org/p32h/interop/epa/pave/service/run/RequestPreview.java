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
 * @param bodySource  for a collection request sent as written, which body would go with it:
 *                    supplied, saved or none, as {@code RawDetail} names them. Null for every
 *                    other operation, whose body the tool builds
 * @param toComplete  what the request still lacks for the guide it follows, each with the field
 *                    that would close the gap. Null for a preview that does not check this, which
 *                    is every operation but Submit; empty when it checked and found nothing. Nothing
 *                    here blocks sending: an incomplete request is a negative test, said beforehand
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestPreview(EpaOperation operation, String method, ResolvedUrl url, EnvironmentRef environment, String requestName,
                             boolean scripted, String body, List<String> notes, MemberIdForms memberId, String blocked, String bodySource,
                             List<ToComplete> toComplete) {

    /**
     * One gap in a request, said before it is sent.
     *
     * @param field where the tester closes it: a case field such as {@code provider.phone}, or a
     *              value of the environment such as {@code values.payerName}
     * @param line  the sentence the screen shows: what is missing, the rule that wants it, and where to add it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToComplete(String field, String line) {
    }

    public RequestPreview {
        notes = notes == null ? List.of() : List.copyOf(notes);
        // Null stays null, because absent (not checked) and empty (checked, nothing missing) mean different things.
        toComplete = toComplete == null ? null : List.copyOf(toComplete);
    }

    public RequestPreview(EpaOperation operation, String method, ResolvedUrl url, EnvironmentRef environment, String requestName,
                          boolean scripted, String body, List<String> notes, MemberIdForms memberId, String blocked, String bodySource) {
        this(operation, method, url, environment, requestName, scripted, body, notes, memberId, blocked, bodySource, null);
    }

    public RequestPreview(EpaOperation operation, String method, ResolvedUrl url, EnvironmentRef environment, String requestName,
                          boolean scripted, String body, List<String> notes, MemberIdForms memberId, String blocked) {
        this(operation, method, url, environment, requestName, scripted, body, notes, memberId, blocked, null, null);
    }

    /** The same preview, saying which body would go with a collection request sent as written. */
    public RequestPreview withBodySource(String source) {
        return new RequestPreview(operation, method, url, environment, requestName, scripted, body, notes, memberId, blocked, source,
                toComplete);
    }

    /** The same preview, with what the request still lacks. */
    public RequestPreview withToComplete(List<ToComplete> gaps) {
        return new RequestPreview(operation, method, url, environment, requestName, scripted, body, notes, memberId, blocked, bodySource,
                gaps == null ? List.of() : gaps);
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
