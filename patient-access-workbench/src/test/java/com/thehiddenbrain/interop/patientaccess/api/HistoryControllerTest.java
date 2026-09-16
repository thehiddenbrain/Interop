package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.history.RequestLog;
import com.thehiddenbrain.interop.patientaccess.history.RequestRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryControllerTest extends ApiTestSupport {

    @Autowired
    RequestLog history;

    private RequestRecord record(String id, String purpose, String correlation) {
        return new RequestRecord(id, Instant.parse("2026-09-16T12:00:00Z"), "env-h", "History env", purpose, correlation, "GET",
                "https://fhir.example.org/r4/Patient?identifier=sys%7C1", Map.of("Accept", "application/fhir+json", "Authorization", "Bearer ***", "X-Api-Key", "***"),
                null, 200, Map.of("content-type", List.of("application/fhir+json")), "{\"resourceType\":\"Bundle\",\"type\":\"searchset\"}", false, 33, null,
                "Bundle searchset: 0 entries");
    }

    @Test
    void listsFiltersAndReturnsEntries() throws Exception {
        history.record(record("hist-search-1", "search", "corr-hist"));
        history.record(record("hist-auth-1", "auth", "corr-hist"));
        history.record(record("hist-search-2", "search", "corr-other"));

        mvc.perform(get("/api/v1/history").param("correlationId", "corr-hist"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("hist-auth-1"))
                .andExpect(jsonPath("$[1].id").value("hist-search-1"))
                .andExpect(jsonPath("$[0].environmentName").value("History env"))
                .andExpect(jsonPath("$[0].status").value(200))
                .andExpect(jsonPath("$[0].durationMs").value(33))
                .andExpect(jsonPath("$[0].requestHeaders").doesNotExist());
        mvc.perform(get("/api/v1/history").param("correlationId", "corr-hist").param("purpose", "search").param("environmentId", "env-h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("hist-search-1"));
        mvc.perform(get("/api/v1/history").param("correlationId", "corr-hist").param("limit", "1"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/history").param("environmentId", "no-such-env"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/v1/history/hist-search-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("hist-search-1"))
                .andExpect(jsonPath("$.purpose").value("search"))
                .andExpect(jsonPath("$.url").value("https://fhir.example.org/r4/Patient?identifier=sys%7C1"))
                .andExpect(jsonPath("$.requestHeaders.Authorization").value("Bearer ***"))
                .andExpect(jsonPath("$.responseHeaders.content-type[0]").value("application/fhir+json"))
                .andExpect(jsonPath("$.responseBody").value(containsString("searchset")))
                .andExpect(jsonPath("$.responseBodyTruncated").value(false))
                .andExpect(jsonPath("$.summary").value("Bundle searchset: 0 entries"));
    }

    @Test
    void curlUsesTokenPlaceholdersAndUnknownIdsAre404() throws Exception {
        history.record(record("hist-curl-1", "search", "corr-curl"));
        mvc.perform(get("/api/v1/history/hist-curl-1/curl"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("curl -sS -X GET \\\n  'https://fhir.example.org/r4/Patient?identifier=sys%7C1'")))
                .andExpect(content().string(containsString("-H \"Authorization: Bearer $TOKEN\"")))
                .andExpect(content().string(containsString("-H \"X-Api-Key: $X_API_KEY\"")))
                .andExpect(content().string(containsString("-H 'Accept: application/fhir+json'")))
                .andExpect(content().string(not(containsString("***"))));

        mvc.perform(get("/api/v1/history/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("history entry 'nope' not found"));
        mvc.perform(get("/api/v1/history/nope/curl"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
