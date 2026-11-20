package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the Subscription a create or an update sends, as PAS 2.0.1 describes it: to the topic
 * PAS 2.0.1 defines, filtered by the requesting provider organization, with the R4 backport
 * extensions.
 *
 * <p>Every value can be stated by a placeholder, which the tester types for the run or keeps in the
 * environment. Where the resource cannot go without a value and none was stated, the tool sends the
 * value PAS 2.0.1 gives or its own default and says so in a note, naming what the PAS 2.2.0 ballot
 * fixes instead where the two differ. The requesting organization is a fact nobody but the tester
 * knows, so the filter is built only from what was stated, and its absence is said first.
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
    /** The identifier of the requesting provider organization, which the orgIdentifier filter names, and its system. */
    public static final String VAR_ORG_IDENTIFIER = "pasRequestingOrgIdentifier";
    public static final String VAR_ORG_IDENTIFIER_SYSTEM = "pasRequestingOrgIdentifierSystem";

    /** What a subscription goes out without when nobody names the requesting organization. */
    public static final String FILTER_LEFT_OUT = "the orgIdentifier filter PAS 2.0.1 requires";

    /** The setting that holds the url of this tool's receiver, as an operator writes it. */
    public static final String CALLBACK_PROPERTY = "onyx.subscription.callback-url";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * The request body and what a tester should know about it.
     *
     * @param json            the Subscription as pretty JSON, as it is sent
     * @param notes           one plain sentence for each thing left out or worked out
     * @param callbackUrl     the url the subscription names as its receiver, as it may be shown, or null when it names none
     * @param callbackProblem why it names none, in plain words with what to set, or null when it names one
     * @param leftOut         what PAS 2.0.1 requires that the subscription went out without. Empty when nothing
     * @param shownJson       the Subscription as it may be shown: the same JSON with every secret in the address to call
     *                        back masked ({@link #shownEndpoint}). Equal to {@code json} when the address holds none
     */
    public record Built(String json, List<String> notes, String callbackUrl, String callbackProblem, List<String> leftOut,
                        String shownJson) {

        public Built {
            notes = notes == null ? List.of() : List.copyOf(notes);
            leftOut = leftOut == null ? List.of() : List.copyOf(leftOut);
            shownJson = shownJson == null ? json : shownJson;
        }

        /** The body to hand the client: sent as built, shown with the secrets of the address to call back masked. */
        public ResolvedText body() {
            return new ResolvedText(shownJson, shownJson, json, List.of(), List.of());
        }

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
        List<String> leftOut = new ArrayList<>();
        ObjectNode subscription = JSON.objectNode();
        subscription.put("resourceType", "Subscription");
        if (Texts.present(subscriptionId)) {
            subscription.put("id", subscriptionId.strip());
        }
        subscription.putObject("meta").putArray("profile").add(OnyxPasConventions.SUBSCRIPTION_PROFILE);
        subscription.put("status", chosen(variables, VAR_STATUS, OnyxPasConventions.REQUESTED,
                "status", "which is what a client asks for and leaves the service to activate",
                "PAS 2.0.1 has no Subscription profile; the PAS 2.2.0 ballot fixes the status to active.", notes));
        String end = CaseResources.value(variables, VAR_END);
        if (end != null) {
            subscription.put("end", end);
        }
        subscription.put("reason", chosen(variables, VAR_REASON, OnyxPasConventions.DEFAULT_SUBSCRIPTION_REASON,
                "reason", "which is this tool's own wording", null, notes));

        String topic = CaseResources.value(variables, VAR_TOPIC);
        if (topic == null) {
            topic = OnyxPasConventions.PAS_TOPIC;
            notes.add("The subscription topic was set to " + topic + ", the topic PAS 2.0.1 defines for a prior authorization result. "
                    + "Set the variable " + VAR_TOPIC + " to send another.");
        }
        subscription.put("criteria", topic);
        String filter = CaseResources.value(variables, VAR_FILTER);
        if (filter == null) {
            String organization = CaseResources.value(variables, VAR_ORG_IDENTIFIER);
            if (organization != null) {
                String system = CaseResources.value(variables, VAR_ORG_IDENTIFIER_SYSTEM);
                filter = OnyxPasConventions.orgIdentifierFilter(system, organization);
                notes.add("The subscription is filtered by the requesting organization, as PAS 2.0.1 requires: " + filter + ", written in the "
                        + "form the PAS 2.2.0 ballot's own example uses."
                        + (system == null ? " No identifier system was given (the variable " + VAR_ORG_IDENTIFIER_SYSTEM + " is not set), "
                        + "so the filter names the identifier without one." : ""));
            } else {
                leftOut.add(FILTER_LEFT_OUT);
                notes.add("No filter was sent with the topic: neither " + VAR_ORG_IDENTIFIER + " nor " + VAR_FILTER + " is set. PAS 2.0.1 "
                        + "requires a subscription to be filtered by the requesting provider organization, through the topic's filter "
                        + OnyxPasConventions.ORG_IDENTIFIER_FILTER + ", and the service may refuse it or send notifications about other "
                        + "organizations' requests. Set " + VAR_ORG_IDENTIFIER + " to that organization's identifier, such as its NPI.");
            }
        }
        if (filter != null) {
            subscription.putObject("_criteria").putArray("extension").addObject().put("url", OnyxPasConventions.FILTER_CRITERIA)
                    .put("valueString", filter);
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
                "which is the kind of channel that calls a receiver back and the one PAS 2.0.1 says a server should support", null,
                notes));

        String callbackUrl = null;
        String shownCallbackUrl = null;
        String callbackProblem = null;
        if (Texts.blank(configuredCallbackUrl)) {
            callbackProblem = "The setting " + CALLBACK_PROPERTY + " is blank. Set it to the address at which the service can reach this "
                    + "tool's receiver, which ends in /api/pas/notifications, and send again.";
        } else {
            ResolvedText resolved = variables == null ? ResolvedText.plain(configuredCallbackUrl) : variables.apply(configuredCallbackUrl.strip());
            if (resolved != null && resolved.resolved() && Texts.present(resolved.value())) {
                callbackUrl = resolved.value().strip();
                shownCallbackUrl = shownEndpoint(Texts.orElse(resolved.display(), callbackUrl).strip());
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
                        "which PAS 2.0.1 requires every client to support and which puts the least member data on the wire",
                        "The PAS 2.2.0 ballot fixes full-resource instead.", notes));
        notes.add("No header was asked for on the notifications. This tool's receiver accepts a notification without one.");
        if (!leftOut.isEmpty()) {
            notes.add(0, "This subscription goes out without " + String.join(" and ", leftOut) + ", because nobody supplied what it is "
                    + "built from. The service may refuse it for that alone.");
        }
        String json = JsonSafe.pretty(subscription);
        String shownJson = json;
        if (callbackUrl != null && !shownCallbackUrl.equals(callbackUrl)) {
            channel.put("endpoint", shownCallbackUrl);
            shownJson = JsonSafe.pretty(subscription);
        }
        return new Built(json, notes, shownCallbackUrl, callbackProblem, leftOut, shownJson);
    }

    /**
     * The address a subscription names for notifications, as it may be shown: every query parameter
     * whose name reads as a credential, such as token or access_token, has its value replaced by the
     * mask of that name, as a header is masked by its name. A secret variable in the address is
     * masked already in the form given here, by the variable's name. The address is sent as
     * written: only what is shown changes. A secret written into the path with no variable has no
     * name to be recognised by, and is not found.
     *
     * @param endpoint the address with its secret variables masked
     */
    public static String shownEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        int query = endpoint.indexOf('?');
        if (query < 0) {
            return endpoint;
        }
        int fragment = endpoint.indexOf('#', query);
        String head = endpoint.substring(0, query + 1);
        String parameters = fragment < 0 ? endpoint.substring(query + 1) : endpoint.substring(query + 1, fragment);
        String tail = fragment < 0 ? "" : endpoint.substring(fragment);
        List<String> shown = new ArrayList<>();
        for (String parameter : parameters.split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            String decoded = decodedName(name);
            boolean masked = !value.isEmpty() && Secrets.looksSecret(decoded) && Secrets.maskedNames(value).isEmpty();
            shown.add(masked ? name + "=" + Secrets.mask(decoded) : parameter);
        }
        return head + String.join("&", shown) + tail;
    }

    private static String decodedName(String name) {
        try {
            return java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A name with a broken escape is judged as written: it cannot be a name the service decodes either.
            return name;
        }
    }

    /**
     * A body the tester supplied, to hand the client: sent exactly as written, and shown with every
     * secret in its {@code channel.endpoint} masked as {@link #shownEndpoint} masks it. The text
     * shown is the tester's own with only that value replaced, so their layout is kept. When the
     * value cannot be found written plainly in the text (it was written with escapes), the body is
     * shown as resolved, with its secret variables masked and nothing else.
     */
    public static ResolvedText ownBody(ResolvedText body) {
        if (body == null || !body.resolved() || body.display() == null) {
            return body;
        }
        String endpoint = endpointOf(body.display());
        String shown = shownEndpoint(endpoint);
        if (endpoint == null || shown.equals(endpoint)) {
            return body;
        }
        String written = JsonSafe.mapper().writeValueAsString(endpoint);
        if (!body.display().contains(written)) {
            return body;
        }
        return new ResolvedText(body.template(), body.display().replace(written, JsonSafe.mapper().writeValueAsString(shown)), body.value(),
                body.variables(), body.unresolved());
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
     *
     * @param versions what the versions of the guide say about the value, or null when they say nothing
     */
    private static String chosen(Function<String, ResolvedText> variables, String variable, String fallback, String what, String why,
                                 String versions, List<String> notes) {
        String stated = CaseResources.value(variables, variable);
        if (stated != null) {
            return stated;
        }
        notes.add("The " + what + " was set to " + fallback + ", " + why + ". It is this tool's default and not something known about "
                + "the service." + (versions == null ? "" : " " + versions) + " Set the variable " + variable + " to send another.");
        return fallback;
    }
}
