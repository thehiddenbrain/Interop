package com.thehiddenbrain.interop.patientaccess.auth;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMART App Launch standalone launch: builds the authorize URL (state + PKCE), and on the callback
 * exchanges the code for tokens. Pending launches live in memory for ten minutes.
 */
@Service
public class SmartAuthCodeFlow {

    private static final Logger log = LoggerFactory.getLogger(SmartAuthCodeFlow.class);
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    public static final String CALLBACK_PATH = "/oauth/callback";

    private final TokenService tokens;
    private final SmartDiscoveryService discovery;
    private final EnvironmentService environments;
    private final WorkbenchProperties properties;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    record Pending(String environmentId, String verifier, String redirectUri, Instant startedAt) {
    }

    /** Outcome of a callback, rendered by the callback page. */
    public record Outcome(boolean success, String environmentId, String environmentName, String message, String patient) {
    }

    public SmartAuthCodeFlow(TokenService tokens, SmartDiscoveryService discovery, EnvironmentService environments,
                             WorkbenchProperties properties, Clock clock) {
        this.tokens = tokens;
        this.discovery = discovery;
        this.environments = environments;
        this.properties = properties;
        this.clock = clock;
    }

    /** The redirect URI registered with the authorization server for this workbench. */
    public String redirectUri(Environment env, String requestBaseUrl) {
        AuthConfig auth = env.auth();
        if (auth.redirectUri() != null && !auth.redirectUri().isBlank()) {
            return auth.redirectUri().trim();
        }
        String base = properties.publicBaseUrl() != null && !properties.publicBaseUrl().isBlank() ? properties.publicBaseUrl() : requestBaseUrl;
        while (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CALLBACK_PATH;
    }

    /** Builds the authorize URL; the caller redirects the browser there. */
    public String start(Environment env, String requestBaseUrl, String launchPatientHint) {
        AuthConfig auth = env.auth();
        if (auth.mode() != AuthMode.SMART_AUTHORIZATION_CODE) {
            throw new WorkbenchException(ErrorCode.NOT_SUPPORTED, "environment '" + env.name() + "' uses " + auth.mode()
                    + "; the SMART login is only for SMART_AUTHORIZATION_CODE");
        }
        OAuthEndpoints endpoints = discovery.endpointsFor(env, null);
        if (endpoints.authorizationEndpoint() == null) {
            throw new WorkbenchException(ErrorCode.AUTH_FAILED, "no authorization endpoint for environment '" + env.name()
                    + "': set auth.authorizationEndpoint or make sure /.well-known/smart-configuration is served");
        }
        purgeExpired();
        String state = Pkce.state();
        String verifier = auth.usePkce() ? Pkce.verifier() : null;
        String redirectUri = redirectUri(env, requestBaseUrl);
        pending.put(state, new Pending(env.id(), verifier, redirectUri, clock.instant()));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", auth.clientId());
        params.put("redirect_uri", redirectUri);
        params.put("scope", auth.scopes() == null || auth.scopes().isBlank() ? "launch/patient openid fhirUser offline_access patient/*.rs" : auth.scopes().trim());
        params.put("state", state);
        params.put("aud", auth.audience() == null || auth.audience().isBlank() ? env.baseUrl() : auth.audience().trim());
        if (verifier != null) {
            params.put("code_challenge", Pkce.challenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        if (launchPatientHint != null && !launchPatientHint.isBlank()) {
            params.put("login_hint", launchPatientHint.trim());
        }
        params.putAll(auth.extraAuthorizeParams());
        StringBuilder url = new StringBuilder(endpoints.authorizationEndpoint());
        url.append(endpoints.authorizationEndpoint().contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        log.info("SMART login started for environment '{}' (redirect {})", env.name(), redirectUri);
        return url.toString();
    }

    /** Handles the redirect back from the authorization server. */
    public Outcome callback(String code, String state, String error, String errorDescription) {
        purgeExpired();
        if (state == null || !pending.containsKey(state)) {
            return new Outcome(false, null, null, "unknown or expired state; start the login again", null);
        }
        Pending p = pending.remove(state);
        Environment env = environments.find(p.environmentId()).orElse(null);
        if (env == null) {
            return new Outcome(false, p.environmentId(), null, "environment no longer exists", null);
        }
        if (error != null) {
            return new Outcome(false, env.id(), env.name(), "authorization server returned " + error
                    + (errorDescription == null ? "" : ": " + errorDescription), null);
        }
        if (code == null || code.isBlank()) {
            return new Outcome(false, env.id(), env.name(), "no authorization code in the callback", null);
        }
        try {
            AccessToken token = tokens.exchangeCode(env, code, p.redirectUri(), p.verifier(), null);
            tokens.store(token);
            log.info("SMART login completed for environment '{}' (patient {})", env.name(), token.patient());
            return new Outcome(true, env.id(), env.name(), "token obtained" + (token.patient() == null ? "" : " for patient " + token.patient()),
                    token.patient());
        } catch (WorkbenchException e) {
            return new Outcome(false, env.id(), env.name(), e.getMessage(), null);
        }
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(PENDING_TTL);
        pending.entrySet().removeIf(e -> e.getValue().startedAt().isBefore(cutoff));
    }

    static String hostOf(String url) {
        return UrlBuilder.hostOf(url);
    }
}
