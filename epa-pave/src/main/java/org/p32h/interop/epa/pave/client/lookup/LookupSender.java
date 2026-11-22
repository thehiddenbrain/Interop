package org.p32h.interop.epa.pave.client.lookup;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.HeaderSetting;
import org.p32h.interop.epa.pave.service.config.LookupTarget;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.lookup.LookupBody;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.springframework.web.client.RestClient;

/**
 * Sends one lookup on the live profile, the same way for a member and for a provider.
 *
 * <p>The target comes from the gateway and from nowhere else: application.yml's lookup, or the
 * lookup saved on screen handed over as a {@link LookupTarget} (R-11). The gateway resolves the
 * url template, puts the identifier into the {@code {id}} token percent-encoded, returns the
 * headers and holds their secrets. The request leaves through the shared executor. Nothing here
 * builds a url, adds a configured header or opens a connection.
 *
 * <p>A saved POST lookup carries the identifier in its body, never in its url: the body template
 * is resolved by the gateway first and only then are its tokens filled, JSON-escaped, so nothing
 * typed into a card field is ever resolved as a placeholder.
 *
 * <p>Every refusal the gateway and the executor make for a lookup is turned into an answer here
 * and is not thrown. A lookup that is switched off, has no url, or is configured with a
 * placeholder nobody gave a value is DISABLED: the tester types the details by hand, and the
 * message says what is missing. A lookup overtaken by a change of configuration, or left without
 * the access token it was told to carry, is a FAULT that says nothing was sent.
 *
 * <p>Nothing here logs. The identifier is in the url or the body, and the two clients log the
 * outcome only.
 */
final class LookupSender {

    /** A read that expects JSON back. A header of the same name configured for the lookup replaces this one. */
    static final ExchangeOptions OPTIONS = ExchangeOptions.read("application/json");
    /** A saved POST lookup: JSON out and JSON back. */
    static final ExchangeOptions POST_OPTIONS = ExchangeOptions.json("application/json");

    /**
     * What came of one attempt. Either the call as it happened, or the reason nothing was sent.
     *
     * @param call    the call as it happened, whatever its outcome. Null when nothing was sent
     * @param notSent why nothing was sent. Null when a request went out
     * @param request the request that was prepared and not sent, when there is one worth showing
     */
    record Attempt(VendorExchange call, LookupOutcomes.Ending notSent, WireRequest request) {

        static Attempt of(VendorExchange call) {
            return new Attempt(call, null, null);
        }

        static Attempt notSent(LookupOutcomes.Ending why) {
            return new Attempt(null, why, null);
        }
    }

    private final EpaOperation operation;
    private final LookupKind kind;
    private final LookupWords words;
    private final LookupProperties.Endpoint endpoint;
    private final ExchangeExecutor executor;
    private final OnyxTokenService tokens;
    private final OperationGateway gateway;
    private final RestClient client;
    private final RestClient.Builder builder;

    /**
     * @param client  built with application.yml's timeouts of this lookup, which are shorter than the protocol timeouts
     * @param builder the builder a client with a saved lookup's own timeouts is made from, kept per pair of timeouts; null
     *                in tests that bind every call to {@code client}
     */
    LookupSender(EpaOperation operation, LookupWords words, LookupProperties.Endpoint endpoint, ExchangeExecutor executor,
                 OnyxTokenService tokens, OperationGateway gateway, RestClient client, RestClient.Builder builder) {
        this.operation = operation;
        this.kind = operation == EpaOperation.MEMBER_LOOKUP ? LookupKind.MEMBER : LookupKind.PROVIDER;
        this.words = words;
        this.endpoint = endpoint;
        this.executor = executor;
        this.tokens = tokens;
        this.gateway = gateway;
        this.client = client;
        this.builder = builder;
    }

    LookupSender(EpaOperation operation, LookupWords words, LookupProperties.Endpoint endpoint, ExchangeExecutor executor,
                 OnyxTokenService tokens, OperationGateway gateway, RestClient client) {
        this(operation, words, endpoint, executor, tokens, gateway, client, null);
    }

    /** application.yml's lookup. @param identifier the member ID in its no-space form, or the NPI */
    Attempt send(String identifier) {
        // Checked here, before the gateway is asked anything: a lookup that is switched off has no target to resolve.
        if (!endpoint.enabled()) {
            return Attempt.notSent(LookupOutcomes.switchedOff(words));
        }
        PreparedCall call;
        try {
            call = gateway.prepare(CallIntent.of(operation).withUrlValue("id", identifier));
        } catch (RuntimeException e) {
            return refused(e, words);
        }
        Attempt incomplete = unusableUrl(call, words);
        if (incomplete != null) {
            return incomplete;
        }
        ExchangeOptions options = OPTIONS;
        OnyxTokenService.TokenResult token = null;
        try {
            if (endpoint.useOnyxAuth() && !carriesItsOwnCredential(call)) {
                // Before the token is fetched, for the reason OnyxAccess gives: the token belongs to the
                // configuration that is current now, and a call prepared under an earlier one must not get it.
                Exchanges.requireSendable(call, null, gateway);
                // The token the environment's own calls carry: its sign-in, or a token the tester pasted (R-43).
                token = tokens.tokenFor(call.signIn());
                if (token.failure() != null) {
                    return new Attempt(null, LookupOutcomes.noAccessToken(words, token.failure()),
                            Exchanges.wireRequest(call, null, options, false, token.failure()));
                }
                if (token.token() != null) {
                    options = options.withBearerToken(token.token());
                }
            }
            return Attempt.of(answered(executor.execute(client, call, null, options), token));
        } catch (RuntimeException e) {
            return notSentAtTheExecutor(e, call, null, options, words);
        }
    }

    /**
     * The lookup saved on screen (R-11).
     *
     * @param settings      the saved lookup, checked when it was saved and when it was read back
     * @param identifier    the member ID in the lookup's own form, or the NPI
     * @param dateOfService the case's date of service, or null when it has none
     */
    Attempt send(LookupSettings settings, String identifier, LocalDate dateOfService) {
        LookupWords saved = words.onScreen(kind);
        if (!settings.on()) {
            return Attempt.notSent(LookupOutcomes.switchedOff(saved));
        }
        LookupSettings.SignIn signIn = settings.signIn();
        List<HeaderSetting> headers = new ArrayList<>();
        // Every header value of a saved lookup is held as a secret: an extra header is as often a key as not.
        settings.headers().forEach(header -> headers.add(new HeaderSetting(header.name(), header.value(), true)));
        if (LookupSettings.SignIn.API_KEY.equals(signIn.method())) {
            headers.add(new HeaderSetting(signIn.headerName(), signIn.apiKey(), true));
        } else if (LookupSettings.SignIn.BEARER_TOKEN.equals(signIn.method())) {
            headers.add(new HeaderSetting("Authorization", "Bearer " + signIn.bearerToken(), true));
        }
        boolean environment = LookupSettings.SignIn.ENVIRONMENT.equals(signIn.method());
        CallIntent intent = CallIntent.of(operation).withLookup(new LookupTarget(settings.method(), settings.address(), headers, environment,
                settings.connectMs(), settings.readMs()));
        if (!settings.post()) {
            intent = intent.withUrlValue(LookupKind.ID_TOKEN, identifier);
        }
        PreparedCall call;
        try {
            call = gateway.prepare(intent);
        } catch (RuntimeException e) {
            return refused(e, saved);
        }
        Attempt incomplete = unusableUrl(call, saved);
        if (incomplete != null) {
            return incomplete;
        }

        ResolvedText body = null;
        ExchangeOptions options = OPTIONS;
        if (settings.post()) {
            boolean dated = LookupBody.tokens(settings.bodyTemplate()).contains(LookupKind.DATE_TOKEN);
            if (dated && dateOfService == null) {
                return Attempt.notSent(LookupOutcomes.noDateOfService(saved));
            }
            // Resolved before the tokens are filled, so a member ID that looks like a placeholder is never resolved.
            ResolvedText template = call.resolve(settings.bodyTemplate());
            if (template == null || !template.resolved()) {
                return Attempt.notSent(LookupOutcomes.incomplete(saved, template == null ? List.of() : template.unresolved(),
                        "its body template", call.environment() == null ? null : call.environment().name(), null));
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put(kind.token(), identifier);
            if (dated) {
                values.put(LookupKind.DATE_TOKEN, DateTimeFormatter.ofPattern(settings.dateFormat(), Locale.ROOT).format(dateOfService));
            }
            body = new ResolvedText(template.template(), LookupBody.fill(template.display(), values), LookupBody.fill(template.value(), values),
                    template.variables(), template.unresolved());
            options = POST_OPTIONS;
        }

        OnyxTokenService.TokenResult token = null;
        try {
            if (LookupSettings.SignIn.CLIENT_CREDENTIALS.equals(signIn.method()) || environment) {
                Exchanges.requireSendable(call, body, gateway);
                if (environment) {
                    token = tokens.tokenFor(call.signIn());
                } else {
                    String missing = missingCredential(signIn);
                    if (missing != null) {
                        return Attempt.notSent(LookupOutcomes.noAccessToken(saved, missing));
                    }
                    token = tokens.token(SignInSettings.clientCredentials(signIn.tokenUrl(), signIn.clientId(), signIn.clientSecret(),
                            signIn.scope(), SignInSettings.ClientAuthentication.parse(signIn.clientAuthentication())));
                }
                if (token.failure() != null) {
                    return new Attempt(null, LookupOutcomes.noAccessToken(saved, ownWords(token.failure(), environment)),
                            Exchanges.wireRequest(call, body, options, false, token.failure()));
                }
                if (token.token() != null && !carriesItsOwnCredential(call)) {
                    options = options.withBearerToken(token.token());
                }
            }
            return Attempt.of(answered(executor.execute(clientFor(call), call, body, options), token));
        } catch (RuntimeException e) {
            return notSentAtTheExecutor(e, call, body, options, saved);
        }
    }

    /** A 401 tells the token service its token was refused: a pasted one is let go of, an issued one asked for again next time. */
    private VendorExchange answered(VendorExchange exchange, OnyxTokenService.TokenResult token) {
        if (token != null && exchange != null && exchange.httpStatus() != null && exchange.httpStatus() == 401) {
            tokens.refused(token);
        }
        return exchange;
    }

    /**
     * Which of the lookup's own client credentials is missing, by the name of its field, or null.
     * Checked here so the sentence names the lookup's settings and not the environment's, which
     * the token service's own sentences are written for.
     */
    private String missingCredential(LookupSettings.SignIn signIn) {
        List<String> missing = new ArrayList<>();
        if (!resolves(signIn.tokenUrl())) {
            missing.add("Token url");
        }
        if (!resolves(signIn.clientId())) {
            missing.add("Client id");
        }
        if (!resolves(signIn.clientSecret())) {
            missing.add("Client secret");
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "Its " + String.join(" and ", missing) + (missing.size() == 1 ? " is" : " are") + " not set, or names a placeholder the "
                + "active environment does not define. The request was not sent.";
    }

    private boolean resolves(String value) {
        if (Texts.blank(value)) {
            return false;
        }
        if (!value.contains("{{")) {
            return true;
        }
        ResolvedText resolved = gateway.resolve(value, Map.of());
        return resolved != null && resolved.resolved() && Texts.present(resolved.value());
    }

    /** The token service words a failure for the environment; for the lookup's own sign-in the token endpoint's part is what holds. */
    private static String ownWords(String failure, boolean environment) {
        return environment ? failure : Texts.orElse(failure, "").replace("This environment signs in", "The lookup signs in");
    }

    /** A client with the call's timeouts: a saved lookup's own over application.yml's lookup timeouts. */
    private RestClient clientFor(PreparedCall call) {
        if (builder == null) {
            return client;
        }
        return RestClients.cached(builder, call.timeouts().connectOr(endpoint.connectTimeoutMs()),
                call.timeouts().readOr(endpoint.readTimeoutMs()));
    }

    /** Every refusal the gateway makes while preparing the call, as an answer. Anything else is not the gateway's and is thrown on. */
    private Attempt refused(RuntimeException e, LookupWords words) {
        if (e instanceof EndpointNotConfiguredException) {
            return Attempt.notSent(LookupOutcomes.notConfigured(words));
        }
        if (e instanceof UnresolvedVariablesException unresolved) {
            return Attempt.notSent(LookupOutcomes.incomplete(words, unresolved.variables(), "its url", unresolved.environmentName(),
                    unresolved.reasons()));
        }
        if (e instanceof BadInputException) {
            // The gateway refuses a url value it has no token for. Its message is not used: it may name the value, which is the identifier.
            return Attempt.notSent(LookupOutcomes.noPlaceForTheIdentifier(words));
        }
        if (e instanceof ProdWriteBlockedException blocked) {
            // The gateway's refusal names the header, the request or the environment, never the identifier or a value.
            return Attempt.notSent(LookupOutcomes.refusedByTheGuardrail(words, blocked.getMessage(), blocked.action()));
        }
        if (e instanceof ConfigurationChangedException) {
            return Attempt.notSent(LookupOutcomes.configurationChanged(words));
        }
        throw e;
    }

    /** A gateway that hands back an incomplete target without refusing it gets the same answers. Null when the url can be called. */
    private static Attempt unusableUrl(PreparedCall call, LookupWords words) {
        ResolvedUrl url = call.url();
        if (url == null || !url.configured()) {
            return Attempt.notSent(LookupOutcomes.notConfigured(words));
        }
        if (!url.resolved()) {
            return Attempt.notSent(LookupOutcomes.incomplete(words, url.unresolved(), "its url", call.environment().name(), null));
        }
        return null;
    }

    /** The refusals of the executor and of the check before a token, as answers. Anything else is thrown on. */
    private static Attempt notSentAtTheExecutor(RuntimeException e, PreparedCall call, ResolvedText body, ExchangeOptions options,
                                                LookupWords words) {
        if (e instanceof UnresolvedVariablesException unresolved) {
            // A configured header still holds a placeholder. The executor refuses to send without it.
            return Attempt.notSent(LookupOutcomes.incomplete(words, unresolved.variables(),
                    unresolved.template() != null && unresolved.template().startsWith("the header ") ? unresolved.template()
                            : "one of its headers", unresolved.environmentName(), unresolved.reasons()));
        }
        if (e instanceof EndpointNotConfiguredException) {
            return Attempt.notSent(LookupOutcomes.urlCannotBeCalled(words));
        }
        if (e instanceof BadInputException) {
            return Attempt.notSent(LookupOutcomes.notValidHttp(words));
        }
        if (e instanceof ConfigurationChangedException) {
            return new Attempt(null, LookupOutcomes.configurationChanged(words), Exchanges.wireRequest(call, body, options, false,
                    "Not sent. The configuration changed after this request was prepared."));
        }
        throw e;
    }

    /** A lookup configured with its own Authorization header is sent with that, and no token is fetched for it. */
    private static boolean carriesItsOwnCredential(PreparedCall call) {
        return call.headers().stream().anyMatch(header -> "authorization".equalsIgnoreCase(header.name()));
    }
}
