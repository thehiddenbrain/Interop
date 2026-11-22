package org.p32h.interop.epa.pave.service.config;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An environment's settings as {@code store.json} version 2 holds them (plan 5.1), written and read
 * back. What the store reads it never guesses: a method, an operation, a rule, a shape or a value
 * name this tool does not know makes the whole settings entry unreadable, and the entry says why,
 * instead of being read as something it may not be or stopping the start.
 */
final class StoredSettings {

    /** The operations an environment may type an address for: the eight that leave this tool for the vendor. */
    static final java.util.List<EpaOperation> ADDRESSED = java.util.List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.CRD_ORDER_SELECT,
            EpaOperation.CRD_DISCOVERY, EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, EpaOperation.DTR_NEXT_QUESTION, EpaOperation.PAS_SUBMIT,
            EpaOperation.PAS_INQUIRE, EpaOperation.SUBSCRIPTION_CREATE);

    /** The key under which the subscription base is stored: create, get, update and delete all append to it. */
    static final String SUBSCRIPTION = "SUBSCRIPTION";

    /** The longest value name and value an environment may state (plan 5.1). */
    static final int NAME_MAX = 64;
    static final int VALUE_MAX = 2000;

    private StoredSettings() {
    }

    /**
     * What reading a settings entry gave.
     *
     * @param settings the settings, {@link EnvironmentSettings#none()} when the entry was absent or unreadable
     * @param problem  why the entry could not be read, in words, or null when it was read
     */
    record Read(EnvironmentSettings settings, String problem) {
    }

    /** The key an address is stored under: the operation's name, and {@link #SUBSCRIPTION} for the subscription base. */
    static String key(EpaOperation operation) {
        return operation == EpaOperation.SUBSCRIPTION_CREATE ? SUBSCRIPTION : operation.name();
    }

    /** The operation an address key names, or null when it names none an address may be typed for. */
    static EpaOperation operation(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.strip().toUpperCase(Locale.ROOT);
        for (EpaOperation operation : ADDRESSED) {
            if (key(operation).equals(wanted)) {
                return operation;
            }
        }
        return null;
    }

    /**
     * Why a value name breaks the rule of plan 5.1, or null when it keeps it: letters, digits,
     * underscore, hyphen and dot, at most {@link #NAME_MAX}, and never a name {@link Secrets} reads
     * as a secret, since a value is shown and a secret never is. Walked by character: no pattern.
     */
    static String nameProblem(String name) {
        if (!Texts.present(name)) {
            return "A value needs a name.";
        }
        if (name.length() > NAME_MAX) {
            return "A value's name is at most " + NAME_MAX + " characters.";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!allowed) {
                return "A value's name holds letters, digits, _, - and . only.";
            }
        }
        if (Secrets.looksSecret(name)) {
            return SECRET_NAME;
        }
        return null;
    }

    /** R-42's words for a value whose name reads as a secret. */
    static final String SECRET_NAME = "This looks like a secret. Put it in Sign-in, or keep it in the Postman environment, where it is masked.";

    /** The settings as store.json holds them. Parts not stated are left out, so a version 2 entry reads back as it was saved. */
    static ObjectNode write(EnvironmentSettings settings) {
        ObjectNode node = JsonSafe.mapper().createObjectNode();
        SignInSettings signIn = settings.signIn();
        if (signIn != null) {
            ObjectNode in = node.putObject("signIn");
            in.put("method", signIn.method().name());
            putIfPresent(in, "tokenUrl", signIn.tokenUrl());
            putIfPresent(in, "clientId", signIn.clientId());
            putIfPresent(in, "clientSecret", signIn.clientSecret());
            putIfPresent(in, "scope", signIn.scope());
            in.put("clientAuthentication", signIn.clientAuthentication().name());
            putIfPresent(in, "bearerToken", signIn.bearerToken());
        }
        Timeouts timeouts = settings.timeouts();
        if (!timeouts.empty()) {
            ObjectNode out = node.putObject("timeouts");
            if (timeouts.connectMs() != null) {
                out.put("connectMs", timeouts.connectMs().intValue());
            }
            if (timeouts.readMs() != null) {
                out.put("readMs", timeouts.readMs().intValue());
            }
        }
        if (!settings.addresses().isEmpty()) {
            ObjectNode addresses = node.putObject("addresses");
            settings.addresses().forEach((operation, address) -> addresses.put(key(operation), address));
        }
        if (settings.memberIdRule() != null) {
            node.put("memberIdRule", settings.memberIdRule().name());
        }
        if (!settings.shapes().isEmpty()) {
            ObjectNode shapes = node.putObject("shapes");
            settings.shapes().forEach((shape, value) -> shapes.put(shape.name(), value));
        }
        if (!settings.values().isEmpty()) {
            ObjectNode values = node.putObject("values");
            settings.values().forEach(values::put);
        }
        return node;
    }

    /** Reads a settings entry. Absent or null reads as no settings; anything this tool does not know makes it unreadable. */
    static Read read(JsonNode node) {
        if (JsonSafe.nullOrMissing(node)) {
            return new Read(EnvironmentSettings.none(), null);
        }
        if (!JsonSafe.isObject(node)) {
            return unreadable("they are not a JSON object");
        }
        SignInSettings signIn = null;
        JsonNode in = JsonSafe.child(node, "signIn");
        if (!JsonSafe.nullOrMissing(in)) {
            SignInSettings.Method method = SignInSettings.Method.parse(JsonSafe.presentText(in, "method"));
            if (method == null) {
                return unreadable("the sign-in names no method this tool knows");
            }
            String authentication = JsonSafe.presentText(in, "clientAuthentication");
            SignInSettings.ClientAuthentication how = SignInSettings.ClientAuthentication.parse(authentication);
            if (authentication != null && how == null) {
                return unreadable("the sign-in names a client authentication this tool does not know");
            }
            signIn = new SignInSettings(method, JsonSafe.presentText(in, "tokenUrl"), JsonSafe.presentText(in, "clientId"),
                    JsonSafe.presentText(in, "clientSecret"), JsonSafe.text(in, "scope"), how, JsonSafe.presentText(in, "bearerToken"));
        }
        JsonNode times = JsonSafe.child(node, "timeouts");
        Integer connect = JsonSafe.integer(times, "connectMs");
        Integer readMs = JsonSafe.integer(times, "readMs");
        if (outOfRange(connect) || outOfRange(readMs) || unread(times, "connectMs", connect) || unread(times, "readMs", readMs)) {
            return unreadable("a timeout is not a whole number from " + Timeouts.MIN_MS + " to " + Timeouts.MAX_MS + " ms");
        }
        Timeouts timeouts = new Timeouts(connect, readMs, connect == null ? null : Timeouts.FROM_ENVIRONMENT,
                readMs == null ? null : Timeouts.FROM_ENVIRONMENT);
        Map<EpaOperation, String> addresses = new EnumMap<>(EpaOperation.class);
        for (Map.Entry<String, JsonNode> entry : JsonSafe.properties(JsonSafe.child(node, "addresses"))) {
            EpaOperation operation = operation(entry.getKey());
            String address = JsonSafe.presentText(entry.getValue());
            if (operation == null) {
                return unreadable("an address is stored for an operation this tool does not know");
            }
            if (address == null) {
                return unreadable("an address is stored with no url");
            }
            addresses.put(operation, address);
        }
        MemberIdRule rule = null;
        String ruleName = JsonSafe.presentText(node, "memberIdRule");
        if (ruleName != null) {
            rule = MemberIdRule.parse(ruleName);
            if (rule == null) {
                return unreadable("the member ID rule is not one this tool knows");
            }
        }
        Map<RequestShape, String> shapes = new EnumMap<>(RequestShape.class);
        for (Map.Entry<String, JsonNode> entry : JsonSafe.properties(JsonSafe.child(node, "shapes"))) {
            RequestShape shape = RequestShape.parse(entry.getKey());
            String value = JsonSafe.presentText(entry.getValue());
            if (shape == null || value == null || !shape.allows(value.strip())) {
                return unreadable("a request shape or its value is not one this tool knows");
            }
            shapes.put(shape, value.strip());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : JsonSafe.properties(JsonSafe.child(node, "values"))) {
            String value = JsonSafe.text(entry.getValue());
            if (nameProblem(entry.getKey()) != null || value == null || value.length() > VALUE_MAX) {
                return unreadable("a value has a name or a length outside the rules for values");
            }
            values.put(entry.getKey(), value);
        }
        return new Read(new EnvironmentSettings(signIn, timeouts, addresses, rule, shapes, values), null);
    }

    /** True when the field holds something that did not read as a whole number: a fraction or text is never rounded into a timeout. */
    private static boolean unread(JsonNode times, String field, Integer read) {
        return read == null && JsonSafe.presence(JsonSafe.child(times, field)) != Presence.ABSENT
                && JsonSafe.presence(JsonSafe.child(times, field)) != Presence.NULL;
    }

    private static boolean outOfRange(Integer ms) {
        return ms != null && (ms < Timeouts.MIN_MS || ms > Timeouts.MAX_MS);
    }

    private static Read unreadable(String why) {
        return new Read(EnvironmentSettings.none(), why);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
