package org.p32h.interop.epa.pave.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;

/**
 * What the environment form reads and posts (plan 5.4): one environment with every setting it
 * states, each beside the value in force and where that comes from, and the input of a save.
 *
 * <p>No view here carries the value of a secret: a stored client secret or bearer token is a
 * {@link SecretSetting}, an export's secret variable is masked as everywhere else, and every url is
 * shown with a credential typed into it masked.
 */
public final class SettingsViews {

    private SettingsViews() {
    }

    // ------------------------------------------------------------------------------------------
    // The view
    // ------------------------------------------------------------------------------------------

    /**
     * One environment for its form. Every field is present from D1a; a screen draws a section only
     * when its field is there.
     *
     * @param problem why the stored settings could not be read, in words; absent when they were read.
     *                While it is set, the settings shown are none and nothing is sent under this environment
     * @param epoch   the configuration epoch the view was built from
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvironmentSettingsView(String id, String name, EnvironmentKind kind, boolean active, boolean acknowledgedNotProd,
                                          ExportView export, ProfileView profile, SignInView signIn, TimeoutsView timeouts,
                                          MemberIdRuleView memberIdRule, List<ShapeView> shapes, List<ValueView> values,
                                          List<AddressView> addresses, String problem, long epoch) {

        public EnvironmentSettingsView {
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
            values = values == null ? List.of() : List.copyOf(values);
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }
    }

    /**
     * The Postman environment export stored with it.
     *
     * @param fileName   the name it was uploaded under, absent for an environment made on screen
     * @param uploadedAt when, as an ISO instant
     * @param variables  its variables, secrets masked
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExportView(String fileName, String uploadedAt, List<VariableView> variables) {

        public ExportView {
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /** Mock or live, a fact of the start and never a setting (R-01). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileView(String mode, String words) {
    }

    /**
     * How it signs in (R-03). The method is absent when the environment states none, and
     * application.yml's {@code onyx.auth} applies; {@code inForceWords} says which applies.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInView(String method, String tokenUrl, String clientId, String scope, String clientAuthentication,
                             SecretSetting clientSecret, SecretSetting bearerToken, String inForceWords) {
    }

    /**
     * Its timeouts: what it states (absent when not), what is in force with the source of each, and
     * application.yml's values, which "Use application.yml's value" goes back to (R-28).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeoutsView(Integer connectMs, Integer readMs, Timeouts inForce, Defaults defaults) {

        /** application.yml's values. */
        public record Defaults(int connectMs, int readMs) {
        }
    }

    /**
     * The member ID rule (R-10).
     *
     * @param from "default" or "this environment"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberIdRuleView(String rule, String from, List<Option> options) {

        public MemberIdRuleView {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** One choice of a list, by its value and in words. */
    public record Option(String value, String words) {
    }

    /**
     * One request shape (R-37).
     *
     * @param from        "default", "this environment" or "the collection's sample"
     * @param requestName for a value read from a sample, the collection request it was read from
     * @param lines       what following the sample leaves out, by key and type (D-309); null when nothing
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShapeView(String key, String label, String value, String from, String requestName, List<Option> options, String why,
                            List<String> lines) {

        public ShapeView {
            options = options == null ? List.of() : List.copyOf(options);
            lines = lines == null || lines.isEmpty() ? null : List.copyOf(lines);
        }

        public ShapeView(String key, String label, String value, String from, String requestName, List<Option> options, String why) {
            this(key, label, value, from, requestName, options, why, null);
        }
    }

    /**
     * One value (R-42): the known ones first, with a label and a line why, then any other name typed.
     *
     * @param typed   the value typed here, or absent when none is
     * @param inForce what a request reads for the name under this environment
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValueView(String name, String label, String why, String typed, InForce inForce) {
    }

    /**
     * A value in force and where it comes from.
     *
     * @param display     the value, a secret masked; absent when not set
     * @param source      THIS_ENVIRONMENT, SAMPLE, EXPORT, APPLICATION_YML, REQUEST (nothing here sets it, and a call takes it from
     *                    its own collection request, D-320) or NOT_SET
     * @param sourceLabel the source in words: "typed here", "from the collection's request \"<name>\"", "from the export's
     *                    variable <name>", "from application.yml", "not set"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InForce(String display, String source, String sourceLabel) {
    }

    /**
     * Where one operation goes (R-02), computed as if this environment were active.
     *
     * @param operation the key an address is typed under: an operation's name, or SUBSCRIPTION for
     *                  the base every subscription call appends to
     * @param shortLabel the operation in a word or two, served as "short" as plan 5.4 names it
     * @param typed     the address typed here, a credential in it masked; absent when none is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddressView(String operation, String label, @JsonProperty("short") String shortLabel, String typed,
                              AddressInForce inForce) {
    }

    /**
     * The address in force.
     *
     * @param display    the url with what resolved filled in, secrets masked
     * @param template   the url as configured
     * @param source     THIS_ENVIRONMENT, COLLECTION, APPLICATION_YML, DERIVED or NOT_SET
     * @param unresolved the placeholders with no value
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AddressInForce(String display, String template, String source, String sourceLabel, boolean resolved,
                                 List<String> unresolved) {

        public AddressInForce {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }
    }

    /** What a save answers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingsSaved(EnvironmentView environment, EnvironmentSettingsView settings, List<String> warnings, long epoch) {

        public SettingsSaved {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The input
    // ------------------------------------------------------------------------------------------

    /**
     * What {@code POST /api/config/environments/settings} takes (plan 5.4). Read strictly: a
     * property not named here is refused by name.
     *
     * @param id                 the environment to update; absent to make one
     * @param name               for a new environment with no export, its name; for an update, when given,
     *                           the stored name, since a stored environment is never renamed
     * @param kind               sandbox, uat or prod; required for a new environment
     * @param acknowledgeNotProd the confirmation that an environment that reads as production is not
     * @param environmentExport  a Postman environment export, as the page read it
     * @param collectionExport   a Postman collection export, stored in the same commit
     * @param signIn             absent keeps the stored sign-in
     * @param timeouts           absent keeps; a value null clears it
     * @param addresses          a key absent keeps; "" clears
     * @param memberIdRule       absent keeps; "" clears
     * @param shapes             a key absent keeps; "" clears
     * @param values             a key absent keeps; "" clears, and the export's value applies again
     * @param sampleValues       true to store, with the collection export, each value its samples state for a value nothing
     *                           is in force for (D-308); absent or false stores none
     */
    public record EnvironmentSettingsInput(String id, String name, String kind, Boolean acknowledgeNotProd, FileInput environmentExport,
                                           FileInput collectionExport, SignInInput signIn, TimeoutsInput timeouts,
                                           Map<String, String> addresses, String memberIdRule, Map<String, String> shapes,
                                           Map<String, String> values, Boolean sampleValues) {

        /** Names the parts given and never a value: the input carries secrets, exports and addresses. */
        @Override
        public String toString() {
            return "EnvironmentSettingsInput[" + (id == null ? "new" : id) + ", kind=" + kind + ", environmentExport="
                    + (environmentExport != null) + ", collectionExport=" + (collectionExport != null) + ", signIn=" + (signIn != null)
                    + ", addresses=" + (addresses == null ? List.of() : addresses.keySet()) + ", values="
                    + (values == null ? List.of() : values.keySet()) + "]";
        }
    }

    /** A file the page read and posts as text (R-06). */
    public record FileInput(String fileName, String text) {

        /** Holds the length only: the text can hold credentials and member data. */
        @Override
        public String toString() {
            return "FileInput[" + (text == null ? 0 : text.length()) + " characters]";
        }
    }

    /**
     * The sign-in as typed. A secret left blank keeps the stored one; its remove box removes it (R-04).
     *
     * @param method absent or blank: not stated, application.yml's sign-in applies
     */
    public record SignInInput(String method, String tokenUrl, String clientId, String scope, String clientAuthentication,
                              String clientSecret, Boolean removeClientSecret, String bearerToken, Boolean removeBearerToken) {

        /** Names the method and never a secret. */
        @Override
        public String toString() {
            return "SignInInput[" + method + "]";
        }
    }

    /**
     * Timeouts as typed. A property present and null clears the stored value; a property absent keeps
     * it, which Jackson tells apart only through these flags.
     */
    public static final class TimeoutsInput {
        private Integer connectMs;
        private Integer readMs;
        private boolean connectGiven;
        private boolean readGiven;

        public Integer getConnectMs() {
            return connectMs;
        }

        public void setConnectMs(Integer value) {
            this.connectMs = value;
            this.connectGiven = true;
        }

        public Integer getReadMs() {
            return readMs;
        }

        public void setReadMs(Integer value) {
            this.readMs = value;
            this.readGiven = true;
        }

        boolean connectGiven() {
            return connectGiven;
        }

        boolean readGiven() {
            return readGiven;
        }

        /** For a test or a caller in Java: both values given. */
        public static TimeoutsInput of(Integer connectMs, Integer readMs) {
            TimeoutsInput input = new TimeoutsInput();
            input.setConnectMs(connectMs);
            input.setReadMs(readMs);
            return input;
        }
    }
}
