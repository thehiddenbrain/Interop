package com.thehiddenbrain.interop.patientaccess.conformance;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.SmartDiscoveryService;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import com.thehiddenbrain.interop.patientaccess.patient.PriorAuthSummarizer;
import com.thehiddenbrain.interop.patientaccess.patient.ProfileLiteChecker;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a check can use: the environment, the patient under test, the gateway (every call is recorded
 * with the run id as correlation), the IG catalog, the profile checker, discovery results and a
 * per-run cache so several checks can share one fetch (e.g. the patient's EOBs).
 */
public final class CheckContext {

    private final String runId;
    private final Environment environment;
    private final String patientId;
    private final FhirGateway gateway;
    private final IgCatalog catalog;
    private final ProfileLiteChecker checker;
    private final PriorAuthSummarizer priorAuth;
    private final SmartDiscoveryService discovery;
    private final WorkbenchProperties properties;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public CheckContext(String runId, Environment environment, String patientId, FhirGateway gateway, IgCatalog catalog,
                        ProfileLiteChecker checker, PriorAuthSummarizer priorAuth, SmartDiscoveryService discovery, WorkbenchProperties properties) {
        this.runId = runId;
        this.environment = environment;
        this.patientId = patientId;
        this.gateway = gateway;
        this.catalog = catalog;
        this.checker = checker;
        this.priorAuth = priorAuth;
        this.discovery = discovery;
        this.properties = properties;
    }

    public String runId() {
        return runId;
    }

    public Environment environment() {
        return environment;
    }

    public Optional<String> patientId() {
        return Optional.ofNullable(patientId);
    }

    public FhirGateway gateway() {
        return gateway;
    }

    public IgCatalog catalog() {
        return catalog;
    }

    public ProfileLiteChecker checker() {
        return checker;
    }

    public PriorAuthSummarizer priorAuth() {
        return priorAuth;
    }

    public WorkbenchProperties properties() {
        return properties;
    }

    public FhirGateway.Options options() {
        return FhirGateway.Options.of(FhirGateway.PURPOSE_CONFORMANCE, runId);
    }

    /** GET of a URL inside the environment; errors are returned as results, transport failures throw. */
    public HttpResult get(String url) {
        return gateway.get(environment, url, options());
    }

    public HttpResult getUnauthenticated(String url) {
        return gateway.get(environment, url, options().unauthenticated());
    }

    public HttpResult getWithHeaders(String url, Map<String, String> headers) {
        return gateway.get(environment, url, options().withHeaders(headers));
    }

    public String searchUrl(String resourceType, MultiValueMap<String, String> params) {
        return UrlBuilder.searchUrl(environment, resourceType, params);
    }

    public String readUrl(String resourceType, String id) {
        return UrlBuilder.readUrl(environment, resourceType, id);
    }

    public static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            p.add(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    /** Search returning the page even on error status (callers inspect status). */
    public SearchPage searchPage(String resourceType, MultiValueMap<String, String> params) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>(params);
        if (environment.fhir().sendCountParam() && !p.containsKey("_count")) {
            p.add("_count", String.valueOf(gateway.pageSize(environment)));
        }
        HttpResult r = get(UrlBuilder.searchUrl(environment, resourceType, p));
        return SearchPage.of(r, List.of());
    }

    public SmartDiscoveryService.Discovery discovery() {
        return cached("discovery", () -> discovery.discover(environment, false, runId));
    }

    @SuppressWarnings("unchecked")
    public <T> T cached(String key, java.util.function.Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }

    /** All EOBs of the patient (first pages, bounded by conformance.max-pages), shared between checks. */
    public FhirGateway.Collected patientEobs() {
        return cached("eobs", () -> gateway.searchAll(environment, "ExplanationOfBenefit", params("patient", patientId), options(),
                properties.conformance().maxPages()));
    }

    public List<JsonNode> patientClaims() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : patientEobs().resources()) {
            if (!PriorAuthSummarizer.isPriorAuth(e)) {
                out.add(e);
            }
        }
        return out;
    }

    public List<JsonNode> patientPriorAuths() {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : patientEobs().resources()) {
            if (PriorAuthSummarizer.isPriorAuth(e)) {
                out.add(e);
            }
        }
        return out;
    }

    public JsonNode patientResource() {
        return cached("patient", () -> {
            HttpResult r = get(readUrl("Patient", patientId));
            return r.ok() ? r.json() : null;
        });
    }

    public FhirGateway.Collected patientResources(String resourceType) {
        return cached("res:" + resourceType, () -> gateway.searchAll(environment, resourceType, params("patient", patientId), options(),
                properties.conformance().maxPages()));
    }

    public long slowWarnMs() {
        return properties.conformance().slowWarnMs();
    }

    public long slowFailMs() {
        return properties.conformance().slowFailMs();
    }
}
