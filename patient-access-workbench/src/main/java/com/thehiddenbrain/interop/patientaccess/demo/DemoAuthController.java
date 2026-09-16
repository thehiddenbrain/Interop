package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The demo OAuth 2.0 / SMART authorization server at {@code /demo/auth}: token endpoint (client credentials
 * with basic / post / JWT-assertion client authentication, authorization code with PKCE, refresh), a plain HTML
 * authorize page where the tester picks the demo patient to sign in as, an empty JWKS and a stub registration
 * endpoint. Everything is deliberately simple: one client, no consent screen, no signature verification.
 */
@RestController
@RequestMapping("/demo/auth")
@DemoEnabled
public class DemoAuthController {

    public static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    static final String BASE_PATH = "/demo/auth";

    private record ClientAuth(String clientId, boolean confidential) {
    }

    private final DemoAuthService auth;
    private final DemoDataStore store;
    private final Clock clock;

    public DemoAuthController(DemoAuthService auth, DemoDataStore store, Clock clock) {
        this.auth = auth;
        this.store = store;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ token

    @PostMapping(value = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        Map<String, String> form = firstValues(request);
        String grant = form.get("grant_type");
        if (grant == null || grant.isBlank()) {
            throw new DemoAuthService.OAuthException(400, "invalid_request", "grant_type is required");
        }
        ClientAuth client = authenticateClient(request, form);
        String fhirBase = serverBase(request) + DemoFhirController.BASE_PATH;
        String issuer = serverBase(request) + BASE_PATH;
        Map<String, Object> body = new LinkedHashMap<>();
        switch (grant) {
            case "client_credentials" -> {
                if (!client.confidential()) {
                    throw new DemoAuthService.OAuthException(401, "invalid_client", "client_credentials needs client authentication "
                            + "(client_secret_basic, client_secret_post or a private_key_jwt client_assertion)");
                }
                DemoAuthService.Token t = auth.issueSystemToken(client.clientId(), form.get("scope"),
                        form.containsKey("client_assertion") ? "backend_services" : "client_credentials");
                fill(body, t);
            }
            case "authorization_code" -> {
                DemoAuthService.Token t = auth.redeemCode(form.get("code"), client.clientId(), form.get("redirect_uri"), form.get("code_verifier"));
                fill(body, t);
                body.put("refresh_token", t.refreshToken());
                body.put("patient", t.patientId());
                body.put("id_token", auth.idToken(t, issuer, fhirBase));
            }
            case "refresh_token" -> {
                DemoAuthService.Token t = auth.refresh(form.get("refresh_token"), client.clientId());
                fill(body, t);
                body.put("refresh_token", t.refreshToken());
                body.put("patient", t.patientId());
            }
            default -> throw new DemoAuthService.OAuthException(400, "unsupported_grant_type", "grant_type '" + grant
                    + "' is not supported (client_credentials, authorization_code, refresh_token)");
        }
        return ResponseEntity.ok().headers(noStore()).body(body);
    }

    private static void fill(Map<String, Object> body, DemoAuthService.Token t) {
        body.put("access_token", t.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", DemoAuthService.TOKEN_TTL_SECONDS);
        body.put("scope", t.scope());
    }

    /**
     * RFC 6749 client authentication: HTTP basic (id and secret form-urlencoded before base64), form fields,
     * or a JWT client assertion (SMART Backend Services). Only {@code client_id} makes a public client, which the
     * authorization-code and refresh grants accept.
     */
    private ClientAuth authenticateClient(HttpServletRequest request, Map<String, String> form) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            String[] parts;
            try {
                parts = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8).split(":", 2);
            } catch (IllegalArgumentException e) {
                parts = new String[0];
            }
            if (parts.length != 2 || !auth.clientSecretMatches(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8))) {
                throw new DemoAuthService.OAuthException(401, "invalid_client", "unknown client or wrong secret in the Authorization header");
            }
            return new ClientAuth(auth.clientId(), true);
        }
        if (JWT_BEARER.equals(form.get("client_assertion_type")) || form.containsKey("client_assertion")) {
            return new ClientAuth(assertionClient(form.get("client_assertion")), true);
        }
        if (form.containsKey("client_secret")) {
            if (!auth.clientSecretMatches(form.get("client_id"), form.get("client_secret"))) {
                throw new DemoAuthService.OAuthException(401, "invalid_client", "unknown client_id or wrong client_secret");
            }
            return new ClientAuth(auth.clientId(), true);
        }
        String clientId = form.get("client_id");
        if (clientId == null || clientId.isBlank()) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "no client authentication: send client_secret_basic, "
                    + "client_secret_post, a client_assertion, or at least client_id for a public client");
        }
        if (!clientId.equals(auth.clientId())) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "unknown client_id '" + clientId + "'");
        }
        return new ClientAuth(clientId, false);
    }

    /**
     * Accepts any structurally valid, unexpired JWT whose iss (and sub) is the demo client id. The signature is
     * not verified: the demo has no key registry, and the point is to exercise the workbench's assertion building,
     * not to protect sample data. A real server checks the signature against the client's registered JWKS.
     */
    private String assertionClient(String assertion) {
        if (assertion == null || assertion.isBlank()) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion is required with " + JWT_BEARER);
        }
        JWTClaimsSet claims;
        try {
            JWT jwt = JWTParser.parse(assertion);
            if (jwt instanceof PlainJWT) {
                throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion must be a signed JWT (alg none is not accepted)");
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion is not a JWT: " + e.getMessage());
        }
        String iss = claims.getIssuer();
        if (iss == null || !iss.equals(auth.clientId())) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion iss must be the client id '" + auth.clientId() + "'");
        }
        if (claims.getSubject() != null && !claims.getSubject().equals(iss)) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion sub must equal iss");
        }
        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(clock.instant())) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion is expired or has no exp");
        }
        if (claims.getAudience() == null || claims.getAudience().isEmpty()) {
            throw new DemoAuthService.OAuthException(401, "invalid_client", "client_assertion has no aud (must be the token endpoint URL)");
        }
        return iss;
    }

    // ------------------------------------------------------------------ authorize

    /** The "login" page: one sign-in form per demo patient. Errors in client_id / redirect_uri are shown, never redirected (RFC 6749 4.1.2.1). */
    @GetMapping(value = "/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorizePage(HttpServletRequest request) {
        Map<String, String> q = firstValues(request);
        String problem = clientProblem(q);
        if (problem != null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body(errorPage(problem));
        }
        String redirectUri = q.get("redirect_uri");
        if (!"code".equals(q.get("response_type"))) {
            return redirectError(redirectUri, q.get("state"), "unsupported_response_type", "response_type must be 'code'");
        }
        String method = q.get("code_challenge_method");
        if (method != null && !method.equals("S256") && !method.equals("plain")) {
            return redirectError(redirectUri, q.get("state"), "invalid_request", "code_challenge_method must be S256");
        }
        if (method != null && (q.get("code_challenge") == null || q.get("code_challenge").isBlank())) {
            return redirectError(redirectUri, q.get("state"), "invalid_request", "code_challenge is required with code_challenge_method");
        }
        // SMART requires aud = the FHIR base; only the path is compared because the tester may reach the workbench
        // through localhost, 127.0.0.1 or a container host name.
        String aud = q.get("aud");
        if (aud != null && !aud.isBlank() && !aud.replaceAll("/+$", "").endsWith(DemoFhirController.BASE_PATH)) {
            return redirectError(redirectUri, q.get("state"), "invalid_request", "aud must be the FHIR base URL ending in " + DemoFhirController.BASE_PATH);
        }
        // The workbench's global CSP allows form posts to 'self' only; the browser applies form-action to the redirect
        // that follows the POST as well, so the client's origin is allowed explicitly on this page.
        String csp = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' " + origin(redirectUri);
        return ResponseEntity.ok().header("Content-Security-Policy", csp).headers(noStore()).contentType(MediaType.TEXT_HTML).body(loginPage(q));
    }

    /** The tester chose a patient (or denied): mint a one-time code bound to the request and send the browser back. */
    @PostMapping("/authorize")
    public ResponseEntity<String> authorizeSubmit(HttpServletRequest request) {
        Map<String, String> form = firstValues(request);
        String problem = clientProblem(form);
        if (problem != null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body(errorPage(problem));
        }
        String redirectUri = form.get("redirect_uri");
        String state = form.get("state");
        if ("deny".equals(form.get("decision"))) {
            return redirectError(redirectUri, state, "access_denied", "the user denied the request");
        }
        String patientId = form.get("patient");
        if (patientId == null || store.find("Patient", patientId).isEmpty()) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body(errorPage("unknown demo patient '" + patientId + "'"));
        }
        String code = auth.createCode(form.get("client_id"), redirectUri, blankToNull(form.get("code_challenge")),
                form.get("code_challenge_method") == null ? (form.get("code_challenge") == null ? null : "S256") : form.get("code_challenge_method"),
                form.get("scope"), patientId);
        String location = append(redirectUri, "code=" + encode(code) + (state == null ? "" : "&state=" + encode(state)));
        return ResponseEntity.status(HttpStatus.FOUND).headers(noStore()).header(HttpHeaders.LOCATION, location).build();
    }

    /** Null when client_id and redirect_uri are acceptable, else what is wrong. */
    private String clientProblem(Map<String, String> q) {
        String clientId = q.get("client_id");
        if (clientId == null || !clientId.equals(auth.clientId())) {
            return "unknown client_id" + (clientId == null ? "" : " '" + clientId + "'") + "; the demo client id is '" + auth.clientId() + "'";
        }
        String redirectUri = q.get("redirect_uri");
        if (redirectUri == null || redirectUri.isBlank()) {
            return "redirect_uri is required";
        }
        try {
            URI uri = new URI(redirectUri);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getFragment() != null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                return "redirect_uri must be an absolute http(s) URL without fragment";
            }
        } catch (URISyntaxException e) {
            return "redirect_uri is not a valid URL";
        }
        // Any absolute URI is accepted because demo clients are not registered; a real server matches the registered URIs.
        return null;
    }

    private static ResponseEntity<String> redirectError(String redirectUri, String state, String error, String description) {
        String location = append(redirectUri, "error=" + error + "&error_description=" + encode(description) + (state == null ? "" : "&state=" + encode(state)));
        return ResponseEntity.status(HttpStatus.FOUND).headers(noStore()).header(HttpHeaders.LOCATION, location).build();
    }

    private String loginPage(Map<String, String> q) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Demo sign-in</title><style>")
                .append("body{font-family:system-ui,sans-serif;margin:0;background:#f4f6f8;color:#1f2933}")
                .append("main{max-width:560px;margin:48px auto;background:#fff;border:1px solid #d9dee3;border-radius:8px;padding:28px}")
                .append("h1{font-size:1.4rem;margin:0 0 6px}p{margin:0 0 18px;color:#52606d}")
                .append("form{margin:0 0 10px}button{width:100%;text-align:left;padding:12px 14px;border:1px solid #cbd2d9;border-radius:6px;background:#fff;font-size:1rem;cursor:pointer}")
                .append("button:hover{background:#eef2f6}button.deny{color:#a1281f;border-color:#e3b1ad}small{display:block;color:#52606d;margin-top:3px}")
                .append("dl{font-size:.85rem;color:#52606d;margin:18px 0 0}dt{font-weight:600}dd{margin:0 0 6px;word-break:break-all}")
                .append("</style></head><body><main><h1>Patient Access Workbench demo server</h1>")
                .append("<p>Sign in as one of the demo members. The client <code>").append(HtmlUtils.htmlEscape(q.get("client_id")))
                .append("</code> asks for the scopes <code>").append(HtmlUtils.htmlEscape(q.getOrDefault("scope", DemoAuthService.DEFAULT_PATIENT_SCOPE)))
                .append("</code>.</p>");
        for (JsonNode patient : store.patients()) {
            html.append("<form method=\"post\" action=\"").append(HtmlUtils.htmlEscape(BASE_PATH + "/authorize")).append("\">");
            hidden(html, q);
            html.append("<input type=\"hidden\" name=\"patient\" value=\"").append(HtmlUtils.htmlEscape(patient.path("id").asText())).append("\">")
                    .append("<button type=\"submit\">Sign in as ").append(HtmlUtils.htmlEscape(displayName(patient)))
                    .append("<small>Patient/").append(HtmlUtils.htmlEscape(patient.path("id").asText()))
                    .append(" &middot; born ").append(HtmlUtils.htmlEscape(patient.path("birthDate").asText("?")))
                    .append(" &middot; member id ").append(HtmlUtils.htmlEscape(memberId(patient))).append("</small></button></form>");
        }
        html.append("<form method=\"post\" action=\"").append(HtmlUtils.htmlEscape(BASE_PATH + "/authorize")).append("\">");
        hidden(html, q);
        html.append("<input type=\"hidden\" name=\"decision\" value=\"deny\"><button type=\"submit\" class=\"deny\">Deny access</button></form>");
        html.append("<dl><dt>redirect_uri</dt><dd>").append(HtmlUtils.htmlEscape(q.get("redirect_uri"))).append("</dd>");
        if (q.get("code_challenge") != null) {
            html.append("<dt>PKCE</dt><dd>").append(HtmlUtils.htmlEscape(q.getOrDefault("code_challenge_method", "S256"))).append(" challenge present</dd>");
        }
        html.append("</dl></main></body></html>");
        return html.toString();
    }

    private static void hidden(StringBuilder html, Map<String, String> q) {
        for (String name : List.of("response_type", "client_id", "redirect_uri", "scope", "state", "aud", "code_challenge", "code_challenge_method")) {
            String v = q.get(name);
            if (v != null) {
                html.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"").append(HtmlUtils.htmlEscape(v)).append("\">");
            }
        }
    }

    private static String errorPage(String problem) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Demo sign-in error</title></head><body style=\"font-family:system-ui,sans-serif;padding:32px\">"
                + "<h1>Authorization request rejected</h1><p>" + HtmlUtils.htmlEscape(problem) + "</p></body></html>";
    }

    static String displayName(JsonNode patient) {
        JsonNode name = patient.path("name").path(0);
        StringBuilder sb = new StringBuilder();
        for (JsonNode g : name.path("given")) {
            sb.append(g.asText()).append(' ');
        }
        sb.append(name.path("family").asText(""));
        String s = sb.toString().trim();
        return s.isEmpty() ? name.path("text").asText("Patient " + patient.path("id").asText()) : s;
    }

    static String memberId(JsonNode patient) {
        for (JsonNode id : patient.path("identifier")) {
            for (JsonNode c : id.path("type").path("coding")) {
                if ("MB".equals(c.path("code").asText())) {
                    return id.path("value").asText("?");
                }
            }
        }
        return patient.path("identifier").path(0).path("value").asText("-");
    }

    // ------------------------------------------------------------------ jwks / register

    /** No keys: the demo's id_tokens are HS256 with a per-process secret and cannot be verified by clients. */
    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return Map.of("keys", List.of());
    }

    @RequestMapping(value = "/register", method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(noStore()).body(Map.of(
                "error", "not_supported",
                "error_description", "dynamic client registration is not supported by the demo server; use the configured client id "
                        + auth.clientId() + " (paw.demo.client-id / paw.demo.client-secret)"));
    }

    @ExceptionHandler(DemoAuthService.OAuthException.class)
    public ResponseEntity<Map<String, Object>> oauthError(DemoAuthService.OAuthException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).headers(noStore()).contentType(MediaType.APPLICATION_JSON);
        if (e.status() == 401) {
            b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"demo\"");
        }
        return b.body(Map.of("error", e.error(), "error_description", e.getMessage()));
    }

    // ------------------------------------------------------------------ helpers

    static String serverBase(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }

    private static Map<String, String> firstValues(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                out.put(k, v[0]);
            }
        });
        return out;
    }

    private static HttpHeaders noStore() {
        HttpHeaders h = new HttpHeaders();
        h.setCacheControl(CacheControl.noStore());
        h.setPragma("no-cache");
        return h;
    }

    private static String append(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String origin(String url) {
        try {
            URI u = new URI(url);
            return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (URISyntaxException | NullPointerException e) {
            return "'none'";
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
