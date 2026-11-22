package org.p32h.interop.epa.pave.service.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One lookup as saved on screen (R-11, plan 5.5), and as {@code lookups.json} holds it. A saved
 * lookup replaces application.yml's {@code lookup.member} or {@code lookup.provider} wholly.
 *
 * <p>Secret values (an API key, a bearer token, a client secret, every header value) are kept as
 * typed, or as the {@code {{env:PAVE_NAME}}} or {@code {{variable}}} reference typed in their
 * place. None is ever returned to a screen, so {@link #toString()} names none of them, and no
 * address either, since an address may carry a key in its query.
 *
 * @param enabled      false keeps the settings and sends nothing: the tester types the details
 * @param method       GET or POST
 * @param address      the url template; for GET it holds {@code {id}}, for POST no identifier
 * @param bodyTemplate the JSON body for POST with {@code {memberId}} or {@code {npi}}, and
 *                     optionally {@code {dateOfService}}; null for GET
 * @param dateFormat   how {@code {dateOfService}} is written: one of {@link #DATE_FORMATS}; null
 *                     when the body has no date
 * @param idForm       member only: how the member ID is written in the lookup (one of
 *                     {@link #ID_FORMS}); null for a provider
 * @param signIn       how the lookup signs in; never null
 * @param headers      the extra headers, values secret
 * @param listPath     where the answer holds its matches, or null for the usual places
 * @param fieldPaths   card field name to path in a match; a field not named is read the usual way
 * @param connectMs    the connect timeout, or null for application.yml's lookup timeout
 * @param readMs       the read timeout, or null for application.yml's lookup timeout
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupSettings(Boolean enabled, String method, String address, String bodyTemplate, String dateFormat, String idForm,
                             SignIn signIn, List<Header> headers, String listPath, Map<String, String> fieldPaths, Integer connectMs,
                             Integer readMs) {

    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final List<String> DATE_FORMATS = List.of("yyyy-MM-dd", "MM/dd/yyyy", "yyyyMMdd");
    public static final String AS_TYPED = "AS_TYPED";
    public static final List<String> ID_FORMS = List.of(AS_TYPED, "PACKED", "SPACED", "SPLIT");

    public LookupSettings {
        signIn = signIn == null ? SignIn.NONE_STATED : signIn;
        headers = headers == null ? List.of() : List.copyOf(headers);
        fieldPaths = fieldPaths == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fieldPaths));
    }

    public boolean on() {
        return !Boolean.FALSE.equals(enabled);
    }

    public boolean post() {
        return POST.equals(method);
    }

    /**
     * How a lookup signs in. One of {@link #NONE}, {@link #API_KEY} (a header of the given name
     * carrying the key), {@link #BEARER_TOKEN}, {@link #CLIENT_CREDENTIALS} (its own token url),
     * {@link #ENVIRONMENT} (the active environment's sign-in, the only way the environment's token
     * goes to a lookup).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignIn(String method, String headerName, String apiKey, String bearerToken, String tokenUrl, String clientId,
                         String clientSecret, String scope, String clientAuthentication) {

        public static final String NONE = "NONE";
        public static final String API_KEY = "API_KEY";
        public static final String BEARER_TOKEN = "BEARER_TOKEN";
        public static final String CLIENT_CREDENTIALS = "CLIENT_CREDENTIALS";
        public static final String ENVIRONMENT = "ENVIRONMENT";
        public static final List<String> METHODS = List.of(NONE, API_KEY, BEARER_TOKEN, CLIENT_CREDENTIALS, ENVIRONMENT);
        public static final List<String> CLIENT_AUTHENTICATIONS = List.of("BASIC", "BODY");

        static final SignIn NONE_STATED = new SignIn(NONE, null, null, null, null, null, null, null, null);

        public SignIn {
            method = method == null ? NONE : method;
        }

        /** The method and which secrets are set; never a value. */
        @Override
        public String toString() {
            return "SignIn[" + method + ", apiKey " + (apiKey == null ? "not set" : "redacted") + ", bearerToken "
                    + (bearerToken == null ? "not set" : "redacted") + ", clientSecret " + (clientSecret == null ? "not set" : "redacted")
                    + "]";
        }
    }

    /** An extra header. Its value is treated as a secret whatever the header is called: it is often a key. */
    public record Header(String name, String value) {

        @Override
        public String toString() {
            return "Header[" + name + "]";
        }
    }

    /** The method, the sign-in and the header names; never the address, a template or a value. */
    @Override
    public String toString() {
        return "LookupSettings[" + method + ", on=" + on() + ", " + signIn + ", headers=" + headers + "]";
    }
}
