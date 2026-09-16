package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** The columns shown for a Patient in search results and the patient header. */
public record PatientSummary(String id, String display, String family, String given, String birthDate, String gender,
                             List<IdentifierView> identifiers, String lastUpdated, List<String> profiles, Boolean active,
                             String foundBy) {

    public record IdentifierView(String system, String value, String typeCode, String typeText) {
    }

    public static PatientSummary of(JsonNode patient, String foundBy) {
        String family = null;
        String given = null;
        String display = null;
        JsonNode names = patient.path("name");
        if (names.isArray() && names.size() > 0) {
            JsonNode best = names.get(0);
            for (JsonNode n : names) {
                if ("official".equals(n.path("use").asText())) {
                    best = n;
                    break;
                }
            }
            family = best.path("family").asText(null);
            List<String> givens = new ArrayList<>();
            for (JsonNode g : best.path("given")) {
                givens.add(g.asText());
            }
            given = givens.isEmpty() ? null : String.join(" ", givens);
            display = best.hasNonNull("text") ? best.get("text").asText()
                    : ((given == null ? "" : given + " ") + (family == null ? "" : family)).trim();
        }
        List<IdentifierView> ids = new ArrayList<>();
        for (JsonNode i : patient.path("identifier")) {
            String typeCode = null;
            String typeText = i.path("type").path("text").asText(null);
            JsonNode codings = i.path("type").path("coding");
            if (codings.isArray() && codings.size() > 0) {
                typeCode = codings.get(0).path("code").asText(null);
                if (typeText == null) {
                    typeText = codings.get(0).path("display").asText(null);
                }
            }
            ids.add(new IdentifierView(i.path("system").asText(null), i.path("value").asText(null), typeCode, typeText));
        }
        List<String> profiles = new ArrayList<>();
        for (JsonNode p : patient.path("meta").path("profile")) {
            profiles.add(p.asText());
        }
        return new PatientSummary(patient.path("id").asText(null), display == null || display.isBlank() ? "(no name)" : display, family, given,
                patient.path("birthDate").asText(null), patient.path("gender").asText(null), ids,
                patient.path("meta").path("lastUpdated").asText(null), profiles,
                patient.hasNonNull("active") ? patient.get("active").asBoolean() : null, foundBy);
    }
}
