package com.thehiddenbrain.interop.patientaccess.search;

import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.environment.Environment;
import com.thehiddenbrain.interop.patientaccess.environment.IdentifierSystem;
import com.thehiddenbrain.interop.patientaccess.fhir.FhirGateway;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import com.thehiddenbrain.interop.patientaccess.patient.PatientSummary;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds members the way the IGs allow: {@code Patient?identifier=system|value} for a member id
 * (C4BB/US Core SHALL), {@code Patient?name=&birthdate=&gender=} / {@code family=&given=} for
 * demographics (US Core SHALL/SHOULD combos), {@code Patient/{id}} for a logical id, and, when a
 * member id matches no Patient, {@code Coverage?identifier=} with the beneficiaries resolved.
 */
@Service
public class MemberSearchService {

    private final FhirGateway gateway;
    private final IgCatalog catalog;

    public MemberSearchService(FhirGateway gateway, IgCatalog catalog) {
        this.gateway = gateway;
        this.catalog = catalog;
    }

    public MemberSearchResult search(Environment env, MemberSearchRequest request) {
        if (request == null || !(request.hasMemberId() || request.hasDemographics() || request.hasId() || request.hasExtra())) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "enter a member id, a name / birth date, a patient id or search parameters");
        }
        List<MemberSearchResult.Query> queries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, PatientSummary> found = new LinkedHashMap<>();
        FhirGateway.Options options = FhirGateway.Options.of(FhirGateway.PURPOSE_SEARCH);

        if (request.hasId()) {
            String url = UrlBuilder.readUrl(env, "Patient", request.id().trim());
            HttpResult r = gateway.get(env, url, FhirGateway.Options.of(FhirGateway.PURPOSE_READ));
            if (r.ok() && "Patient".equals(r.resourceType())) {
                add(found, r.json(), "Patient/" + request.id().trim());
                queries.add(new MemberSearchResult.Query("read Patient/" + request.id().trim(), url, r.status(), 1, 1, r.durationMs(), r.requestId(), null));
            } else {
                queries.add(new MemberSearchResult.Query("read Patient/" + request.id().trim(), url, r.status(), 0, 0, r.durationMs(), r.requestId(),
                        r.ok() ? "response is not a Patient" : r.errorSummary()));
            }
        }

        if (request.hasMemberId()) {
            String value = request.memberId().trim();
            List<IdentifierSystem> systems = systemsFor(env, request.identifierSystem());
            List<String> tokens = new ArrayList<>();
            if (systems.isEmpty()) {
                tokens.add(value);
                if (env.identifierSystems().isEmpty()) {
                    warnings.add("no identifier systems configured for this environment; searched identifier=" + value + " without a system");
                }
            } else {
                for (IdentifierSystem s : systems) {
                    tokens.add(s.system().trim() + "|" + value);
                }
            }
            int before = found.size();
            for (String token : tokens) {
                MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
                params.add("identifier", token);
                addExtra(params, request);
                run(env, "Patient", params, "Patient by member id " + token, options, queries, found, warnings);
                if (found.size() > before) {
                    break;
                }
            }
            if (found.size() == before && !Boolean.FALSE.equals(request.coverageFallback())) {
                for (String token : tokens) {
                    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
                    params.add("identifier", token);
                    SearchPage page = runPage(env, "Coverage", params, "Coverage by member id " + token, options, queries, warnings);
                    if (page == null) {
                        continue;
                    }
                    Set<String> beneficiaries = new LinkedHashSet<>();
                    for (JsonNode cov : page.resources()) {
                        String ref = cov.path("beneficiary").path("reference").asString(null);
                        if (ref != null) {
                            beneficiaries.add(ref);
                        }
                    }
                    for (String ref : beneficiaries) {
                        String pid = ref.substring(ref.lastIndexOf('/') + 1);
                        String url = UrlBuilder.readUrl(env, "Patient", pid);
                        HttpResult r = gateway.get(env, ref.startsWith("http") ? ref : url, FhirGateway.Options.of(FhirGateway.PURPOSE_READ));
                        if (r.ok() && "Patient".equals(r.resourceType())) {
                            add(found, r.json(), "Coverage.beneficiary " + ref);
                        }
                        queries.add(new MemberSearchResult.Query("read " + ref + " (Coverage beneficiary)", r.url(), r.status(),
                                r.ok() ? 1 : 0, null, r.durationMs(), r.requestId(), r.ok() ? null : r.errorSummary()));
                    }
                    if (!beneficiaries.isEmpty()) {
                        break;
                    }
                }
            }
        }

        if (request.hasDemographics() || (!request.hasMemberId() && !request.hasId() && request.hasExtra())) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            if (MemberSearchRequest.notBlank(request.name())) {
                params.add("name", request.name().trim());
            }
            if (MemberSearchRequest.notBlank(request.family())) {
                params.add("family", request.family().trim());
            }
            if (MemberSearchRequest.notBlank(request.given())) {
                params.add("given", request.given().trim());
            }
            if (MemberSearchRequest.notBlank(request.birthDate())) {
                params.add("birthdate", request.birthDate().trim());
            }
            if (MemberSearchRequest.notBlank(request.gender())) {
                params.add("gender", request.gender().trim().toLowerCase());
            }
            addExtra(params, request);
            if (request.count() != null && request.count() > 0) {
                params.add("_count", String.valueOf(request.count()));
            }
            if (params.containsKey("name") && !params.containsKey("birthdate") && !params.containsKey("gender")) {
                warnings.add("name alone is a SHALL search in US Core but may return many members; add birth date or gender to narrow it");
            }
            run(env, "Patient", params, "Patient by demographics", options, queries, found, warnings);
        }

        return new MemberSearchResult(new ArrayList<>(found.values()), queries, warnings);
    }

    static List<IdentifierSystem> systemsFor(Environment env, String selected) {
        if (selected != null && !selected.isBlank()) {
            return List.of(new IdentifierSystem("selected", selected.trim(), null, true));
        }
        List<IdentifierSystem> defaults = env.identifierSystems().stream().filter(IdentifierSystem::defaultForMemberId).toList();
        return defaults.isEmpty() ? env.identifierSystems() : defaults;
    }

    private static void addExtra(MultiValueMap<String, String> params, MemberSearchRequest request) {
        if (request.extraParams() != null) {
            request.extraParams().forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    params.add(k.trim(), v);
                }
            });
        }
    }

    private void run(Environment env, String type, MultiValueMap<String, String> params, String description, FhirGateway.Options options,
                     List<MemberSearchResult.Query> queries, Map<String, PatientSummary> found, List<String> warnings) {
        SearchPage page = runPage(env, type, params, description, options, queries, warnings);
        if (page != null) {
            for (JsonNode p : page.resources()) {
                if ("Patient".equals(p.path("resourceType").asString(""))) {
                    add(found, p, description);
                }
            }
        }
    }

    private SearchPage runPage(Environment env, String type, MultiValueMap<String, String> params, String description, FhirGateway.Options options,
                               List<MemberSearchResult.Query> queries, List<String> warnings) {
        warnings.addAll(catalog.validateParams(type, params.keySet()));
        try {
            SearchPage page = gateway.search(env, type, params, options);
            queries.add(new MemberSearchResult.Query(description, page.response().url(), page.response().status(), page.count(), page.total(),
                    page.response().durationMs(), page.response().requestId(), null));
            return page;
        } catch (WorkbenchException e) {
            var up = e.getUpstream();
            queries.add(new MemberSearchResult.Query(description, up == null ? UrlBuilder.searchUrl(env, type, params) : up.url(),
                    up == null ? null : up.httpStatus(), 0, null, 0, up == null ? null : up.requestId(), e.getMessage()));
            return null;
        }
    }

    private static void add(Map<String, PatientSummary> found, JsonNode patient, String foundBy) {
        String id = patient.path("id").asString(null);
        String key = id == null ? "anonymous-" + found.size() : id;
        found.putIfAbsent(key, PatientSummary.of(patient, foundBy));
    }
}
