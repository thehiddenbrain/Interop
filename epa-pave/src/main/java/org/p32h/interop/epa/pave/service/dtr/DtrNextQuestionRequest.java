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
 * What the DTR tab posts for one step of the adaptive loop.
 *
 * <p>The tool keeps no session between steps, so the request carries everything the step starts
 * from: the questionnaire being answered, the QuestionnaireResponse so far, and the answers the
 * tester is adding. The fields every protocol shares are described on {@link RunRequest}. The
 * member, the provider and the codes are not sent by this step. They are taken so the run is
 * recorded against the same case as the steps before it.
 *
 * @param questionnaire         the Questionnaire resource as the package step returned it, as JSON
 *                              text. Needed for the first step only
 * @param questionnaireResponse the QuestionnaireResponse as the previous step returned it, as JSON
 *                              text. Blank for the first step
 * @param answers               the answers to add. An answer to a question already answered
 *                              replaces the earlier one, which is how an earlier answer is changed
 * @param wrapInParameter       blank to send the QuestionnaireResponse by itself. Otherwise the name
 *                              of the parameter to send it under, inside a Parameters resource, for
 *                              a service that takes next-question that way
 */
public record DtrNextQuestionRequest(
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        String dateOfService,
        String questionnaire,
        String questionnaireResponse,
        List<DtrAnswerInput> answers,
        String wrapInParameter,
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

    public DtrNextQuestionRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        answers = answers == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(answers));
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data and answers. */
    @Override
    public String toString() {
        return "DtrNextQuestionRequest[answers=" + answers.size() + "]";
    }

    public static final class Builder {
        private MemberDetail member;
        private List<String> memberOverrides = List.of();
        private ProviderDetail provider;
        private List<String> providerOverrides = List.of();
        private List<CodeInput> procedureCodes = List.of();
        private String dateOfService;
        private String questionnaire;
        private String questionnaireResponse;
        private List<DtrAnswerInput> answers = List.of();
        private String wrapInParameter;
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

        public Builder questionnaire(String value) {
            this.questionnaire = value;
            return this;
        }

        public Builder questionnaireResponse(String value) {
            this.questionnaireResponse = value;
            return this;
        }

        public Builder answers(List<DtrAnswerInput> value) {
            this.answers = value;
            return this;
        }

        public Builder wrapInParameter(String value) {
            this.wrapInParameter = value;
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

        public DtrNextQuestionRequest build() {
            return new DtrNextQuestionRequest(member, memberOverrides, provider, providerOverrides, procedureCodes, dateOfService,
                    questionnaire, questionnaireResponse, answers, wrapInParameter, scenarioId, expected, testerRef, note, chainId, request,
                    rawBody, variables, carry, configEpoch, Map.of());
        }
    }
}
