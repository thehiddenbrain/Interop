package org.p32h.interop.epa.pave.service.lookup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.LiteralCredentials;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.service.config.SecretSetting;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.Header;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.SignIn;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.HeaderInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.HeaderView;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.InForce;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSettingsInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.LookupSettingsView;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.SignInInput;
import org.p32h.interop.epa.pave.service.lookup.LookupSettingsViews.SignInView;

/**
 * The rules of a lookup saved on screen (R-11, plan 5.5): what each field accepts, with field
 * errors named as the form names its fields; how a save lays what is typed over what is stored
 * (a blank secret keeps it, R-04); and the view a screen may see. A stored lookup is held to the
 * same rules when it is read back, so a file edited by hand into something PAVE would refuse on
 * screen is never sent with.
 *
 * <p>Every check walks characters: no pattern, no recursion (R-23).
 */
public final class LookupSettingsRules {

    public static final int MIN_MS = 100;
    public static final int MAX_MS = 60_000;
    public static final int MAX_HEADERS = 20;
    static final int MAX_TEXT = 2_000;
    static final int MAX_BODY = 8_000;

    static final String FROM_SCREEN = "this screen";
    static final String FROM_APPLICATION = "application.yml";
    static final String NOT_SET = "not set";

    private LookupSettingsRules() {
    }

    // ------------------------------------------------------------------ checks

    /**
     * Every field error of a save, given what is stored (a secret left blank is checked against
     * the stored one). Nothing is stored while one is found.
     */
    public static List<FieldError> check(LookupKind kind, LookupSettingsInput input, LookupSettings stored) {
        List<FieldError> errors = new ArrayList<>();
        String method = Texts.trimToNull(input.method()) == null ? null : input.method().strip().toUpperCase(Locale.ROOT);
        if (method == null || (!method.equals(LookupSettings.GET) && !method.equals(LookupSettings.POST))) {
            errors.add(new FieldError("method", "The method is GET or POST."));
        }
        String address = Texts.trimToNull(input.address());
        if (address == null) {
            errors.add(new FieldError("address", "Give the address the lookup is sent to."));
        } else if (address.length() > MAX_TEXT) {
            errors.add(new FieldError("address", "An address is at most " + MAX_TEXT + " characters."));
        } else if (!addressOrReference(address)) {
            errors.add(new FieldError("address", "The address is an absolute http or https url, or starts with {{."));
        } else if (!LiteralCredentials.inUrl(address).none()) {
            errors.add(new FieldError("address", "This url holds a credential. Put it under Sign in with, or refer to it as a {{placeholder}}."));
        } else if (LookupSettings.GET.equals(method) && !LookupBody.tokens(address).contains(LookupKind.ID_TOKEN)) {
            errors.add(new FieldError("address", "Put {id} where the " + identifier(kind) + " goes, in the path or the query."));
        } else if (LookupSettings.POST.equals(method)) {
            Set<String> tokens = LookupBody.tokens(address);
            if (tokens.contains(LookupKind.ID_TOKEN) || tokens.contains(kind.token()) || tokens.contains(LookupKind.DATE_TOKEN)) {
                errors.add(new FieldError("address", "The " + identifier(kind) + " goes in the body, never in the address."));
            }
        }
        String body = Texts.trimToNull(input.bodyTemplate());
        if (LookupSettings.POST.equals(method)) {
            String problem = body != null && body.length() > MAX_BODY ? "A body template is at most " + MAX_BODY + " characters."
                    : LookupBody.problem(body, kind);
            if (problem != null) {
                errors.add(new FieldError("bodyTemplate", problem));
            }
        }
        boolean dated = LookupSettings.POST.equals(method) && body != null && LookupBody.tokens(body).contains(LookupKind.DATE_TOKEN);
        String dateFormat = Texts.trimToNull(input.dateFormat());
        if (dateFormat != null && !LookupSettings.DATE_FORMATS.contains(dateFormat)) {
            errors.add(new FieldError("dateFormat", "The date format is one of " + String.join(", ", LookupSettings.DATE_FORMATS) + "."));
        } else if (dated && dateFormat == null) {
            errors.add(new FieldError("dateFormat", "The body holds {dateOfService}: choose the format the date is written in."));
        }
        String idForm = Texts.trimToNull(input.idForm());
        if (idForm != null && kind == LookupKind.PROVIDER) {
            errors.add(new FieldError("idForm", "A provider lookup sends the NPI as typed; it has no member ID form."));
        } else if (idForm != null && !LookupSettings.ID_FORMS.contains(idForm.toUpperCase(Locale.ROOT))) {
            errors.add(new FieldError("idForm", "The member ID form is AS_TYPED, PACKED, SPACED or SPLIT."));
        }
        signIn(input.signIn(), stored == null ? null : stored.signIn(), errors);
        headers(input.headers(), stored == null ? List.of() : stored.headers(), errors);
        String listPath = Texts.trimToNull(input.listPath());
        if (listPath != null && !LookupPath.valid(listPath)) {
            errors.add(new FieldError("listPath", LookupPath.RULE));
        }
        if (input.fieldPaths() != null) {
            input.fieldPaths().forEach((field, path) -> {
                String name = "fieldPaths." + field;
                if (kind.field(field) == null) {
                    errors.add(new FieldError(name, "There is no card field " + field + ". The fields are "
                            + String.join(", ", kind.fields().stream().map(LookupKind.Field::name).toList()) + "."));
                } else if (Texts.present(path) && !LookupPath.valid(path)) {
                    errors.add(new FieldError(name, LookupPath.RULE));
                }
            });
        }
        timeout("connectMs", input.connectMs(), errors);
        timeout("readMs", input.readMs(), errors);
        return errors;
    }

    private static void signIn(SignInInput typed, SignIn stored, List<FieldError> errors) {
        if (typed == null) {
            return;
        }
        String method = Texts.trimToNull(typed.method()) == null ? SignIn.NONE : typed.method().strip().toUpperCase(Locale.ROOT);
        if (!SignIn.METHODS.contains(method)) {
            errors.add(new FieldError("signIn.method", "Sign in with is NONE, API_KEY, BEARER_TOKEN, CLIENT_CREDENTIALS or ENVIRONMENT."));
            return;
        }
        switch (method) {
            case SignIn.API_KEY -> {
                String header = Texts.trimToNull(typed.headerName());
                if (header == null || !headerName(header)) {
                    errors.add(new FieldError("signIn.headerName", "Give the name of the header the API key goes in, such as x-api-key."));
                }
                if (secret(typed.apiKey(), typed.removeApiKey(), stored == null ? null : stored.apiKey()) == null) {
                    errors.add(new FieldError("signIn.apiKey", "Give the API key, or a {{placeholder}} that holds it."));
                }
            }
            case SignIn.BEARER_TOKEN -> {
                if (secret(typed.bearerToken(), typed.removeBearerToken(), stored == null ? null : stored.bearerToken()) == null) {
                    errors.add(new FieldError("signIn.bearerToken", "Give the bearer token, or a {{placeholder}} that holds it."));
                }
            }
            case SignIn.CLIENT_CREDENTIALS -> {
                String tokenUrl = Texts.trimToNull(typed.tokenUrl());
                if (tokenUrl == null || !addressOrReference(tokenUrl)) {
                    errors.add(new FieldError("signIn.tokenUrl", "The token url is an absolute http or https url, or a {{placeholder}}."));
                } else if (!LiteralCredentials.inUrl(tokenUrl).none()) {
                    errors.add(new FieldError("signIn.tokenUrl", "This url holds a credential. Put it in Client secret, or refer to it as a "
                            + "{{placeholder}}."));
                }
                if (Texts.blank(typed.clientId())) {
                    errors.add(new FieldError("signIn.clientId", "Give the client id, or a {{placeholder}} that holds it."));
                }
                if (secret(typed.clientSecret(), typed.removeClientSecret(), stored == null ? null : stored.clientSecret()) == null) {
                    errors.add(new FieldError("signIn.clientSecret", "Give the client secret, or a {{placeholder}} that holds it."));
                }
                String how = Texts.trimToNull(typed.clientAuthentication());
                if (how != null && !SignIn.CLIENT_AUTHENTICATIONS.contains(how.toUpperCase(Locale.ROOT))) {
                    errors.add(new FieldError("signIn.method", "Client authentication is BASIC (a Basic header) or BODY (in the form body)."));
                }
            }
            default -> {
            }
        }
    }

    private static void headers(List<HeaderInput> typed, List<Header> stored, List<FieldError> errors) {
        if (typed == null) {
            return;
        }
        int kept = 0;
        for (int i = 0; i < typed.size(); i++) {
            HeaderInput header = typed.get(i);
            if (header == null || Boolean.TRUE.equals(header.remove())) {
                continue;
            }
            kept++;
            String name = Texts.trimToNull(header.name());
            if (name == null || !headerName(name)) {
                errors.add(new FieldError("headers", "Header " + (i + 1) + ": a header name is letters, digits and - only, such as x-trace."));
                continue;
            }
            if (Texts.blank(header.value()) && storedValue(stored, name) == null) {
                errors.add(new FieldError("headers", "Header " + name + ": give its value."));
            } else if (header.value() != null && header.value().length() > MAX_TEXT) {
                errors.add(new FieldError("headers", "Header " + name + ": a value is at most " + MAX_TEXT + " characters."));
            }
        }
        if (kept > MAX_HEADERS) {
            errors.add(new FieldError("headers", "A lookup sends at most " + MAX_HEADERS + " extra headers."));
        }
    }

    private static void timeout(String field, Integer value, List<FieldError> errors) {
        if (value != null && (value < MIN_MS || value > MAX_MS)) {
            errors.add(new FieldError(field, "A lookup timeout is from " + MIN_MS + " to " + MAX_MS + " ms."));
        }
    }

    /** An HTTP header name: the characters RFC 9110 allows in a token. */
    static boolean headerName(String name) {
        if (name.isEmpty() || name.length() > 100) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** True for an absolute http or https url with no white space, or text that starts with a placeholder. */
    static boolean addressOrReference(String text) {
        String value = text.strip();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        if (value.startsWith("{{")) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String rest = lower.startsWith("https://") ? value.substring(8) : lower.startsWith("http://") ? value.substring(7) : null;
        return rest != null && !rest.isEmpty() && !rest.startsWith("/") && !rest.startsWith("{id}");
    }

    private static String identifier(LookupKind kind) {
        return kind == LookupKind.MEMBER ? "member ID" : "NPI";
    }

    // ------------------------------------------------------------------ merge

    /** What to store: the input laid over what is stored. Called only when {@link #check} found nothing. */
    public static LookupSettings merge(LookupKind kind, LookupSettingsInput input, LookupSettings stored) {
        String method = input.method().strip().toUpperCase(Locale.ROOT);
        boolean post = LookupSettings.POST.equals(method);
        String body = post ? Texts.trimToNull(input.bodyTemplate()) : null;
        boolean dated = body != null && LookupBody.tokens(body).contains(LookupKind.DATE_TOKEN);
        String idForm = kind == LookupKind.PROVIDER ? null
                : Texts.trimToNull(input.idForm()) == null ? "PACKED" : input.idForm().strip().toUpperCase(Locale.ROOT);
        Map<String, String> paths = new LinkedHashMap<>();
        if (input.fieldPaths() != null) {
            for (LookupKind.Field field : kind.fields()) {
                String path = Texts.trimToNull(input.fieldPaths().get(field.name()));
                if (path != null) {
                    paths.put(field.name(), path);
                }
            }
        }
        return new LookupSettings(!Boolean.FALSE.equals(input.enabled()), method, input.address().strip(), body,
                dated ? Texts.trimToNull(input.dateFormat()) : null, idForm,
                mergedSignIn(input.signIn(), stored == null ? null : stored.signIn()),
                mergedHeaders(input.headers(), stored == null ? List.of() : stored.headers()),
                Texts.trimToNull(input.listPath()), paths, input.connectMs(), input.readMs());
    }

    private static SignIn mergedSignIn(SignInInput typed, SignIn stored) {
        if (typed == null) {
            return SignIn.NONE_STATED;
        }
        String method = Texts.trimToNull(typed.method()) == null ? SignIn.NONE : typed.method().strip().toUpperCase(Locale.ROOT);
        String keep = stored == null ? null : stored.apiKey();
        return switch (method) {
            case SignIn.API_KEY -> new SignIn(method, typed.headerName().strip(), secret(typed.apiKey(), typed.removeApiKey(), keep), null,
                    null, null, null, null, null);
            case SignIn.BEARER_TOKEN -> new SignIn(method, null, null,
                    secret(typed.bearerToken(), typed.removeBearerToken(), stored == null ? null : stored.bearerToken()), null, null, null,
                    null, null);
            case SignIn.CLIENT_CREDENTIALS -> new SignIn(method, null, null, null, typed.tokenUrl().strip(), typed.clientId().strip(),
                    secret(typed.clientSecret(), typed.removeClientSecret(), stored == null ? null : stored.clientSecret()),
                    Texts.trimToNull(typed.scope()),
                    Texts.trimToNull(typed.clientAuthentication()) == null ? "BASIC" : typed.clientAuthentication().strip().toUpperCase(Locale.ROOT));
            default -> new SignIn(method, null, null, null, null, null, null, null, null);
        };
    }

    private static List<Header> mergedHeaders(List<HeaderInput> typed, List<Header> stored) {
        List<Header> out = new ArrayList<>();
        if (typed == null) {
            return out;
        }
        for (HeaderInput header : typed) {
            if (header == null || Boolean.TRUE.equals(header.remove())) {
                continue;
            }
            String name = header.name().strip();
            String value = Texts.present(header.value()) ? header.value().strip() : storedValue(stored, name);
            out.add(new Header(name, value));
        }
        return out;
    }

    private static String storedValue(List<Header> stored, String name) {
        for (Header header : stored) {
            if (header.name().equalsIgnoreCase(name)) {
                return header.value();
            }
        }
        return null;
    }

    /** R-04: a value typed replaces, blank keeps, and the remove box removes. */
    private static String secret(String typed, Boolean remove, String stored) {
        if (Boolean.TRUE.equals(remove)) {
            return Texts.present(typed) ? typed.strip() : null;
        }
        return Texts.present(typed) ? typed.strip() : stored;
    }

    /**
     * One line for each secret this save stored as typed rather than as a reference (R-04): the
     * folder is closed to other accounts, but a reference keeps the secret out of the file.
     */
    public static List<String> plainSecretWarnings(LookupKind kind, LookupSettingsInput input, Path file) {
        List<String> warnings = new ArrayList<>();
        SignInInput signIn = input.signIn();
        List<String[]> typed = new ArrayList<>();
        if (signIn != null) {
            typed.add(new String[] {"API key", signIn.apiKey()});
            typed.add(new String[] {"bearer token", signIn.bearerToken()});
            typed.add(new String[] {"client secret", signIn.clientSecret()});
        }
        if (input.headers() != null) {
            for (HeaderInput header : input.headers()) {
                if (header != null && !Boolean.TRUE.equals(header.remove())) {
                    typed.add(new String[] {"value of the header " + Texts.orElse(header.name(), "").strip(), header.value()});
                }
            }
        }
        for (String[] each : typed) {
            if (Texts.present(each[1]) && Placeholders.sole(each[1]) == null) {
                warnings.add("The " + each[0] + " of the " + kind.word() + " lookup is stored as typed in " + file + ", which only this "
                        + "account can read. To keep it out of the file, type it as {{env:PAVE_YOUR_NAME}} and set that operating system "
                        + "variable before PAVE starts.");
            }
        }
        return warnings;
    }

    /** True when a stored lookup keeps every rule a save is held to: read back from a file, it is used only then. */
    static boolean keepsTheRules(LookupKind kind, LookupSettings stored) {
        return check(kind, asInput(stored), stored).isEmpty();
    }

    private static LookupSettingsInput asInput(LookupSettings stored) {
        SignIn s = stored.signIn();
        List<HeaderInput> headers = stored.headers().stream().map(h -> new HeaderInput(h.name(), h.value(), null)).toList();
        return new LookupSettingsInput(stored.enabled(), stored.method(), stored.address(), stored.bodyTemplate(), stored.dateFormat(),
                stored.idForm(), new SignInInput(s.method(), s.headerName(), s.apiKey(), null, s.bearerToken(), null, s.tokenUrl(),
                s.clientId(), s.clientSecret(), null, s.scope(), s.clientAuthentication()), headers, stored.listPath(), stored.fieldPaths(),
                stored.connectMs(), stored.readMs());
    }

    // ------------------------------------------------------------------ views

    /**
     * What a screen may see of one lookup.
     *
     * @param saved    the lookup saved on screen, or null
     * @param problem  why lookups.json could not be read, or null
     * @param fallback application.yml's lookup of this kind
     */
    public static LookupSettingsView view(LookupKind kind, LookupSettings saved, String problem, LookupProperties.Endpoint fallback) {
        LookupSettingsViews.Timeouts defaults = new LookupSettingsViews.Timeouts(fallback.connectTimeoutMs(), fallback.readTimeoutMs());
        if (saved == null) {
            return new LookupSettingsView(kind.word(), false, problem, null, null, null, null, null, null, null, null, null, null, null, null,
                    fallbackInForce(kind, fallback), kind.fields(), defaults);
        }
        SignIn s = saved.signIn();
        SignInView signIn = new SignInView(s.method(), s.headerName(), SecretSetting.of(s.apiKey()), SecretSetting.of(s.bearerToken()),
                s.tokenUrl(), s.clientId(), SecretSetting.of(s.clientSecret()), s.scope(), s.clientAuthentication());
        List<HeaderView> headers = saved.headers().stream().map(h -> new HeaderView(h.name(), SecretSetting.of(h.value()))).toList();
        return new LookupSettingsView(kind.word(), true, problem, saved.on(), saved.method(), saved.address(), saved.bodyTemplate(),
                saved.dateFormat(), saved.idForm(), signIn, headers, saved.listPath(), saved.fieldPaths(), saved.connectMs(), saved.readMs(),
                new InForce(FROM_SCREEN, savedWords(saved)), kind.fields(), defaults);
    }

    static String savedWords(LookupSettings saved) {
        if (!saved.on()) {
            return "Switched off on this screen: the tester types the details";
        }
        return saved.method() + ", " + signInWords(saved.signIn().method());
    }

    static String signInWords(String method) {
        return switch (method) {
            case SignIn.API_KEY -> "signs in with an API key header";
            case SignIn.BEARER_TOKEN -> "signs in with a bearer token";
            case SignIn.CLIENT_CREDENTIALS -> "signs in with client credentials";
            case SignIn.ENVIRONMENT -> "signs in with the active environment's sign-in";
            default -> "sends no sign-in";
        };
    }

    private static InForce fallbackInForce(LookupKind kind, LookupProperties.Endpoint fallback) {
        String property = "lookup." + kind.word();
        if (Texts.blank(fallback.url())) {
            return new InForce(NOT_SET, "No lookup is configured: the tester types the details");
        }
        if (!fallback.enabled()) {
            return new InForce(FROM_APPLICATION, "Switched off in application.yml (" + property + ".enabled)");
        }
        return new InForce(FROM_APPLICATION, "GET, " + (fallback.useOnyxAuth() ? "signs in with the active environment's sign-in"
                : fallback.headers().isEmpty() ? "sends no sign-in" : "sends the headers set in application.yml"));
    }
}
