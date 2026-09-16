package com.thehiddenbrain.interop.patientaccess.patient;

import tools.jackson.databind.JsonNode;

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
                if ("official".equals(n.path("use").asString(""))) {
                    best = n;
                    break;
                }
            }
            family = best.path("family").asString(null);
            List<String> givens = new ArrayList<>();
            for (JsonNode g : best.path("given")) {
                givens.add(g.asString(""));
            }
            given = givens.isEmpty() ? null : String.join(" ", givens);
            display = best.hasNonNull("text") ? best.get("text").asString("")
                    : ((given == null ? "" : given + " ") + (family == null ? "" : family)).trim();
        }
        List<IdentifierView> ids = new ArrayList<>();
        for (JsonNode i : patient.path("identifier")) {
            String typeCode = null;
            String typeText = i.path("type").path("text").asString(null);
            JsonNode codings = i.path("type").path("coding");
            if (codings.isArray() && codings.size() > 0) {
                typeCode = codings.get(0).path("code").asString(null);
                if (typeText == null) {
                    typeText = codings.get(0).path("display").asString(null);
                }
            }
            ids.add(new IdentifierView(i.path("system").asString(null), i.path("value").asString(null), typeCode, typeText));
        }
        List<String> profiles = new ArrayList<>();
        for (JsonNode p : patient.path("meta").path("profile")) {
            profiles.add(p.asString(""));
        }
        return new PatientSummary(patient.path("id").asString(null), display == null || display.isBlank() ? "(no name)" : display, family, given,
                patient.path("birthDate").asString(null), patient.path("gender").asString(null), ids,
                patient.path("meta").path("lastUpdated").asString(null), profiles,
                patient.hasNonNull("active") ? patient.get("active").asBoolean() : null, foundBy);
    }
}
