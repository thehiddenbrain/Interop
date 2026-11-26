package org.p32h.interop.epa.pave.service.config;

import java.util.Locale;

/**
 * How an environment signs in to the vendor, as its settings state it (plan 5.1, R-03).
 *
 * <p>A stated method replaces {@code onyx.auth} wholly for that environment. When an environment
 * states none, it has no {@code SignInSettings} at all ({@link PreparedCall#signIn()} is null) and
 * {@code onyx.auth} applies as before. Every text value may be a {@code {{placeholder}}} resolved
 * against the environment's variables, or an {@code {{env:PAVE_NAME}}} reference, so a secret
 * typed as a reference never sits in the store. Stored, the values are as typed; carried by a
 * prepared call, they are resolved against the snapshot the call was prepared in.
 *
 * <p>Holds a client secret and a bearer token, so {@link #toString()} names neither.
 *
 * @param method               how it signs in; never null in an instance
 * @param tokenUrl             where client credentials are exchanged for a token, or null
 * @param clientId             the client id, or null
 * @param clientSecret         the client secret, or a reference to one, or null
 * @param scope                the scope asked for, or null or blank for none
 * @param clientAuthentication how the client id and secret travel to the token url; null reads as
 *                             {@link ClientAuthentication#BASIC}, the way this tool sent them before
 * @param bearerToken          the token sent as it is under {@link Method#BEARER_TOKEN}, or null
 */
public record SignInSettings(Method method, String tokenUrl, String clientId, String clientSecret, String scope,
                             ClientAuthentication clientAuthentication, String bearerToken) {

    public SignInSettings {
        if (method == null) {
            // Not stating a method is said by having no settings, never by an instance without one,
            // so that a half-filled record cannot be taken for "sign in with nothing".
            throw new IllegalArgumentException("A sign-in setting states its method.");
        }
        if (clientAuthentication == null) {
            clientAuthentication = ClientAuthentication.BASIC;
        }
    }

    /** Signs in with nothing: no Authorization header is sent. */
    public static SignInSettings none() {
        return new SignInSettings(Method.NONE, null, null, null, null, null, null);
    }

    /** OAuth2 client credentials. */
    public static SignInSettings clientCredentials(String tokenUrl, String clientId, String clientSecret, String scope,
                                                   ClientAuthentication clientAuthentication) {
        return new SignInSettings(Method.CLIENT_CREDENTIALS, tokenUrl, clientId, clientSecret, scope, clientAuthentication, null);
    }

    /** A token sent as it is, never asked for and never retried. */
    public static SignInSettings bearerToken(String token) {
        return new SignInSettings(Method.BEARER_TOKEN, null, null, null, null, null, token);
    }

    /** The same settings with other text values, such as the values resolved against a snapshot. */
    public SignInSettings with(String tokenUrl, String clientId, String clientSecret, String scope, String bearerToken) {
        return new SignInSettings(method, tokenUrl, clientId, clientSecret, scope, clientAuthentication, bearerToken);
    }

    /** Names the method and whether each secret is set, never a value: the record holds secrets. */
    @Override
    public String toString() {
        return "SignInSettings[method=" + method + ", clientAuthentication=" + clientAuthentication
                + ", clientSecret " + (clientSecret == null ? "not set" : "redacted")
                + ", bearerToken " + (bearerToken == null ? "not set" : "redacted") + "]";
    }

    /** How the environment signs in. */
    public enum Method {
        NONE, CLIENT_CREDENTIALS, BEARER_TOKEN;

        /**
         * The method with this name, ignoring case and surrounding spaces, or null when no method
         * has it. Null rather than an exception, so that a stored entry naming an unknown method is
         * listed as unreadable instead of stopping the start, and never read as another method.
         */
        public static Method parse(String name) {
            return SignInSettings.parse(Method.class, name);
        }
    }

    /** How client credentials travel to the token url. */
    public enum ClientAuthentication {
        /** In an HTTP Basic header, as this tool always sent them before (D-233). */
        BASIC,
        /** As client_id and client_secret in the form body. */
        BODY;

        /** As {@link Method#parse}: null for a name no value has. */
        public static ClientAuthentication parse(String name) {
            return SignInSettings.parse(ClientAuthentication.class, name);
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.strip().toUpperCase(Locale.ROOT);
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(wanted)) {
                return value;
            }
        }
        return null;
    }
}
