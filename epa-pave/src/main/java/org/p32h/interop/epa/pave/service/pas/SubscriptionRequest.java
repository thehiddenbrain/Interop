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
 * What the PAS tab posts for one subscription call: create, read, list, update or delete.
 *
 * <p>The fields every protocol shares are described on {@link RunRequest}. A subscription call has
 * no member, no provider and no procedure codes, so those are not fields of this request at all: a
 * posted {@code member} is a field this tool does not know here, and is refused like any other.
 *
 * <p>What the subscription is subscribed to, how much a notification carries and how often a
 * heartbeat is wanted are not fields either. They are placeholder values, read from the values
 * typed for the run and from the active environment, because what they should be for the live
 * service is not known. {@link SubscriptionRequestBuilder} names each one.
 *
 * @param subscriptionId the id of the subscription a read, an update or a delete is about. Blank
 *                       for a create, where the service assigns the id, and for a list. The
 *                       controller takes it from the address, never from the browser's own body
 */
public record SubscriptionRequest(
        String subscriptionId,
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

    public SubscriptionRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        carry = carry == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(carry));
        unrecognised = unrecognised == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(unrecognised));
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

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.subscriptionId = subscriptionId;
        b.scenarioId = scenarioId;
        b.expected = expected;
        b.testerRef = testerRef;
        b.note = note;
        b.chainId = chainId;
        b.request = request;
        b.rawBody = rawBody;
        b.variables = variables;
        b.carry = carry;
        b.configEpoch = configEpoch;
        b.unrecognised = unrecognised;
        return b;
    }

    /** Prints the names of what it holds and never the values: a body the tester supplied names the url of a receiver. */
    @Override
    public String toString() {
        return "SubscriptionRequest[variables=" + variables.keySet() + ", carry=" + carry.keySet() + "]";
    }

    public static final class Builder {
        private String subscriptionId;
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
        private Map<String, Object> unrecognised = Map.of();

        private Builder() {
        }

        public Builder subscriptionId(String value) {
            this.subscriptionId = value;
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

        /**
         * What was posted that this tool does not know. A read and a delete arrive as request
         * parameters, not as a body, and a parameter nobody knows is refused exactly as an unknown
         * field of a body is.
         */
        public Builder unrecognised(Map<String, Object> value) {
            this.unrecognised = value;
            return this;
        }

        public SubscriptionRequest build() {
            return new SubscriptionRequest(subscriptionId, scenarioId, expected, testerRef, note, chainId, request, rawBody, variables, carry,
                    configEpoch, unrecognised);
        }
    }
}
