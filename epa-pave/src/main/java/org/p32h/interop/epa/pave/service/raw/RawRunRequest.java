package org.p32h.interop.epa.pave.service.raw;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.run.RunRequest;

/**
 * What is posted to send one collection request as written.
 *
 * <p>A raw request has no member, provider or procedure codes of its own: whatever it sends is in
 * its body. The fields every run shares are described on {@link RunRequest}; here only the request
 * to send, its body, the values for its placeholders and the tester's expectation and notes are used.
 *
 * @param request   the collection request to send. Required
 * @param rawBody   the body to send, as loaded from the collection and edited. Blank sends the body
 *                  saved with the collection request, or none when it has none
 * @param urlValues values for the single-brace {@code {name}} parts of the request's url, as
 *                  {@code /api/config/resolve} takes them. Each is percent-encoded into its place,
 *                  so a value can never change the shape of the url
 */
public record RawRunRequest(
        RequestRef request,
        String rawBody,
        Map<String, String> variables,
        Map<String, String> urlValues,
        String expected,
        String testerRef,
        String note,
        String chainId,
        Long configEpoch,
        @JsonAnySetter Map<String, Object> unrecognised) implements RunRequest {

    public RawRunRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        urlValues = urlValues == null ? Map.of() : Map.copyOf(urlValues);
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    /** A request with only what a test usually sets. */
    public static RawRunRequest of(RequestRef request, String rawBody, Map<String, String> variables, String expected) {
        return new RawRunRequest(request, rawBody, variables, Map.of(), expected, null, null, null, null, Map.of());
    }

    /** The same request with values for the single-brace parts of its url. */
    public RawRunRequest withUrlValues(Map<String, String> values) {
        return new RawRunRequest(request, rawBody, variables, values, expected, testerRef, note, chainId, configEpoch, unrecognised);
    }

    public RawRunRequest withConfigEpoch(Long epoch) {
        return new RawRunRequest(request, rawBody, variables, urlValues, expected, testerRef, note, chainId, epoch, unrecognised);
    }

    @Override
    public MemberDetail member() {
        return null;
    }

    @Override
    public List<String> memberOverrides() {
        return List.of();
    }

    @Override
    public ProviderDetail provider() {
        return null;
    }

    @Override
    public List<String> providerOverrides() {
        return List.of();
    }

    @Override
    public List<CodeInput> procedureCodes() {
        return List.of();
    }

    @Override
    public String dateOfService() {
        return null;
    }

    /** A raw request has no scenarios: the mock answers every one of them the same way. */
    @Override
    public String scenarioId() {
        return null;
    }

    @Override
    public Map<String, List<String>> carry() {
        return Map.of();
    }

    /** Redacted, because the body can hold member data and a variable can hold a secret. */
    @Override
    public String toString() {
        return "RawRunRequest[" + request + "]";
    }
}
