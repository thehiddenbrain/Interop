package org.p32h.interop.epa.pave.service.crd;

import com.fasterxml.jackson.annotation.JsonAnySetter;
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
 * @param diagnosisCodes  the Diagnosis codes of the Service card. Sent only where the collection's
 *                        sample puts a diagnosis on its order (D-310); CRD itself requires none
 */
public record CrdRunRequest(
        String hook,
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        List<CodeInput> diagnosisCodes,
        String dateOfService,
        Boolean includePrefetch,
        String scenarioId,
        String expected,
        String testerRef,
        String note,
        String chainId,
        RequestRef request,
        String rawBody,
        Boolean asWritten,
        Map<String, String> variables,
        Map<String, List<String>> carry,
        Long configEpoch,
        @JsonAnySetter Map<String, Object> unrecognised) implements RunRequest {

    public static final String ORDER_SIGN = "order-sign";
    public static final String ORDER_SELECT = "order-select";

    public CrdRunRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        diagnosisCodes = diagnosisCodes == null ? List.of() : List.copyOf(diagnosisCodes);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * This request with the collection's body as written in place of a body of its own (D-313), so the
     * run goes down the path such a body takes. Every other field is kept.
     */
    public CrdRunRequest withRawBody(String body) {
        return new CrdRunRequest(hook, member, memberOverrides, provider, providerOverrides, procedureCodes, diagnosisCodes, dateOfService,
                includePrefetch, scenarioId, expected, testerRef, note, chainId, request, body, asWritten, variables, carry, configEpoch,
                unrecognised);
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
        private List<CodeInput> diagnosisCodes = List.of();
        private String dateOfService;
        private Boolean includePrefetch;
        private String scenarioId;
        private String expected;
        private String testerRef;
        private String note;
        private String chainId;
        private RequestRef request;
        private String rawBody;
        private Boolean asWritten;
        private Map<String, String> variables = Map.of();
        private Map<String, List<String>> carry = Map.of();
        private Long configEpoch;

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

        public Builder diagnosisCodes(List<CodeInput> value) {
            this.diagnosisCodes = value;
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

        public Builder asWritten(Boolean value) {
            this.asWritten = value;
            return this;
        }

        public Builder variables(Map<String, String> value) {
            this.variables = value;
            return this;
        }

        public Builder carry(Map<String, List<String>> value) {
            this.carry = value;
            return this;
        }

        public Builder configEpoch(Long value) {
            this.configEpoch = value;
            return this;
        }

        public CrdRunRequest build() {
            return new CrdRunRequest(hook, member, memberOverrides, provider, providerOverrides, procedureCodes, diagnosisCodes,
                    dateOfService, includePrefetch, scenarioId, expected, testerRef, note, chainId, request, rawBody, asWritten, variables, carry, configEpoch,
                    Map.of());
        }
    }
}
