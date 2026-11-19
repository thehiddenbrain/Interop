package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the Subscription a create or an update sends.
 *
 * <p>What a subscription has to be subscribed to for the live service, how much a notification
 * should carry and what else the service wants set are not known. So nothing of that is a fixed
 * choice of this tool. Each value is read from a placeholder, which the tester types for the run or
 * keeps in the environment, and where the resource cannot be sent without one the tool sends a
 * default that is a visible placeholder and says so in a note. A subscription created with the
 * placeholder topic is meant to be rejected or to look wrong, never to pass for the right one.
 *
 * <p>The receiver the subscription names is this tool's own, from {@code onyx.subscription.callback-url}.
 * When that is not set the subscription is still built, without an endpoint, and the caller is told.
 */
public final class SubscriptionRequestBuilder {

    /** Placeholder names the subscription reads. Each is optional. */
    public static final String VAR_TOPIC = "subscriptionTopic";
    public static final String VAR_FILTER = "subscriptionFilter";
    public static final String VAR_STATUS = "subscriptionStatus";
    public static final String VAR_REASON = "subscriptionReason";
    public static final String VAR_CHANNEL_TYPE = "subscriptionChannelType";
    public static final String VAR_PAYLOAD_CONTENT = "subscriptionPayloadContent";
    public static final String VAR_HEARTBEAT_SECONDS = "subscriptionHeartbeatSeconds";
    public static final String VAR_END = "subscriptionEnd";

    /** The setting that holds the url of this tool's receiver, as an operator writes it. */
    public static final String CALLBACK_PROPERTY = "onyx.subscription.callback-url";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * The request body and what a tester should know about it.
     *
     * @param json            the Subscription as pretty JSON
     * @param notes           one plain sentence for each thing left out or worked out
     * @param callbackUrl     the url the subscription names as its receiver, or null when it names none
     * @param callbackProblem why it names none, in plain words with what to set, or null when it names one
     */
    public record Built(String json, List<String> notes, String callbackUrl, String callbackProblem) {

        /** The body is what goes on the wire and the receiver's address is the operator's, so neither is printed. */
        @Override
        public String toString() {
            return "Built[notes=" + notes.size() + ", namesReceiver=" + (callbackUrl != null) + "]";
        }
    }

    private final String configuredCallbackUrl;

    /** @param configuredCallbackUrl the value of {@code onyx.subscription.callback-url}, which may be blank or hold placeholders */
    public SubscriptionRequestBuilder(String configuredCallbackUrl) {
        this.configuredCallbackUrl = configuredCallbackUrl;
    }

    /**
     * @param subscriptionId the id of the subscription an update replaces, or null for a create
     * @param variables      resolves text holding placeholders, as a prepared call does
     */
    public Built build(String subscriptionId, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        ObjectNode subscription = JSON.objectNode();
        subscription.put("resourceType", "Subscription");
        if (Texts.present(subscriptionId)) {
            subscription.put("id", subscriptionId.strip());
        }
        subscription.putObject("meta").putArray("profile").add(OnyxPasConventions.SUBSCRIPTION_PROFILE);
        subscription.put("status", chosen(variables, VAR_STATUS, OnyxPasConventions.REQUESTED,
                "status", "which is what a client asks for and leaves the service to activate", notes));
        String end = CaseResources.value(variables, VAR_END);
        if (end != null) {
            subscription.put("end", end);
        }
        subscription.put("reason", chosen(variables, VAR_REASON, OnyxPasConventions.DEFAULT_SUBSCRIPTION_REASON,
                "reason", "which is this tool's own wording", notes));

        String topic = CaseResources.value(variables, VAR_TOPIC);
        if (topic == null) {
            topic = OnyxPasConventions.PLACEHOLDER_TOPIC;
            notes.add("The subscription topic was set to " + topic + ". That is a placeholder and names no real topic: what the service "
                    + "expects here is not known. Set the variable " + VAR_TOPIC + " to the topic the service documents.");
        }
        subscription.put("criteria", topic);
        String filter = CaseResources.value(variables, VAR_FILTER);
        if (filter != null) {
            subscription.putObject("_criteria").putArray("extension").addObject().put("url", OnyxPasConventions.FILTER_CRITERIA)
                    .put("valueString", filter);
        } else {
            notes.add("No filter was sent with the topic: the variable " + VAR_FILTER + " is not set. Whether the service needs one, "
                    + "and what it must say, is not known.");
        }

        ObjectNode channel = subscription.putObject("channel");
        String heartbeat = CaseResources.value(variables, VAR_HEARTBEAT_SECONDS);
        if (heartbeat != null && heartbeat.matches("\\d{1,9}")) {
            channel.putArray("extension").addObject().put("url", OnyxPasConventions.HEARTBEAT_PERIOD).put("valueUnsignedInt", Integer.parseInt(heartbeat));
        } else if (heartbeat != null) {
            notes.add("No heartbeat period was sent: the variable " + VAR_HEARTBEAT_SECONDS + " does not hold a whole number of seconds.");
        } else {
            notes.add("No heartbeat period was sent: the variable " + VAR_HEARTBEAT_SECONDS + " is not set.");
        }
        channel.put("type", chosen(variables, VAR_CHANNEL_TYPE, OnyxPasConventions.DEFAULT_CHANNEL_TYPE, "channel type",
                "which is the kind of channel that calls a receiver back", notes));

        String callbackUrl = null;
        String callbackProblem = null;
        if (Texts.blank(configuredCallbackUrl)) {
            callbackProblem = "The setting " + CALLBACK_PROPERTY + " is blank. Set it to the address at which the service can reach this "
                    + "tool's receiver, which ends in /api/pas/notifications, and send again.";
        } else {
            ResolvedText resolved = variables == null ? ResolvedText.plain(configuredCallbackUrl) : variables.apply(configuredCallbackUrl.strip());
            if (resolved != null && resolved.resolved() && Texts.present(resolved.value())) {
                callbackUrl = resolved.value().strip();
            } else {
                callbackProblem = "The setting " + CALLBACK_PROPERTY + " holds placeholders that have no value"
                        + (resolved == null || resolved.unresolved().isEmpty() ? "" : ": " + String.join(", ", resolved.unresolved()))
                        + ". Give each a value, or write the address out in full, and send again.";
            }
        }
        if (callbackUrl != null) {
            channel.put("endpoint", callbackUrl);
        } else {
            notes.add("No endpoint was sent, so the service has nowhere to call back. " + callbackProblem);
        }
        channel.put("payload", OnyxPasConventions.FHIR_JSON);
        channel.putObject("_payload").putArray("extension").addObject().put("url", OnyxPasConventions.PAYLOAD_CONTENT)
                .put("valueCode", chosen(variables, VAR_PAYLOAD_CONTENT, OnyxPasConventions.DEFAULT_PAYLOAD_CONTENT, "payload content",
                        "which puts the least member data on the wire", notes));
        notes.add("No header was asked for on the notifications. This tool's receiver accepts a notification without one.");
        return new Built(JsonSafe.pretty(subscription), List.copyOf(notes), callbackUrl, callbackProblem);
    }

    /**
     * The receiver a body the tester supplied names, read from {@code channel.endpoint}, or null
     * when the body is not JSON or names none. Looked at only to tell the tester when a
     * subscription has nowhere to call back: the body itself is sent exactly as written.
     */
    public static String endpointOf(String body) {
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        return parsed.ok() ? JsonSafe.presentText(JsonSafe.at(parsed.node(), "channel", "endpoint")) : null;
    }

    /**
     * A value the resource cannot go without and the tester may state as a variable. When they have
     * not, the tool's own default is sent and the note says so, because a default nobody is told
     * about is a guess.
     */
    private static String chosen(Function<String, ResolvedText> variables, String variable, String fallback, String what, String why,
                                 List<String> notes) {
        String stated = CaseResources.value(variables, variable);
        if (stated != null) {
            return stated;
        }
        notes.add("The " + what + " was set to " + fallback + ", " + why + ". It is this tool's default and not something known about "
                + "the service. Set the variable " + variable + " to send another.");
        return fallback;
    }
}
