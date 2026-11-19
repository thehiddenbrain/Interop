package org.p32h.interop.epa.pave.service.pas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the PAS tab posts to ask for the status of a submitted request.
 *
 * <p>The fields every protocol shares are described on {@link RunRequest}. An inquiry adds one,
 * and uses the procedure codes differently: they are optional, and when given they are the codes
 * of the submission in the order it sent them, so each decision can be tied to its code.
 *
 * @param claimId the identifier of the claim to ask about, or blank to use the one the submission
 *                handed on in {@code carry}
 */
public record PasInquireRequest(
        String claimId,
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        String dateOfService,
        String scenarioId,
        String expected,
        String testerRef,
        String note,
        String chainId,
        RequestRef request,
        String rawBody,
        Map<String, String> variables,
        Map<String, List<String>> carry,
        Long configEpoch,
        @JsonAnySetter Map<String, Object> unrecognised) implements RunRequest {

    public PasInquireRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "PasInquireRequest[codes=" + procedureCodes.size() + "]";
    }

    public static final class Builder {
        private String claimId;
        private MemberDetail member;
        private List<String> memberOverrides = List.of();
        private ProviderDetail provider;
        private List<String> providerOverrides = List.of();
        private List<CodeInput> procedureCodes = List.of();
        private String dateOfService;
        private String scenarioId;
        private String expected;
        private String testerRef;
        private String note;
        private String chainId;
        private RequestRef request;
        private String rawBody;
        private Map<String, String> variables = Map.of();
        private Map<String, List<String>> carry = Map.of();
        private Long configEpoch;

        private Builder() {
        }

        public Builder claimId(String value) {
            this.claimId = value;
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

        public Builder carry(Map<String, List<String>> value) {
            this.carry = value;
            return this;
        }

        public Builder configEpoch(Long value) {
            this.configEpoch = value;
            return this;
        }

        public PasInquireRequest build() {
            return new PasInquireRequest(claimId, member, memberOverrides, provider, providerOverrides, procedureCodes, dateOfService,
                    scenarioId, expected, testerRef, note, chainId, request, rawBody, variables, carry, configEpoch, Map.of());
        }
    }
}
