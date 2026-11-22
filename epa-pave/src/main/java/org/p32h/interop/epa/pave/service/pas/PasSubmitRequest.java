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
 * What the PAS tab posts to submit one prior authorization request.
 *
 * <p>The fields every protocol shares are described on {@link RunRequest}. A submission adds the
 * claim id, diagnoses and documents, and the Service card's details, each sent where the PAS guide in force has a place for it.
 * Each of those details is optional and taken as typed: a value left out stays not stated, and the
 * request takes it from the environment's values or says it is missing, never from a guess here.
 *
 * @param claimId           the identifier to put on the claim, or blank. When it is blank the one an
 *                          earlier step handed on is used, and failing that the tool mints one. It is
 *                          how an inquiry and a notification are matched to this submission
 * @param diagnosisCodes    the diagnoses the request is for. Optional
 * @param attachments       the documents the tester adds. Optional
 * @param placeOfService    the CMS place of service code, such as 11. Optional
 * @param levelOfService    the X12 1338 level of service code, such as U. Optional
 * @param certificationType the X12 1322 certification type code, such as I. Optional
 * @param requestType       the X12 1525 request category code, such as HS. Optional
 * @param serviceType       the X12 1365 service type code, such as 1. Optional
 * @param servicingProvider who will perform the service, when another than the requesting provider. Optional
 */
public record PasSubmitRequest(
        String claimId,
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        List<CodeInput> diagnosisCodes,
        List<PasAttachment> attachments,
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
        String placeOfService,
        String levelOfService,
        String certificationType,
        String requestType,
        String serviceType,
        ProviderDetail servicingProvider,
        @JsonAnySetter Map<String, Object> unrecognised) implements RunRequest {

    public PasSubmitRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        diagnosisCodes = diagnosisCodes == null ? List.of() : List.copyOf(diagnosisCodes);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data, documents and the answers a tester gave. */
    @Override
    public String toString() {
        return "PasSubmitRequest[codes=" + procedureCodes.size() + ", attachments=" + attachments.size() + "]";
    }

    public static final class Builder {
        private String claimId;
        private MemberDetail member;
        private List<String> memberOverrides = List.of();
        private ProviderDetail provider;
        private List<String> providerOverrides = List.of();
        private List<CodeInput> procedureCodes = List.of();
        private List<CodeInput> diagnosisCodes = List.of();
        private List<PasAttachment> attachments = List.of();
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
        private String placeOfService;
        private String levelOfService;
        private String certificationType;
        private String requestType;
        private String serviceType;
        private ProviderDetail servicingProvider;

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

        public Builder diagnosisCodes(List<CodeInput> value) {
            this.diagnosisCodes = value;
            return this;
        }

        public Builder attachments(List<PasAttachment> value) {
            this.attachments = value;
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

        public Builder placeOfService(String value) {
            this.placeOfService = value;
            return this;
        }

        public Builder levelOfService(String value) {
            this.levelOfService = value;
            return this;
        }

        public Builder certificationType(String value) {
            this.certificationType = value;
            return this;
        }

        public Builder requestType(String value) {
            this.requestType = value;
            return this;
        }

        public Builder serviceType(String value) {
            this.serviceType = value;
            return this;
        }

        public Builder servicingProvider(ProviderDetail value) {
            this.servicingProvider = value;
            return this;
        }

        public PasSubmitRequest build() {
            return new PasSubmitRequest(claimId, member, memberOverrides, provider, providerOverrides, procedureCodes, diagnosisCodes,
                    attachments, dateOfService, scenarioId, expected, testerRef, note, chainId, request, rawBody, variables, carry,
                    configEpoch, placeOfService, levelOfService, certificationType, requestType, serviceType, servicingProvider, Map.of());
        }
    }
}
