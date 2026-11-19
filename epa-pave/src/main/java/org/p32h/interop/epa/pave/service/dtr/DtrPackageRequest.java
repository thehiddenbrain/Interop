package org.p32h.interop.epa.pave.service.dtr;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import java.util.List;
import java.util.Map;

/**
 * What the DTR tab posts to fetch a questionnaire package.
 *
 * <p>The fields every protocol shares are described on {@link RunRequest}. A package fetch adds
 * two, both optional. What CRD handed on arrives in {@code carry}, and a field typed here wins
 * over the same thing carried, because what the tester sees on the form is what goes out.
 *
 * @param questionnaires      canonical urls of the questionnaires to ask for. Empty to ask for
 *                            the ones CRD named, or for none, which leaves the choice to the service
 * @param coverageAssertionId the coverage assertion id CRD returned. Blank to use the one carried
 */
public record DtrPackageRequest(
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        String dateOfService,
        List<String> questionnaires,
        String coverageAssertionId,
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

    public DtrPackageRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        questionnaires = questionnaires == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(questionnaires));
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "DtrPackageRequest[codes=" + procedureCodes.size() + ", questionnaires=" + questionnaires.size() + "]";
    }

    public static final class Builder {
        private MemberDetail member;
        private List<String> memberOverrides = List.of();
        private ProviderDetail provider;
        private List<String> providerOverrides = List.of();
        private List<CodeInput> procedureCodes = List.of();
        private String dateOfService;
        private List<String> questionnaires = List.of();
        private String coverageAssertionId;
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

        public Builder questionnaires(List<String> value) {
            this.questionnaires = value;
            return this;
        }

        public Builder coverageAssertionId(String value) {
            this.coverageAssertionId = value;
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

        public DtrPackageRequest build() {
            return new DtrPackageRequest(member, memberOverrides, provider, providerOverrides, procedureCodes, dateOfService,
                    questionnaires, coverageAssertionId, scenarioId, expected, testerRef, note, chainId, request, rawBody, variables,
                    carry, configEpoch, Map.of());
        }
    }
}
