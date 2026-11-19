package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import java.util.List;
import java.util.Map;

/**
 * What the CRD tab posts for one run.
 *
 * <p>The fields every protocol shares are described on {@link RunRequest}. CRD adds two.
 *
 * @param hook            order-sign or order-select. Blank means order-sign
 * @param includePrefetch false to send the request without prefetch. Null means true
 */
public record CrdRunRequest(
        String hook,
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        String dateOfService,
        Boolean includePrefetch,
        String scenarioId,
        String expected,
        String testerRef,
        String note,
        String chainId,
        RequestRef request,
        String rawBody,
        Map<String, String> variables) implements RunRequest {

    public static final String ORDER_SIGN = "order-sign";
    public static final String ORDER_SELECT = "order-select";

    public CrdRunRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "CrdRunRequest[hook=" + hook + ", codes=" + procedureCodes.size() + "]";
    }

    public static final class Builder {
        private String hook;
        private MemberDetail member;
        private List<String> memberOverrides = List.of();
        private ProviderDetail provider;
        private List<String> providerOverrides = List.of();
        private List<CodeInput> procedureCodes = List.of();
        private String dateOfService;
        private Boolean includePrefetch;
        private String scenarioId;
        private String expected;
        private String testerRef;
        private String note;
        private String chainId;
        private RequestRef request;
        private String rawBody;
        private Map<String, String> variables = Map.of();

        private Builder() {
        }

        public Builder hook(String value) {
            this.hook = value;
            return this;
        }

        public Builder member(MemberDetail value) {
            this.member = value;
            return this;
        }

        public Builder memberOverrides(List<String> value) {
            this.memberOverrides = value;
            return this;
        }

        public Builder provider(ProviderDetail value) {
            this.provider = value;
            return this;
        }

        public Builder providerOverrides(List<String> value) {
            this.providerOverrides = value;
            return this;
        }

        public Builder procedureCodes(List<CodeInput> value) {
            this.procedureCodes = value;
            return this;
        }

        public Builder dateOfService(String value) {
            this.dateOfService = value;
            return this;
        }

        public Builder includePrefetch(Boolean value) {
            this.includePrefetch = value;
            return this;
        }

        public Builder scenarioId(String value) {
            this.scenarioId = value;
            return this;
        }

        public Builder expected(String value) {
            this.expected = value;
            return this;
        }

        public Builder testerRef(String value) {
            this.testerRef = value;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
            return this;
        }

        public Builder chainId(String value) {
            this.chainId = value;
            return this;
        }

        public Builder request(RequestRef value) {
            this.request = value;
            return this;
        }

        public Builder rawBody(String value) {
            this.rawBody = value;
            return this;
        }

        public Builder variables(Map<String, String> value) {
            this.variables = value;
            return this;
        }

        public CrdRunRequest build() {
            return new CrdRunRequest(hook, member, memberOverrides, provider, providerOverrides, procedureCodes, dateOfService,
                    includePrefetch, scenarioId, expected, testerRef, note, chainId, request, rawBody, variables);
        }
    }
}
