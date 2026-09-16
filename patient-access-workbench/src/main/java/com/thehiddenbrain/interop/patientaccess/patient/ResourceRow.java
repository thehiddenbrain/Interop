package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** One resource in a table: type, id, profiles, key columns, profile-check summary and the raw JSON. */
public record ResourceRow(String resourceType, String id, List<String> profiles, String lastUpdated, Map<String, String> columns,
                          ProfileLiteChecker.Report checks, JsonNode resource) {
}
