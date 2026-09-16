package com.thehiddenbrain.interop.patientaccess.fhir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** One page of a FHIR search: the Bundle, its resources, total and the next link. */
public record SearchPage(JsonNode bundle, List<JsonNode> resources, List<JsonNode> included, Integer total, String selfUrl,
                         String nextUrl, HttpResult response, List<String> warnings) {

    public static SearchPage of(HttpResult response, List<String> warnings) {
        JsonNode bundle = response.json();
        List<JsonNode> matches = new ArrayList<>();
        List<JsonNode> included = new ArrayList<>();
        Integer total = null;
        String self = null;
        String next = null;
        if (bundle != null && "Bundle".equals(bundle.path("resourceType").asText())) {
            if (bundle.hasNonNull("total")) {
                total = bundle.get("total").asInt();
            }
            for (JsonNode entry : bundle.path("entry")) {
                JsonNode resource = entry.get("resource");
                if (resource == null) {
                    continue;
                }
                String mode = entry.path("search").path("mode").asText("match");
                if ("include".equals(mode)) {
                    included.add(resource);
                } else {
                    matches.add(resource);
                }
            }
            for (JsonNode link : bundle.path("link")) {
                String rel = link.path("relation").asText();
                if ("next".equals(rel)) {
                    next = link.path("url").asText(null);
                } else if ("self".equals(rel)) {
                    self = link.path("url").asText(null);
                }
            }
        }
        return new SearchPage(bundle, matches, included, total, self, next, response, warnings == null ? List.of() : warnings);
    }

    public boolean isBundle() {
        return bundle != null && "Bundle".equals(bundle.path("resourceType").asText());
    }

    public int count() {
        return resources.size();
    }
}
