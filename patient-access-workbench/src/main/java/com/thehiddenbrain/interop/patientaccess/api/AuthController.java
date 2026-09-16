package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.auth.AccessToken;
import com.thehiddenbrain.interop.patientaccess.auth.ClientAssertions;
import com.thehiddenbrain.interop.patientaccess.auth.OAuthEndpoints;
import com.thehiddenbrain.interop.patientaccess.auth.SmartAuthCodeFlow;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.auth.TokenService;
import com.thehiddenbrain.interop.patientaccess.auth.TokenStatus;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Map;

@RestController
@RequestMapping(value = "/api/v1/environments/{id}/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authorization", description = "Tokens, SMART discovery and the SMART login for an environment")
public class AuthController {

    private final EnvironmentService environments;
    private final TokenService tokens;
    private final SmartDiscoveryService discovery;
    private final SmartAuthCodeFlow smartFlow;
    private final SecretCrypto crypto;

    public AuthController(EnvironmentService environments, TokenService tokens, SmartDiscoveryService discovery,
                          SmartAuthCodeFlow smartFlow, SecretCrypto crypto) {
        this.environments = environments;
        this.tokens = tokens;
        this.discovery = discovery;
        this.smartFlow = smartFlow;
        this.crypto = crypto;
    }

    public record ManualToken(String accessToken, Long expiresInSeconds, String patient) {
    }

    public record SmartStart(String authorizeUrl, String redirectUri) {
    }

    @Operation(summary = "Token status (never the token itself)")
    @GetMapping("/status")
    public TokenStatus status(@PathVariable String id) {
        return tokens.status(environments.require(id));
    }

    @Operation(summary = "Obtain or refresh a token now (client credentials, backend services, or refresh of a SMART token)")
    @PostMapping("/token")
    public TokenStatus obtain(@PathVariable String id) {
        Environment env = environments.require(id);
        AccessToken previous = tokens.stored(id).orElse(null);
        tokens.obtain(env, previous, null);
        return tokens.status(env);
    }

    @Operation(summary = "Store a token pasted by the tester")
    @PostMapping(value = "/token/manual", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TokenStatus manual(@PathVariable String id, @RequestBody ManualToken body) {
        Environment env = environments.require(id);
        tokens.storeManual(env, body.accessToken(), body.expiresInSeconds(), body.patient());
        return tokens.status(env);
    }

    @Operation(summary = "Forget the stored token")
    @DeleteMapping("/token")
    public TokenStatus forget(@PathVariable String id) {
        Environment env = environments.require(id);
        tokens.forget(id);
        return tokens.status(env);
    }

    @Operation(summary = "Resolved OAuth endpoints (configured + discovered)")
    @GetMapping("/endpoints")
    public OAuthEndpoints endpoints(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
        Environment env = environments.require(id);
        if (refresh) {
            discovery.evict(id);
        }
        return discovery.endpointsFor(env, null);
    }

    @Operation(summary = "Start the SMART standalone launch; open authorizeUrl in the browser")
    @PostMapping("/smart/start")
    public SmartStart smartStart(@PathVariable String id, @RequestParam(required = false) String loginHint, HttpServletRequest request) {
        Environment env = environments.require(id);
        String base = ServletUriComponentsBuilder.fromRequestUri(request).replacePath(null).build().toUriString();
        return new SmartStart(smartFlow.start(env, base, loginHint), smartFlow.redirectUri(env, base));
    }

    @Operation(summary = "The redirect URI to register with the vendor for the SMART login")
    @GetMapping("/smart/redirect-uri")
    public Map<String, String> redirectUri(@PathVariable String id, HttpServletRequest request) {
        Environment env = environments.require(id);
        String base = ServletUriComponentsBuilder.fromRequestUri(request).replacePath(null).build().toUriString();
        return Map.of("redirectUri", smartFlow.redirectUri(env, base));
    }

    @Operation(summary = "Public JWK Set of the backend-services signing key, to register with the vendor")
    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks(@PathVariable String id) {
        Environment env = environments.require(id);
        return ClientAssertions.publicJwks(crypto.reveal(env.auth().privateKeyJwk()));
    }
}
