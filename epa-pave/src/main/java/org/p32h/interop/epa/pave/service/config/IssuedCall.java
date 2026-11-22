package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.SentSecrets;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** The only implementation of {@link PreparedCall}. Package-private, so only the gateway in this package mints one. */
final class IssuedCall implements PreparedCall {

    private final EpaOperation operation;
    private final String method;
    private final ResolvedUrl url;
    private final List<ResolvedHeader> headers;
    private final EnvironmentRef environment;
    private final String requestName;
    private final boolean scripted;
    private final LongSupplier epoch;
    private final Function<String, ResolvedText> resolver;
    private final String savedBody;
    private final java.util.function.Supplier<SentSecrets> held;
    private volatile SentSecrets heldOnce;
    private final Timeouts timeouts;
    private final SignInSettings signIn;
    private final MemberIdRule memberIdRule;
    private final RequestShapes shapes;
    private final String callbackUrl;

    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, long epoch, Function<String, ResolvedText> resolver) {
        this(operation, method, url, headers, environment, requestName, scripted, () -> epoch, resolver);
    }

    /**
     * @param epoch the epoch the call belongs to, asked each time. For a write that an unlock let
     *              through it stops answering the epoch it was prepared in once that unlock is no
     *              longer held, so a relock makes the call stale without moving the epoch that
     *              suites and screens are pinned to
     */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver) {
        this(operation, method, url, headers, environment, requestName, scripted, epoch, resolver, null);
    }

    /** @param savedBody the raw body saved with the collection request, as a template, or null */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver, String savedBody) {
        this(operation, method, url, headers, environment, requestName, scripted, epoch, resolver, savedBody, SentSecrets::none);
    }

    /**
     * @param held the secrets this tool holds for the configuration the call was prepared in,
     *             worked out when first asked: most calls prepared for a preview never have an
     *             answer to mask
     */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver, String savedBody,
               java.util.function.Supplier<SentSecrets> held) {
        this(operation, method, url, headers, environment, requestName, scripted, epoch, resolver, savedBody, held, Timeouts.APPLICATION);
    }

    /** @param timeouts the connect and read timeouts in force for this call, both present */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver, String savedBody,
               java.util.function.Supplier<SentSecrets> held, Timeouts timeouts) {
        this(operation, method, url, headers, environment, requestName, scripted, epoch, resolver, savedBody, held, timeouts, Settings.NONE);
    }

    /**
     * What the environment's settings give one call (plan 5.2), read from the snapshot the call was
     * prepared against.
     *
     * @param signIn       the environment's sign-in, its values resolved where they resolve; null when it states none
     * @param memberIdRule the rule it states, or null for packed
     * @param shapes       each request shape with its source, or null for the defaults
     * @param callbackUrl  the callback url it types (R-42), as a template, or null when it types none
     */
    record Settings(SignInSettings signIn, MemberIdRule memberIdRule, RequestShapes shapes, String callbackUrl) {

        static final Settings NONE = new Settings(null, null, null, null);

        /** Names what is set and never a value: the sign-in holds secrets, the callback url can hold a credential. */
        @Override
        public String toString() {
            return "Settings[" + signIn + ", " + memberIdRule + ", " + shapes + ", callbackUrl " + (callbackUrl == null ? "not set" : "set") + "]";
        }
    }

    /** @param settings what the environment's settings give this call */
    IssuedCall(EpaOperation operation, String method, ResolvedUrl url, List<ResolvedHeader> headers, EnvironmentRef environment,
               String requestName, boolean scripted, LongSupplier epoch, Function<String, ResolvedText> resolver, String savedBody,
               java.util.function.Supplier<SentSecrets> held, Timeouts timeouts, Settings settings) {
        this.operation = operation;
        this.method = method;
        this.url = url;
        this.headers = headers == null ? List.of() : List.copyOf(headers);
        this.environment = environment == null ? EnvironmentRef.none() : environment;
        this.requestName = requestName;
        this.scripted = scripted;
        this.epoch = epoch;
        this.resolver = resolver;
        this.savedBody = savedBody;
        this.held = held == null ? SentSecrets::none : held;
        // A value left out here would fail in the client that builds its connection from it, far
        // from where the call was prepared, so each missing one is filled where it is known.
        this.timeouts = timeouts == null ? Timeouts.APPLICATION : timeouts.over(Timeouts.APPLICATION);
        Settings given = settings == null ? Settings.NONE : settings;
        this.signIn = given.signIn();
        this.memberIdRule = given.memberIdRule() == null ? MemberIdRule.PACKED : given.memberIdRule();
        this.shapes = given.shapes() == null ? RequestShapes.DEFAULTS : given.shapes();
        this.callbackUrl = given.callbackUrl();
    }

    @Override
    public SignInSettings signIn() {
        return signIn;
    }

    @Override
    public MemberIdRule memberIdRule() {
        return memberIdRule;
    }

    @Override
    public RequestShapes shapes() {
        return shapes;
    }

    @Override
    public String callbackUrl() {
        return callbackUrl;
    }

    @Override
    public EpaOperation operation() {
        return operation;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public ResolvedUrl url() {
        return url;
    }

    @Override
    public List<ResolvedHeader> headers() {
        return headers;
    }

    @Override
    public EnvironmentRef environment() {
        return environment;
    }

    @Override
    public String requestName() {
        return requestName;
    }

    @Override
    public boolean scripted() {
        return scripted;
    }

    @Override
    public long epoch() {
        return epoch.getAsLong();
    }

    @Override
    public ResolvedText resolve(String template) {
        return template == null ? null : resolver.apply(template);
    }

    @Override
    public String savedBody() {
        return savedBody;
    }

    @Override
    public SentSecrets heldSecrets() {
        SentSecrets known = heldOnce;
        if (known == null) {
            // Worked out at most a few times when two threads ask at once, each time from the same snapshot.
            known = held.get();
            heldOnce = known;
        }
        return known;
    }

    @Override
    public Timeouts timeouts() {
        return timeouts;
    }

    /**
     * Prints the url template, never the resolved url. For a lookup the resolved url holds the
     * member ID, so it must not reach a log through toString.
     */
    @Override
    public String toString() {
        return "PreparedCall[" + operation + " " + method + " " + (url == null ? "" : url.template()) + "]";
    }
}
