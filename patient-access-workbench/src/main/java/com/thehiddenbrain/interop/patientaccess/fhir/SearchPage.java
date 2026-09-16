package com.thehiddenbrain.interop.patientaccess.fhir;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** One page of a FHIR search: the Bundle, its resources, total and the next link. */
public record SearchPage(JsonNode bundle, List<JsonNode> resources, List<JsonNode> included, List<JsonNode> outcomes, Integer total,
                         String selfUrl, String nextUrl, HttpResult response, List<String> warnings) {

    public static SearchPage of(HttpResult response, List<String> warnings) {
        JsonNode bundle = response.json();
        List<JsonNode> matches = new ArrayList<>();
        List<JsonNode> included = new ArrayList<>();
        List<JsonNode> outcomes = new ArrayList<>();
        Integer total = null;
        String self = null;
        String next = null;
        if (bundle != null && "Bundle".equals(bundle.path("resourceType").asString(""))) {
            if (bundle.hasNonNull("total")) {
                total = bundle.get("total").asInt();
            }
            for (JsonNode entry : bundle.path("entry")) {
                JsonNode resource = entry.get("resource");
                if (resource == null) {
                    continue;
                }
                String mode = entry.path("search").path("mode").asString("match");
                if ("include".equals(mode)) {
                    included.add(resource);
                } else if ("outcome".equals(mode) || "OperationOutcome".equals(resource.path("resourceType").asString(""))) {
                    outcomes.add(resource); // search-related warnings the server adds (e.g. an ignored parameter); never a match
                } else {
                    matches.add(resource);
                }
            }
            for (JsonNode link : bundle.path("link")) {
                String rel = link.path("relation").asString("");
                if ("next".equals(rel)) {
                    next = link.path("url").asString(null);
                } else if ("self".equals(rel)) {
                    self = link.path("url").asString(null);
                }
            }
        }
        return new SearchPage(bundle, matches, included, outcomes, total, self, next, response, warnings == null ? List.of() : warnings);
    }

    public boolean isBundle() {
        return bundle != null && "Bundle".equals(bundle.path("resourceType").asString(""));
    }

    public int count() {
        return resources.size();
    }
}
