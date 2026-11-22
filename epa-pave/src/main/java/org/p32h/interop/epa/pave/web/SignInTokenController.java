package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.ConfigService;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A token the tester pasted for this session (R-43): held in server memory for the active
 * environment, used ahead of the environment's own sign-in, and never written anywhere. The team
 * this tool serves makes tokens by hand in Postman, so a token that works there can be tried here
 * without storing it.
 *
 * <p>Exists on both profiles so that mock answers with a refusal in words rather than a missing
 * route; the token service exists on live only, and on mock nothing signs in.
 */
@RestController
@RequestMapping("/api/config/sign-in")
public class SignInTokenController {

    /**
     * What {@code POST /api/config/sign-in/token} takes.
     *
     * @param token       the access token, as pasted
     * @param environment the id of the environment it is for, which must be the active one
     */
    public record TokenRequest(String token, String environment) {

        /** Names neither value: the token is a credential. */
        @Override
        public String toString() {
            return "TokenRequest[redacted]";
        }
    }

    /** @param held whether a pasted token is held now */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Held(boolean held) {
    }

    private final ObjectProvider<OnyxTokenService> tokens;
    private final ConfigService config;

    public SignInTokenController(ObjectProvider<OnyxTokenService> tokens, ConfigService config) {
        this.tokens = tokens;
        this.config = config;
    }

    /** Holds the token for the active environment. 422 on mock, for another environment and for a blank token. */
    @PostMapping("/token")
    public Held paste(@RequestBody TokenRequest request) {
        OnyxTokenService service = tokens.getIfAvailable();
        if (service == null) {
            throw new ValidationException("No sign-in on mock: the mock profile answers every call itself, so a pasted token is not "
                    + "used.", List.of(), "Switch to live in the top bar to use a pasted token.");
        }
        if (request == null || Texts.blank(request.token())) {
            throw ValidationException.of("token", "Paste the access token.");
        }
        if (request.token().strip().chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            // A space, a line break or a character outside visible ASCII cannot travel in a header; the value itself is never repeated.
            throw ValidationException.of("token", "This token holds a space, a line break or a character an HTTP header cannot carry. "
                    + "Paste the token alone.");
        }
        EnvironmentView active = activeEnvironment();
        if (active == null) {
            throw new ValidationException("No environment is active, so there is nothing to hold the token for.",
                    List.of(new FieldError("environment", "Make an environment active in Configuration > Environments first.")));
        }
        if (Texts.blank(request.environment()) || !request.environment().strip().equals(active.id())) {
            // A token pasted while another environment was shown must not be sent to the active one's service.
            throw ValidationException.of("environment", "The token is held only for the active environment, and this is another one. "
                    + "Open Access token again and paste it there.");
        }
        service.paste(request.token(), active.name());
        return new Held(true);
    }

    /** Lets go of the pasted token. The environment's own sign-in applies again. */
    @DeleteMapping("/token")
    public Held forget() {
        OnyxTokenService service = tokens.getIfAvailable();
        if (service != null) {
            service.forgetPasted();
        }
        return new Held(false);
    }

    private EnvironmentView activeEnvironment() {
        for (EnvironmentView view : config.environments().environments()) {
            if (view.active()) {
                return view;
            }
        }
        return null;
    }
}
