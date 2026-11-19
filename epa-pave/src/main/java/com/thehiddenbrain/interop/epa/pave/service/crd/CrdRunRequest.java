package com.thehiddenbrain.interop.epa.pave.service.crd;

import com.thehiddenbrain.interop.epa.pave.model.CodeInput;
import com.thehiddenbrain.interop.epa.pave.model.MemberDetail;
import com.thehiddenbrain.interop.epa.pave.model.ProviderDetail;
import com.thehiddenbrain.interop.epa.pave.service.config.RequestRef;
import java.util.List;
import java.util.Map;

/**
 * What the CRD tab posts for one run.
 *
 * <p>The member and provider are sent exactly as the form shows them, so what the tester sees is
 * what goes out. The two override lists name the fields the tester typed or changed by hand; the
 * ledger then records that the run used details a fresh lookup would not reproduce.
 *
 * @param hook              order-sign or order-select. Blank means order-sign
 * @param memberOverrides   names of member fields typed or changed by hand
 * @param providerOverrides names of provider fields typed or changed by hand
 * @param dateOfService     ISO date, or blank to send none
 * @param includePrefetch   false to send the request without prefetch. Null means true
 * @param scenarioId        a mock scenario to answer from. Refused on live
 * @param expected          what the tester expects, or blank for none
 * @param request           a specific collection request to send to, or null for the default
 * @param rawBody           a body the tester edited, sent in place of the one the tool would build
 * @param variables         placeholder values typed for this run
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
        Map<String, String> variables) {

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
