package com.thehiddenbrain.interop.patientaccess.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.auth.TokenService;
import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single path for FHIR calls: adds the environment's headers and bearer token, builds URLs,
 * keeps every target inside the environment, follows paging and turns error statuses into
 * {@link WorkbenchException}s that carry the upstream body.
 */
@Component
public class FhirGateway {

    public static final String PURPOSE_SEARCH = "search";
    public static final String PURPOSE_READ = "read";
    public static final String PURPOSE_DISCOVERY = "discovery";
    public static final String PURPOSE_CONFORMANCE = "conformance";

    private final HttpExecutor http;
    private final TokenService tokens;
    private final EnvironmentService environments;
    private final WorkbenchProperties properties;

    public FhirGateway(HttpExecutor http, TokenService tokens, EnvironmentService environments, WorkbenchProperties properties) {
        this.http = http;
        this.tokens = tokens;
        this.environments = environments;
        this.properties = properties;
    }

    /** Options for a single call: purpose/correlation for the history, and whether to send the token. */
    public record Options(String purpose, String correlationId, boolean authenticated, Map<String, String> headerOverrides) {

        public static Options of(String purpose) {
            return new Options(purpose, null, true, Map.of());
        }

        public static Options of(String purpose, String correlationId) {
            return new Options(purpose, correlationId, true, Map.of());
        }

        public Options unauthenticated() {
            return new Options(purpose, correlationId, false, headerOverrides);
        }

        public Options withHeaders(Map<String, String> overrides) {
            Map<String, String> merged = new LinkedHashMap<>(headerOverrides);
            merged.putAll(overrides);
            return new Options(purpose, correlationId, authenticated, merged);
        }
    }

    /** GET of any URL inside the environment; errors are returned, not thrown (callers decide). */
    public HttpResult get(Environment env, String url, Options options) {
        String target = UrlBuilder.resolveInside(env, url);
        return http.execute(env, HttpExecutor.Call.get(target, headers(env, options), options.purpose(), options.correlationId(),
                environments.secretHeaderNames(env)));
    }

    /** GET that throws on non-2xx so services can use the body directly. */
    public HttpResult getOrThrow(Environment env, String url, Options options) {
        HttpResult r = get(env, url, options);
        if (!r.ok()) {
            throw upstream(r);
        }
        return r;
    }

    public JsonNode read(Environment env, String resourceType, String id, Options options) {
        HttpResult r = getOrThrow(env, UrlBuilder.readUrl(env, resourceType, id), options);
        JsonNode json = r.json();
        if (json == null) {
            throw new WorkbenchException(ErrorCode.UPSTREAM_ERROR, "response to " + r.url() + " is not JSON", List.of(),
                    new ApiError.Upstream(r.status(), r.url(), snippet(r), r.requestId()), null);
        }
        return json;
    }

    /** Runs a search; adds _count unless the caller set it or the environment disables it. */
    public SearchPage search(Environment env, String resourceType, MultiValueMap<String, String> params, Options options) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        if (params != null) {
            p.addAll(params);
        }
        if (env.fhir().sendCountParam() && !p.containsKey("_count")) {
            p.add("_count", String.valueOf(pageSize(env)));
        }
        String url = UrlBuilder.searchUrl(env, resourceType, p);
        HttpResult r = get(env, url, options);
        if (!r.ok()) {
            throw upstream(r);
        }
        SearchPage page = SearchPage.of(r, List.of());
        if (!page.isBundle()) {
            throw new WorkbenchException(ErrorCode.UPSTREAM_ERROR, "search " + url + " did not return a Bundle (got "
                    + (r.resourceType() == null ? "non-FHIR content" : r.resourceType()) + ")", List.of(),
                    new ApiError.Upstream(r.status(), r.url(), snippet(r), r.requestId()), null);
        }
        return page;
    }

    /** Fetches the next page of a search (link URL must stay inside the environment). */
    public SearchPage page(Environment env, String nextUrl, Options options) {
        HttpResult r = get(env, nextUrl, options);
        if (!r.ok()) {
            throw upstream(r);
        }
        SearchPage page = SearchPage.of(r, List.of());
        if (!page.isBundle()) {
            throw new WorkbenchException(ErrorCode.UPSTREAM_ERROR, "page " + nextUrl + " did not return a Bundle", List.of(),
                    new ApiError.Upstream(r.status(), r.url(), snippet(r), r.requestId()), null);
        }
        return page;
    }

    /** Collects all matches across pages (bounded by maxPages). */
    public Collected searchAll(Environment env, String resourceType, MultiValueMap<String, String> params, Options options, Integer maxPages) {
        int limit = maxPages != null ? maxPages : (env.fhir().maxPages() != null ? env.fhir().maxPages() : properties.search().maxPages());
        List<JsonNode> all = new ArrayList<>();
        List<JsonNode> included = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();
        SearchPage page = search(env, resourceType, params, options);
        int pages = 1;
        Integer total = page.total();
        all.addAll(page.resources());
        included.addAll(page.included());
        requestIds.add(page.response().requestId());
        boolean truncated = false;
        while (page.nextUrl() != null) {
            if (pages >= limit) {
                truncated = true;
                break;
            }
            page = page(env, page.nextUrl(), options);
            pages++;
            all.addAll(page.resources());
            included.addAll(page.included());
            requestIds.add(page.response().requestId());
        }
        return new Collected(all, included, total, pages, truncated, requestIds);
    }

    public record Collected(List<JsonNode> resources, List<JsonNode> included, Integer total, int pages, boolean truncated, List<String> requestIds) {
    }

    public int pageSize(Environment env) {
        return env.fhir().pageSize() != null ? env.fhir().pageSize() : properties.search().pageSize();
    }

    Map<String, String> headers(Environment env, Options options) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", env.fhir().acceptHeaderOrDefault());
        if (env.fhir().preferHandlingLenient()) {
            headers.put("Prefer", "handling=lenient");
        }
        headers.putAll(environments.resolvedHeaders(env));
        if (options.authenticated()) {
            tokens.bearerFor(env).ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        }
        if (options.headerOverrides() != null) {
            options.headerOverrides().forEach((k, v) -> {
                if (v == null) {
                    headers.remove(k);
                } else {
                    headers.put(k, v);
                }
            });
        }
        return headers;
    }

    public static WorkbenchException upstream(HttpResult r) {
        ErrorCode code = r.status() == 401 || r.status() == 403 ? ErrorCode.UPSTREAM_ERROR : ErrorCode.UPSTREAM_ERROR;
        String message = "FHIR server answered " + r.status() + " for " + r.url()
                + (r.body().isBlank() ? "" : ": " + r.errorSummary());
        return new WorkbenchException(code, message, List.of(), new ApiError.Upstream(r.status(), r.url(), snippet(r), r.requestId()), null);
    }

    static String snippet(HttpResult r) {
        String b = r.body();
        return b.length() > 2000 ? b.substring(0, 2000) + "..." : b;
    }

    public Set<String> secretHeaderNames(Environment env) {
        return environments.secretHeaderNames(env);
    }
}
