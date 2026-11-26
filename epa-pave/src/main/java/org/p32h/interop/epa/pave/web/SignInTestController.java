package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService.TokenFailure;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService.TokenResult;
import org.p32h.interop.epa.pave.client.support.SignInState;
import org.p32h.interop.epa.pave.client.support.TokenRefusal;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Test the sign-in" on Configuration > Access token: asks the token endpoint for a token now,
 * under the active environment's sign-in (or {@code onyx.auth} when it states none), so a tester
 * knows before a call whether the sign-in works. Sends nothing to the service itself.
 *
 * <p>Exists on both profiles, so that mock answers in words rather than with a missing route; the
 * token service exists on live only.
 */
@RestController
@RequestMapping("/api/config/sign-in")
public class SignInTestController {

    private static final Logger log = LoggerFactory.getLogger(SignInTestController.class);

    /** Every failure sentence of the token service ends so, written for a call; a test sends no call. */
    private static final String NOT_SENT = " The request was not sent.";

    /**
     * What {@code POST /api/config/sign-in/test} answers. Holds no token and no secret.
     *
     * @param state         the state the test found, by the names of {@link SignInState.Code}
     * @param line          one sentence for the screen
     * @param httpStatus    the token endpoint's status, when known. The token service does not hand
     *                      it on, so it is left out rather than guessed
     * @param elapsedMillis how long the token endpoint took; absent when nothing was asked
     * @param advice        the setting the token endpoint's answer points at, as the run card says it
     *                      (D-305); absent when the answer points at none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInTest(String state, String line, Integer httpStatus, Long elapsedMillis, String advice) {

        public SignInTest(String state, String line, Integer httpStatus, Long elapsedMillis) {
            this(state, line, httpStatus, elapsedMillis, null);
        }
    }

    private final ObjectProvider<OnyxTokenService> tokens;
    private final OperationGateway gateway;
    private final OnyxProperties onyx;

    public SignInTestController(ObjectProvider<OnyxTokenService> tokens, OperationGateway gateway, OnyxProperties onyx) {
        this.tokens = tokens;
        this.gateway = gateway;
        this.onyx = onyx;
    }

    @PostMapping("/test")
    public SignInTest test() {
        OnyxTokenService service = tokens == null ? null : tokens.getIfAvailable();
        if (service == null) {
            SignInState mock = SignInState.mock();
            return new SignInTest(mock.code().name(), mock.shortWords() + ". " + mock.detail(), null, null);
        }
        SignInSettings signIn = gateway.activeSignIn();
        SignInSettings.Method method = signIn == null ? (onyx.auth() != null && onyx.auth().enabled() ? SignInSettings.Method.CLIENT_CREDENTIALS
                : SignInSettings.Method.NONE) : signIn.method();
        String pasted = service.pastedHeld() ? " A pasted token is held and goes on calls ahead of it until a 401, a restart or a change "
                + "of the configuration." : "";
        if (method == SignInSettings.Method.NONE) {
            String where = signIn == null ? "this environment states no sign-in and onyx.auth in application.yml is switched off"
                    : "this environment's Sign-in says None";
            return new SignInTest(SignInState.Code.NONE.name(), "Nothing to test: " + where + ", so calls go without a token." + pasted,
                    null, null);
        }
        if (method == SignInSettings.Method.BEARER_TOKEN) {
            TokenResult stated = service.token(signIn);
            if (stated.failure() != null) {
                return new SignInTest(SignInState.Code.FAILED.name(), forATest(stated.failure()) + pasted, null, null);
            }
            return new SignInTest(SignInState.Code.BEARER.name(), "Nothing to ask a token endpoint: this environment's Sign-in states a "
                    + "bearer token, sent as it is. Whether the service accepts it shows on the first call." + pasted, null, null);
        }
        return clientCredentials(service, signIn, pasted);
    }

    /**
     * Asks the token endpoint afresh. A token still held from an earlier call would answer from the
     * cache and test nothing, so it is let go of first; the next call then uses the one asked for
     * here, and a failure here is what the state shows until a token is obtained.
     */
    private SignInTest clientCredentials(OnyxTokenService service, SignInSettings signIn, String pasted) {
        String from = signIn == null ? "client credentials, from onyx.auth in application.yml"
                : "client credentials, from this environment's Sign-in";
        // The pasted token is set aside by asking for the sign-in's own token, never through tokenFor.
        if (service.state(signIn).code() == SignInState.Code.SIGNED_IN || service.pastedHeld()) {
            TokenResult held = service.token(signIn);
            if (held.source() == OnyxTokenService.TokenSource.ISSUED) {
                service.refused(held);
            }
        }
        long started = System.nanoTime();
        TokenResult result = service.token(signIn);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (result.failure() != null) {
            // Outcome only: no url, client id or answer is logged.
            log.info("Sign-in test failed");
            Long asked = result.cause() == TokenFailure.SETTING_MISSING ? null : elapsed;
            // onyx.auth always sends a Basic header and offers no other way, so only an environment's sign-in is advised.
            String advice = signIn == null ? null : TokenRefusal.advice(result.failure(), signIn.clientAuthentication());
            return new SignInTest(SignInState.Code.FAILED.name(), forATest(result.failure()) + pasted, null, asked, advice);
        }
        // Outcome only.
        log.info("Sign-in test signed in");
        return new SignInTest(SignInState.Code.SIGNED_IN.name(), "Signed in: the token endpoint issued a token (" + from + ")." + pasted,
                null, elapsed);
    }

    /** The token service's sentence without its last words about a request, since a test sends none. */
    private static String forATest(String failure) {
        return failure.endsWith(NOT_SENT) ? failure.substring(0, failure.length() - NOT_SENT.length()) : failure;
    }
}
