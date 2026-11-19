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
 * What the DTR tab posts to have the QuestionnaireResponse put together from the answers given.
 *
 * <p>This step sends nothing to the service, so it has no scenario, no collection request, no body
 * of the tester's own and no placeholder values. Those four are not fields of this request at all:
 * a browser that posts one of them is told the field is not known, where declaring them would
 * mean accepting a value and then ignoring it.
 *
 * @param questionnaire         the Questionnaire resource as the package step returned it, as JSON text
 * @param questionnaireResponse a QuestionnaireResponse to start from, as JSON text: the one the
 *                              package arrived with, or for an adaptive questionnaire the one the
 *                              last next-question step returned. Blank to start from nothing
 * @param answers               the answers given. Each replaces whatever the response to start
 *                              from holds for the same question
 */
public record DtrResponseRequest(
        MemberDetail member,
        List<String> memberOverrides,
        ProviderDetail provider,
        List<String> providerOverrides,
        List<CodeInput> procedureCodes,
        String dateOfService,
        String questionnaire,
        String questionnaireResponse,
        List<DtrAnswerInput> answers,
        String expected,
        String testerRef,
        String note,
        String chainId,
        Map<String, List<String>> carry,
        Long configEpoch,
        @JsonAnySetter Map<String, Object> unrecognised) implements RunRequest {

    public DtrResponseRequest {
        memberOverrides = memberOverrides == null ? List.of() : List.copyOf(memberOverrides);
        providerOverrides = providerOverrides == null ? List.of() : List.copyOf(providerOverrides);
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        answers = answers == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(answers));
        carry = carry == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unrecognised));
    }

    /** Nothing is sent, so there is no mock scenario to answer from. */
    @Override
    public String scenarioId() {
        return null;
    }

    /** Nothing is sent, so there is no collection request to send to. */
    @Override
    public RequestRef request() {
        return null;
    }

    /** Nothing is sent, so there is no body of the tester's own. */
    @Override
    public String rawBody() {
        return null;
    }

    /** Nothing is sent, so there are no placeholders to give values for. */
    @Override
    public Map<String, String> variables() {
        return Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because it carries member data and answers. */
    @Override
    public String toString() {
        return "DtrResponseRequest[answers=" + answers.size() + "]";
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
        private String expected;
        private String testerRef;
        private String note;
        private String chainId;
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

        public Builder carry(Map<String, List<String>> value) {
            this.carry = value;
            return this;
        }

        public Builder configEpoch(Long value) {
            this.configEpoch = value;
            return this;
        }

        public DtrResponseRequest build() {
            return new DtrResponseRequest(member, memberOverrides, provider, providerOverrides, procedureCodes, dateOfService,
                    questionnaire, questionnaireResponse, answers, expected, testerRef, note, chainId, carry, configEpoch, Map.of());
        }
    }
}
