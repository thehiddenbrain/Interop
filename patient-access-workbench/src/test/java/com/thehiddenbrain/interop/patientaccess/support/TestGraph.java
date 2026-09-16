package com.thehiddenbrain.interop.patientaccess.support;

import com.thehiddenbrain.interop.patientaccess.auth.SmartAuthCodeFlow;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.auth.TokenService;
import com.thehiddenbrain.interop.patientaccess.auth.TokenStore;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.AuthConfig;
import com.thehiddenbrain.interop.patientaccess.environment.AuthMode;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentInput;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentStore;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentTier;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentView;
import com.thehiddenbrain.interop.patientaccess.environment.FhirOptions;
import com.thehiddenbrain.interop.patientaccess.environment.IdentifierSystem;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpClientFactory;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpExecutor;
import com.thehiddenbrain.interop.patientaccess.history.RequestLog;
import com.thehiddenbrain.interop.patientaccess.patient.PatientWorkspaceService;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import com.thehiddenbrain.interop.patientaccess.search.MemberSearchService;
import com.thehiddenbrain.interop.patientaccess.secrets.SecretCrypto;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Wires the service graph without Spring, on a temp folder, for fast unit and WireMock tests.
 * {@code clock} is mutable so token expiry can be simulated.
 */
public final class TestGraph {

    public static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();

    public final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T12:00:00Z"));
    public final WorkbenchProperties properties;
    public final SecretCrypto crypto = SecretCrypto.withKey(KEY);
    public final EnvironmentStore environmentStore;
    public final EnvironmentService environments;
    public final RequestLog history;
    public final HttpClientFactory clients;
    public final HttpExecutor http;
    public final TokenStore tokenStore;
    public final SmartDiscoveryService discovery;
    public final TokenService tokens;
    public final SmartAuthCodeFlow smartFlow;
    public final FhirGateway gateway;
    public final IgCatalog catalog = new IgCatalog();
    public final ProfileLiteChecker checker = new ProfileLiteChecker(catalog);
    public final PriorAuthSummarizer priorAuth = new PriorAuthSummarizer(catalog);
    public final MemberSearchService memberSearch;
    public final PatientWorkspaceService workspace;

    public TestGraph(Path dataDir) {
        this(dataDir, new WorkbenchProperties(dataDir.toString(), "", "", new WorkbenchProperties.Ui(true), new WorkbenchProperties.Demo(false),
                new WorkbenchProperties.Security(new WorkbenchProperties.Security.Basic(false, "workbench", "")),
                new WorkbenchProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(10), "paw-test", 1),
                new WorkbenchProperties.History(200, 65536, false), new WorkbenchProperties.Search(50, 5),
                new WorkbenchProperties.Conformance(3, 2, 3000, 10000), new WorkbenchProperties.Validation(dataDir.resolve("packages").toString())));
    }

    public TestGraph(Path dataDir, WorkbenchProperties properties) {
        this.properties = properties;
        environmentStore = new EnvironmentStore(dataDir.resolve("environments.json"), clock);
        environments = new EnvironmentService(environmentStore, crypto);
        history = new RequestLog(dataDir.resolve("history"), properties.history().maxEntries(), properties.history().persist(), clock);
        clients = new HttpClientFactory(properties);
        http = new HttpExecutor(clients, history, properties, clock);
        tokenStore = new TokenStore(dataDir.resolve("tokens.json"));
        discovery = new SmartDiscoveryService(http, environments, clock);
        tokens = new TokenService(tokenStore, crypto, http, discovery, environments, clock);
        smartFlow = new SmartAuthCodeFlow(tokens, discovery, environments, properties, clock);
        gateway = new FhirGateway(http, tokens, environments, properties);
        memberSearch = new MemberSearchService(gateway, catalog);
        workspace = new PatientWorkspaceService(gateway, checker, priorAuth, catalog, new SyncTaskExecutor());
    }

    /** An open (AuthMode.NONE) environment pointing at a base URL such as a WireMock server. */
    public Environment openEnvironment(String name, String baseUrl) {
        EnvironmentView v = environments.create(new EnvironmentInput(name, null, EnvironmentTier.SANDBOX, baseUrl,
                new EnvironmentInput.AuthInput(AuthMode.NONE, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                List.of(), List.of(new IdentifierSystem("Member ID", "http://example.org/member-id", "MB", true)), FhirOptions.defaults(),
                null, null, true, null));
        return environments.require(v.id());
    }

    /** Environment with an explicit auth configuration (secrets as plain strings). */
    public Environment environment(String name, String baseUrl, EnvironmentInput.AuthInput auth, List<EnvironmentInput.HeaderInput> headers) {
        EnvironmentView v = environments.create(new EnvironmentInput(name, null, EnvironmentTier.SANDBOX, baseUrl, auth,
                headers == null ? List.of() : headers, List.of(new IdentifierSystem("Member ID", "http://example.org/member-id", "MB", true)),
                FhirOptions.defaults(), null, null, true, null));
        return environments.require(v.id());
    }

    public static EnvironmentInput.AuthInput clientCredentials(String tokenEndpoint, String clientId, String secret, String scopes) {
        return new EnvironmentInput.AuthInput(AuthMode.CLIENT_CREDENTIALS, false, null, tokenEndpoint, clientId, secret, null, scopes, null,
                null, null, null, null, null, Map.of(), Map.of());
    }

    public static AuthConfig noAuth() {
        return AuthConfig.none();
    }

    /** A clock whose instant tests can move. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
