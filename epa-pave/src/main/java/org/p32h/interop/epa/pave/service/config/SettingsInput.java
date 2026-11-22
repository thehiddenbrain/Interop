package org.p32h.interop.epa.pave.service.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.config.SettingsViews.EnvironmentSettingsInput;
import org.p32h.interop.epa.pave.service.config.SettingsViews.SignInInput;
import org.p32h.interop.epa.pave.service.config.SettingsViews.TimeoutsInput;

/**
 * The rules of a save of settings (plan 5.4): what each field accepts, with the field errors named
 * as the form names its fields, and how what is typed is laid over what is stored. A part left out
 * keeps what is stored; a secret left blank keeps the stored secret, and only its remove box takes
 * it away (R-04); "" clears an address, a value, the rule or a shape, and null clears a timeout.
 */
final class SettingsInput {

    private SettingsInput() {
    }

    /** Adds every field error of the input to the list. Nothing is stored while one is found. */
    static void check(EnvironmentSettingsInput input, List<FieldError> errors) {
        SignInInput signIn = input.signIn();
        if (signIn != null && Texts.present(signIn.method())) {
            SignInSettings.Method method = SignInSettings.Method.parse(signIn.method());
            if (method == null) {
                errors.add(new FieldError("signIn.method", "The method is NONE, CLIENT_CREDENTIALS or BEARER_TOKEN."));
            }
            if (Texts.present(signIn.clientAuthentication()) && SignInSettings.ClientAuthentication.parse(signIn.clientAuthentication()) == null) {
                errors.add(new FieldError("signIn.method", "Client authentication is BASIC (a Basic header) or BODY (in the form body)."));
            }
            if (Texts.present(signIn.tokenUrl()) && !addressOrReference(signIn.tokenUrl())) {
                errors.add(new FieldError("signIn.tokenUrl", "The token url is an absolute http or https url, or a {{placeholder}}."));
            }
            if (Texts.present(signIn.tokenUrl()) && !LiteralCredentials.inUrl(signIn.tokenUrl().strip()).none()) {
                errors.add(new FieldError("signIn.tokenUrl", CREDENTIAL_IN_URL));
            } else if (Texts.present(signIn.tokenUrl()) && UrlNormal.escapeOfEscape(signIn.tokenUrl())) {
                // A token url is held against the production addresses as an address is (I-28, I-29).
                errors.add(new FieldError("signIn.tokenUrl", UrlNormal.NO_ESCAPE_OF_AN_ESCAPE));
            }
        }
        TimeoutsInput timeouts = input.timeouts();
        if (timeouts != null) {
            timeout("timeouts.connectMs", timeouts.connectGiven(), timeouts.getConnectMs(), errors);
            timeout("timeouts.readMs", timeouts.readGiven(), timeouts.getReadMs(), errors);
        }
        if (input.addresses() != null) {
            input.addresses().forEach((key, address) -> {
                String field = "addresses." + key;
                if (StoredSettings.operation(key) == null) {
                    errors.add(new FieldError(field, "No address can be typed for " + key + ". The keys are "
                            + String.join(", ", StoredSettings.ADDRESSED.stream().map(StoredSettings::key).toList()) + "."));
                } else if (Texts.present(address) && !addressOrReference(address)) {
                    errors.add(new FieldError(field, "An address is an absolute http or https url, or starts with {{."));
                } else if (Texts.present(address) && !LiteralCredentials.inUrl(address.strip()).none()) {
                    errors.add(new FieldError(field, CREDENTIAL_IN_URL));
                } else if (Texts.present(address) && UrlNormal.escapeOfEscape(address)) {
                    // Compared with production as a server reads it, decoded once (I-29).
                    errors.add(new FieldError(field, UrlNormal.NO_ESCAPE_OF_AN_ESCAPE));
                }
            });
        }
        if (Texts.present(input.memberIdRule()) && MemberIdRule.parse(input.memberIdRule()) == null) {
            errors.add(new FieldError("memberIdRule", "The rule is PACKED, SPACED or SPLIT."));
        }
        if (input.shapes() != null) {
            input.shapes().forEach((key, value) -> {
                RequestShape shape = RequestShape.parse(key);
                if (shape == null) {
                    errors.add(new FieldError("shapes." + key, "This tool has no request shape of that name."));
                } else if (Texts.present(value) && !shape.allows(value.strip())) {
                    errors.add(new FieldError("shapes." + key, "The value is one of " + String.join(", ", shape.options().stream()
                            .map(RequestShape.Option::value).toList()) + "."));
                }
            });
        }
        if (input.values() != null) {
            input.values().forEach((name, value) -> {
                String field = "values." + name;
                String problem = StoredSettings.nameProblem(name);
                if (problem != null) {
                    errors.add(new FieldError(field, problem));
                } else if (value != null && value.length() > StoredSettings.VALUE_MAX) {
                    errors.add(new FieldError(field, "A value is at most " + StoredSettings.VALUE_MAX + " characters."));
                } else if (Texts.present(value) && !LiteralCredentials.inUrl(value.strip()).none()) {
                    errors.add(new FieldError(field, CREDENTIAL_IN_URL));
                }
            });
        }
    }

    /** A url with a user name or password in it: a value shown on screen must not carry one. */
    static final String CREDENTIAL_IN_URL = "This url holds a credential. Put the credential in Sign-in, or refer to it as a {{placeholder}}.";

    private static void timeout(String field, boolean given, Integer value, List<FieldError> errors) {
        if (given && value != null && (value < Timeouts.MIN_MS || value > Timeouts.MAX_MS)) {
            errors.add(new FieldError(field, "A timeout is from " + Timeouts.MIN_MS + " to " + Timeouts.MAX_MS + " ms."));
        }
    }

    /** True for an absolute http or https url, or text that starts with a placeholder. Read by character: no pattern. */
    static boolean addressOrReference(String text) {
        String value = text.strip();
        if (value.startsWith("{{")) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String rest = lower.startsWith("https://") ? value.substring(8) : lower.startsWith("http://") ? value.substring(7) : null;
        if (rest == null || rest.isEmpty() || rest.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The settings to store: what is typed laid over what is stored. Called after {@link #check} found nothing. */
    static EnvironmentSettings merge(EnvironmentSettingsInput input, EnvironmentSettings stored) {
        SignInSettings signIn = stored.signIn();
        SignInInput typedSignIn = input.signIn();
        if (typedSignIn != null) {
            signIn = signIn(typedSignIn, stored.signIn());
        }
        Timeouts timeouts = stored.timeouts();
        TimeoutsInput typedTimeouts = input.timeouts();
        if (typedTimeouts != null) {
            Integer connect = typedTimeouts.connectGiven() ? typedTimeouts.getConnectMs() : timeouts.connectMs();
            Integer read = typedTimeouts.readGiven() ? typedTimeouts.getReadMs() : timeouts.readMs();
            timeouts = new Timeouts(connect, read, connect == null ? null : Timeouts.FROM_ENVIRONMENT, read == null ? null
                    : Timeouts.FROM_ENVIRONMENT);
        }
        Map<EpaOperation, String> addresses = new EnumMap<>(EpaOperation.class);
        addresses.putAll(stored.addresses());
        if (input.addresses() != null) {
            input.addresses().forEach((key, address) -> {
                EpaOperation operation = StoredSettings.operation(key);
                if (Texts.present(address)) {
                    addresses.put(operation, address.strip());
                } else {
                    addresses.remove(operation);
                }
            });
        }
        MemberIdRule rule = stored.memberIdRule();
        if (input.memberIdRule() != null) {
            rule = Texts.present(input.memberIdRule()) ? MemberIdRule.parse(input.memberIdRule()) : null;
        }
        Map<RequestShape, String> shapes = new EnumMap<>(RequestShape.class);
        shapes.putAll(stored.shapes());
        if (input.shapes() != null) {
            input.shapes().forEach((key, value) -> {
                RequestShape shape = RequestShape.parse(key);
                if (Texts.present(value)) {
                    shapes.put(shape, value.strip());
                } else {
                    shapes.remove(shape);
                }
            });
        }
        Map<String, String> values = new LinkedHashMap<>(stored.values());
        if (input.values() != null) {
            input.values().forEach((name, value) -> {
                if (Texts.present(value)) {
                    values.put(name, value.strip());
                } else {
                    values.remove(name);
                }
            });
        }
        return new EnvironmentSettings(signIn, timeouts, addresses, rule, shapes, values);
    }

    /** The sign-in as typed over the stored one. A blank method is not stated: application.yml's sign-in applies again. */
    private static SignInSettings signIn(SignInInput typed, SignInSettings stored) {
        SignInSettings.Method method = Texts.present(typed.method()) ? SignInSettings.Method.parse(typed.method()) : null;
        if (method == null) {
            return null;
        }
        String clientSecret = secret(typed.clientSecret(), typed.removeClientSecret(), stored == null ? null : stored.clientSecret());
        String bearerToken = secret(typed.bearerToken(), typed.removeBearerToken(), stored == null ? null : stored.bearerToken());
        return new SignInSettings(method, Texts.trimToNull(typed.tokenUrl()), Texts.trimToNull(typed.clientId()), clientSecret,
                Texts.trimToNull(typed.scope()), SignInSettings.ClientAuthentication.parse(typed.clientAuthentication()), bearerToken);
    }

    /** R-04: a value typed replaces, blank keeps, and the remove box removes. */
    private static String secret(String typed, Boolean remove, String stored) {
        if (Boolean.TRUE.equals(remove)) {
            return null;
        }
        return Texts.present(typed) ? typed.strip() : stored;
    }

    /**
     * One warning for each secret this save stored as typed rather than as a reference (R-04): the
     * store folder is closed to other accounts, but a reference keeps the secret out of it.
     */
    static List<String> plainSecretWarnings(EnvironmentSettingsInput input, String environment, Path directory) {
        List<String> warnings = new ArrayList<>();
        SignInInput signIn = input.signIn();
        if (signIn == null) {
            return warnings;
        }
        for (String[] each : new String[][] {{"client secret", signIn.clientSecret(), String.valueOf(signIn.removeClientSecret())},
                {"bearer token", signIn.bearerToken(), String.valueOf(signIn.removeBearerToken())}}) {
            if (Texts.present(each[1]) && !"true".equals(each[2]) && !SecretSetting.reference(each[1])) {
                warnings.add("The " + each[0] + " of \"" + ConfigStore.quoted(environment) + "\" is stored as typed in " + directory
                        + ", which only this account can read. To keep it out of the store, type it as {{env:PAVE_YOUR_NAME}} and set that "
                        + "operating system variable before PAVE starts.");
            }
        }
        return warnings;
    }
}
