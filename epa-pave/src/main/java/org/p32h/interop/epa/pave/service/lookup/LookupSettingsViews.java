package org.p32h.interop.epa.pave.service.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.service.config.SecretSetting;

/**
 * What the Members and Providers pages read and post for a lookup saved on screen (plan 5.5).
 *
 * <p>No view carries a secret: a key, a token, a client secret or a header value is shown as
 * {@link SecretSetting}, set or not, with the reference it is when it is wholly one. The inputs
 * are read strictly (a misspelt property is a 422 that names it), with wrapper types so that a
 * property left out is told from one sent empty.
 */
public final class LookupSettingsViews {

    private LookupSettingsViews() {
    }

    /** Both lookups, as {@code GET /api/config/lookups} answers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookupsView(LookupSettingsView member, LookupSettingsView provider) {
    }

    /** What a save or a removal answers: the lookup as it now is, and a line for each secret stored as typed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookupSaved(LookupSettingsView lookup, List<String> warnings) {

        public LookupSaved {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * One lookup as a screen may see it.
     *
     * @param stated   true when a lookup is saved on screen for this kind
     * @param problem  why lookups.json could not be read, with what to do; null when it could
     * @param inForce  what a lookup does now and where that comes from
     * @param fields   the card fields a field path may name, for the form's rows
     * @param defaults the timeouts a lookup has when it states none (application.yml's)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookupSettingsView(String kind, boolean stated, String problem, Boolean enabled, String method, String address,
                                     String bodyTemplate, String dateFormat, String idForm, SignInView signIn, List<HeaderView> headers,
                                     String listPath, Map<String, String> fieldPaths, Integer connectMs, Integer readMs, InForce inForce,
                                     List<LookupKind.Field> fields, Timeouts defaults) {

        /** Names the kind and whether one is stated; the address is not printed, as a habit with views that hold one. */
        @Override
        public String toString() {
            return "LookupSettingsView[" + kind + ", stated=" + stated + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInView(String method, String headerName, SecretSetting apiKey, SecretSetting bearerToken, String tokenUrl,
                             String clientId, SecretSetting clientSecret, String scope, String clientAuthentication) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeaderView(String name, SecretSetting value) {
    }

    /**
     * @param from  "this screen", "application.yml" or "not set"
     * @param words what the lookup does, for example "POST, signs in with an API key header"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InForce(String from, String words) {
    }

    public record Timeouts(int connectMs, int readMs) {
    }

    /**
     * A save of one lookup (plan 5.5). Secrets are strings, "" keeping the stored value (R-04),
     * with a remove box each.
     */
    public record LookupSettingsInput(Boolean enabled, String method, String address, String bodyTemplate, String dateFormat, String idForm,
                                      SignInInput signIn, List<HeaderInput> headers, String listPath, Map<String, String> fieldPaths,
                                      Integer connectMs, Integer readMs) {

        /** Names the method and the parts given, never a value: the input carries secrets. */
        @Override
        public String toString() {
            return "LookupSettingsInput[" + method + ", signIn=" + signIn + ", headers=" + (headers == null ? 0 : headers.size()) + "]";
        }
    }

    public record SignInInput(String method, String headerName, String apiKey, Boolean removeApiKey, String bearerToken,
                              Boolean removeBearerToken, String tokenUrl, String clientId, String clientSecret, Boolean removeClientSecret,
                              String scope, String clientAuthentication) {

        @Override
        public String toString() {
            return "SignInInput[" + method + "]";
        }
    }

    /** A header row. A blank value keeps the value stored under the same name; remove takes the row away. */
    public record HeaderInput(String name, String value, Boolean remove) {

        @Override
        public String toString() {
            return "HeaderInput[" + name + "]";
        }
    }
}
