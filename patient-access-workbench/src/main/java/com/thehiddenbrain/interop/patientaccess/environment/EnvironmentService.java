package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.Ids;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.secrets.Secret;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretView;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validation and conversion between the stored {@link Environment}, its input and its masked view. */
@Service
public class EnvironmentService {

    /** Onyx SAFHIR is the vendor this workbench is built for; the field stays free text for other platforms. */
    public static final String DEFAULT_VENDOR = "Onyx SAFHIR";
    public static final List<String> DEFAULT_IGS = List.of("c4bb", "pdex", "uscore", "usdf");
    static final Set<String> SIGNING_ALGORITHMS = Set.of("RS384", "ES384", "RS256", "ES256");

    private final EnvironmentStore store;
    private final SecretCrypto crypto;

    public EnvironmentService(EnvironmentStore store, SecretCrypto crypto) {
        this.store = store;
        this.crypto = crypto;
    }

    public List<EnvironmentView> list() {
        return store.all().stream().map(this::view).toList();
    }

    public EnvironmentView get(String id) {
        return view(store.require(id));
    }

    public Environment require(String id) {
        return store.require(id);
    }

    public Optional<Environment> find(String id) {
        return store.find(id);
    }

    public EnvironmentView create(EnvironmentInput input) {
        Environment candidate = merge(null, input);
        validate(candidate, null);
        return view(store.insert(candidate));
    }

    public EnvironmentView update(String id, EnvironmentInput input) {
        Environment current = store.require(id);
        Environment candidate = merge(current, input);
        validate(candidate, id);
        return view(store.update(candidate, input.version()));
    }

    public void delete(String id) {
        store.delete(id);
    }

    public EnvironmentView duplicate(String id, String newName) {
        Environment source = store.require(id);
        String name = newName == null || newName.isBlank() ? source.name() + " (copy)" : newName.trim();
        Environment copy = new Environment(Ids.next(), name, source.vendor(), source.tier(), source.fhirBaseUrl(), source.auth(),
                source.headers(), source.identifierSystems(), source.fhir(), source.igBaseUrls(), source.implementationGuides(), source.notes(),
                source.enabled(), 0, null, null);
        validate(copy, null);
        return view(store.insert(copy));
    }

    /** Plain header values to send with every request (secret ones decrypted). */
    public Map<String, String> resolvedHeaders(Environment environment) {
        Map<String, String> out = new LinkedHashMap<>();
        for (HeaderEntry h : environment.headers()) {
            String value = h.secret() ? crypto.reveal(h.secretValue()) : h.value();
            if (h.name() != null && !h.name().isBlank() && value != null) {
                out.put(h.name().trim(), value);
            }
        }
        return out;
    }

    public Set<String> secretHeaderNames(Environment environment) {
        return environment.headers().stream().filter(HeaderEntry::secret).map(h -> h.name().trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }

    public EnvironmentView view(Environment e) {
        AuthConfig a = e.auth();
        EnvironmentView.AuthView auth = new EnvironmentView.AuthView(a.mode(), a.discoverEndpoints(), a.authorizationEndpoint(),
                a.tokenEndpoint(), a.clientId(), crypto.view(a.clientSecret()), a.clientAuthMethod(), a.scopes(), a.audience(),
                crypto.view(a.staticToken()), crypto.view(a.privateKeyJwk()), a.signingAlgorithm(), a.redirectUri(), a.usePkce(),
                a.extraTokenParams(), a.extraAuthorizeParams());
        List<EnvironmentView.HeaderView> headers = e.headers().stream().map(h -> new EnvironmentView.HeaderView(h.name(),
                h.secret() ? null : h.value(), h.secret(), h.secret() ? crypto.view(h.secretValue()) : SecretView.unset())).toList();
        return new EnvironmentView(e.id(), e.name(), e.vendor(), e.tier(), e.fhirBaseUrl(), auth, headers, e.identifierSystems(),
                e.fhir(), e.igBaseUrls(), e.implementationGuides(), e.notes(), e.enabled(), e.version(), e.createdAt(), e.updatedAt());
    }

    Environment merge(Environment current, EnvironmentInput in) {
        if (in == null) {
            throw new WorkbenchException(ErrorCode.MALFORMED_REQUEST, "environment body is required");
        }
        AuthConfig currentAuth = current == null ? AuthConfig.none() : current.auth();
        AuthConfig auth = mergeAuth(currentAuth, in.auth());
        List<HeaderEntry> headers = current == null ? List.of() : current.headers();
        if (in.headers() != null) {
            headers = mergeHeaders(headers, in.headers());
        }
        return new Environment(
                current == null ? Ids.next() : current.id(),
                or(in.name(), current == null ? null : current.name()),
                or(in.vendor(), current == null ? DEFAULT_VENDOR : current.vendor()),
                or(in.tier(), current == null ? EnvironmentTier.UAT : current.tier()),
                or(in.fhirBaseUrl(), current == null ? null : current.fhirBaseUrl()),
                auth,
                headers,
                or(in.identifierSystems(), current == null ? List.of() : current.identifierSystems()),
                or(in.fhir(), current == null ? FhirOptions.defaults() : current.fhir()),
                cleanBases(or(in.igBaseUrls(), current == null ? Map.of() : current.igBaseUrls())),
                or(in.implementationGuides(), current == null ? DEFAULT_IGS : current.implementationGuides()),
                or(in.notes(), current == null ? null : current.notes()),
                in.enabled() != null ? in.enabled() : current == null || current.enabled(),
                current == null ? 0 : current.version(),
                current == null ? null : current.createdAt(),
                current == null ? null : current.updatedAt());
    }

    private AuthConfig mergeAuth(AuthConfig c, EnvironmentInput.AuthInput a) {
        if (a == null) {
            return c;
        }
        return new AuthConfig(
                or(a.mode(), c.mode()),
                a.discoverEndpoints() != null ? a.discoverEndpoints() : c.discoverEndpoints(),
                or(a.authorizationEndpoint(), c.authorizationEndpoint()),
                or(a.tokenEndpoint(), c.tokenEndpoint()),
                or(a.clientId(), c.clientId()),
                EnvironmentInput.mergeSecret(c.clientSecret(), a.clientSecret(), crypto::seal),
                or(a.clientAuthMethod(), c.clientAuthMethod()),
                or(a.scopes(), c.scopes()),
                or(a.audience(), c.audience()),
                EnvironmentInput.mergeSecret(c.staticToken(), a.staticToken(), crypto::seal),
                EnvironmentInput.mergeSecret(c.privateKeyJwk(), a.privateKeyJwk(), crypto::seal),
                or(a.signingAlgorithm(), c.signingAlgorithm()),
                or(a.redirectUri(), c.redirectUri()),
                a.usePkce() != null ? a.usePkce() : c.usePkce(),
                or(a.extraTokenParams(), c.extraTokenParams()),
                or(a.extraAuthorizeParams(), c.extraAuthorizeParams()));
    }

    private List<HeaderEntry> mergeHeaders(List<HeaderEntry> current, List<EnvironmentInput.HeaderInput> inputs) {
        List<HeaderEntry> out = new ArrayList<>();
        for (EnvironmentInput.HeaderInput h : inputs) {
            if (h == null || h.name() == null || h.name().isBlank()) {
                continue;
            }
            boolean secret = Boolean.TRUE.equals(h.secret());
            HeaderEntry existing = current.stream().filter(x -> x.name().equalsIgnoreCase(h.name().trim())).findFirst().orElse(null);
            if (secret) {
                Secret value = EnvironmentInput.mergeSecret(existing != null && existing.secret() ? existing.secretValue() : null,
                        h.value(), crypto::seal);
                out.add(new HeaderEntry(h.name().trim(), null, value, true));
            } else {
                out.add(new HeaderEntry(h.name().trim(), h.value() == null ? "" : h.value(), null, false));
            }
        }
        return out;
    }

    void validate(Environment e, String currentId) {
        List<ApiError.Detail> problems = new ArrayList<>();
        if (isBlank(e.name())) {
            problems.add(WorkbenchException.detail("name", "is required"));
        } else if (e.name().length() > 80) {
            problems.add(WorkbenchException.detail("name", "longer than 80 characters"));
        } else if (store.all().stream().anyMatch(o -> !o.id().equals(currentId) && o.name().equalsIgnoreCase(e.name().trim()))) {
            problems.add(WorkbenchException.detail("name", "another environment has this name"));
        }
        checkUrl(e.fhirBaseUrl(), "fhirBaseUrl", true, e.tier(), problems);
        for (Map.Entry<String, String> base : e.igBaseUrls().entrySet()) {
            if (!com.thehiddenbrain.interop.patientaccess.fhir.IgRouting.KEYS.contains(base.getKey())) {
                problems.add(WorkbenchException.detail("igBaseUrls." + base.getKey(), "unknown IG key; use one of "
                        + com.thehiddenbrain.interop.patientaccess.fhir.IgRouting.KEYS));
            }
            checkUrl(base.getValue(), "igBaseUrls." + base.getKey(), false, e.tier(), problems);
        }
        AuthConfig a = e.auth();
        switch (a.mode()) {
            case NONE -> { }
            case STATIC_TOKEN -> {
                if (a.staticToken() == null) {
                    problems.add(WorkbenchException.detail("auth.staticToken", "is required for STATIC_TOKEN"));
                }
            }
            case CLIENT_CREDENTIALS -> {
                requireClientId(a, problems);
                if (a.clientSecret() == null) {
                    problems.add(WorkbenchException.detail("auth.clientSecret", "is required for CLIENT_CREDENTIALS"));
                }
                requireTokenEndpointOrDiscovery(a, e.tier(), problems);
            }
            case BACKEND_SERVICES -> {
                requireClientId(a, problems);
                if (a.privateKeyJwk() == null) {
                    problems.add(WorkbenchException.detail("auth.privateKeyJwk", "is required for BACKEND_SERVICES (private key as JWK JSON)"));
                }
                if (!SIGNING_ALGORITHMS.contains(a.signingAlgorithm())) {
                    problems.add(WorkbenchException.detail("auth.signingAlgorithm", "must be one of " + SIGNING_ALGORITHMS));
                }
                requireTokenEndpointOrDiscovery(a, e.tier(), problems);
            }
            case SMART_AUTHORIZATION_CODE -> {
                requireClientId(a, problems);
                requireTokenEndpointOrDiscovery(a, e.tier(), problems);
                if (!a.discoverEndpoints() && isBlank(a.authorizationEndpoint())) {
                    problems.add(WorkbenchException.detail("auth.authorizationEndpoint", "is required when endpoint discovery is off"));
                }
                checkUrl(a.authorizationEndpoint(), "auth.authorizationEndpoint", false, e.tier(), problems);
                checkUrl(a.redirectUri(), "auth.redirectUri", false, EnvironmentTier.OTHER, problems);
            }
        }
        checkUrl(a.tokenEndpoint(), "auth.tokenEndpoint", false, e.tier(), problems);
        if (e.tier() == EnvironmentTier.PROD && e.fhir().trustAllCertificates()) {
            problems.add(WorkbenchException.detail("fhir.trustAllCertificates", "cannot be enabled for a PROD environment"));
        }
        if (e.fhir().pageSize() != null && (e.fhir().pageSize() < 1 || e.fhir().pageSize() > 1000)) {
            problems.add(WorkbenchException.detail("fhir.pageSize", "must be between 1 and 1000"));
        }
        if (e.fhir().maxPages() != null && (e.fhir().maxPages() < 1 || e.fhir().maxPages() > 500)) {
            problems.add(WorkbenchException.detail("fhir.maxPages", "must be between 1 and 500"));
        }
        for (int i = 0; i < e.identifierSystems().size(); i++) {
            IdentifierSystem s = e.identifierSystems().get(i);
            if (s == null || isBlank(s.system())) {
                problems.add(WorkbenchException.detail("identifierSystems[" + i + "].system", "is required"));
            }
        }
        for (int i = 0; i < e.headers().size(); i++) {
            HeaderEntry h = e.headers().get(i);
            if (h.name().equalsIgnoreCase("authorization")) {
                problems.add(WorkbenchException.detail("headers[" + i + "].name", "Authorization is set from the auth settings, not as an extra header"));
            }
        }
        if (!problems.isEmpty()) {
            throw WorkbenchException.validation("environment rejected: " + problems.stream()
                    .map(d -> d.field() + " " + d.message()).toList(), problems);
        }
    }

    private static void requireClientId(AuthConfig a, List<ApiError.Detail> problems) {
        if (isBlank(a.clientId())) {
            problems.add(WorkbenchException.detail("auth.clientId", "is required for " + a.mode()));
        }
    }

    private static void requireTokenEndpointOrDiscovery(AuthConfig a, EnvironmentTier tier, List<ApiError.Detail> problems) {
        if (!a.discoverEndpoints() && isBlank(a.tokenEndpoint())) {
            problems.add(WorkbenchException.detail("auth.tokenEndpoint", "is required when endpoint discovery is off"));
        }
    }

    static void checkUrl(String url, String field, boolean required, EnvironmentTier tier, List<ApiError.Detail> problems) {
        if (isBlank(url)) {
            if (required) {
                problems.add(WorkbenchException.detail(field, "is required"));
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            problems.add(WorkbenchException.detail(field, "is not a valid URL"));
            return;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            problems.add(WorkbenchException.detail(field, "must be an absolute http(s) URL"));
            return;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            problems.add(WorkbenchException.detail(field, "must use http or https"));
        } else if (scheme.equals("http") && tier == EnvironmentTier.PROD && !isLoopback(uri.getHost())) {
            problems.add(WorkbenchException.detail(field, "must use https for a PROD environment"));
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            problems.add(WorkbenchException.detail(field, "must not contain a query string or fragment"));
        }
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]") || h.equals("host.docker.internal");
    }

    /** Drops blank entries so "" clears an IG base. */
    static Map<String, String> cleanBases(Map<String, String> bases) {
        Map<String, String> out = new LinkedHashMap<>();
        if (bases != null) {
            bases.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    out.put(k.trim(), v.trim());
                }
            });
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
