package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.choiceText;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.concept;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.money;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.period;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.reference;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.text;

/** The key columns of each resource type, for tables in the UI. Works on raw JSON so odd data still renders. */
public final class ResourceSummaries {

    private ResourceSummaries() {
    }

    public static Map<String, String> columns(JsonNode r) {
        Map<String, String> c = new LinkedHashMap<>();
        String type = r.path("resourceType").asText("");
        switch (type) {
            case "Patient" -> {
                PatientSummary p = PatientSummary.of(r, null);
                c.put("name", p.display());
                c.put("birthDate", p.birthDate());
                c.put("gender", p.gender());
                List<String> ids = new ArrayList<>();
                for (PatientSummary.IdentifierView i : p.identifiers()) {
                    ids.add((i.typeCode() == null ? "" : i.typeCode() + " ") + i.value());
                }
                c.put("identifiers", String.join(", ", ids));
            }
            case "Coverage" -> {
                c.put("status", text(r.get("status")));
                c.put("type", concept(r.get("type")));
                c.put("subscriberId", text(r.get("subscriberId")));
                c.put("beneficiary", reference(r.get("beneficiary")));
                c.put("relationship", concept(r.get("relationship")));
                c.put("period", period(r.get("period")));
                c.put("payor", references(r.path("payor")));
                for (JsonNode cls : r.path("class")) {
                    String code = Fhir.code(cls.path("type"), null);
                    if (code != null) {
                        c.put("class." + code, text(cls.get("value")) + (cls.hasNonNull("name") ? " (" + cls.get("name").asText() + ")" : ""));
                    }
                }
            }
            case "ExplanationOfBenefit" -> {
                c.put("claimId", identifier(r, "uc"));
                c.put("use", text(r.get("use")));
                c.put("type", concept(r.get("type")));
                c.put("subType", concept(r.get("subType")));
                c.put("status", text(r.get("status")));
                c.put("outcome", text(r.get("outcome")));
                c.put("billablePeriod", period(r.get("billablePeriod")));
                c.put("created", text(r.get("created")));
                c.put("provider", reference(r.get("provider")));
                c.put("insurer", reference(r.get("insurer")));
                c.put("items", String.valueOf(r.path("item").size()));
                for (JsonNode t : r.path("total")) {
                    String cat = Fhir.code(t.path("category"), null);
                    if (cat != null) {
                        c.put("total." + cat, money(t.get("amount")));
                    }
                }
                if (r.has("payment")) {
                    c.put("payment", Fhir.join(List.of(nz(concept(r.path("payment").get("type"))), nz(money(r.path("payment").get("amount"))),
                            nz(text(r.path("payment").get("date"))))));
                }
                c.put("processNotes", String.valueOf(r.path("processNote").size()));
            }
            case "Condition" -> {
                c.put("code", concept(r.get("code")));
                c.put("category", concepts(r.path("category")));
                c.put("clinicalStatus", concept(r.get("clinicalStatus")));
                c.put("verificationStatus", concept(r.get("verificationStatus")));
                c.put("onset", choiceText(r, "onset"));
                c.put("recordedDate", text(r.get("recordedDate")));
                c.put("encounter", reference(r.get("encounter")));
            }
            case "Observation" -> {
                c.put("code", concept(r.get("code")));
                c.put("category", concepts(r.path("category")));
                c.put("value", observationValue(r));
                c.put("effective", choiceText(r, "effective"));
                c.put("status", text(r.get("status")));
                c.put("interpretation", concepts(r.path("interpretation")));
            }
            case "MedicationRequest" -> {
                c.put("medication", r.has("medicationCodeableConcept") ? concept(r.get("medicationCodeableConcept")) : reference(r.get("medicationReference")));
                c.put("status", text(r.get("status")));
                c.put("intent", text(r.get("intent")));
                c.put("authoredOn", text(r.get("authoredOn")));
                c.put("requester", reference(r.get("requester")));
                c.put("dosage", r.path("dosageInstruction").size() > 0 ? text(r.path("dosageInstruction").get(0).get("text")) : null);
            }
            case "MedicationDispense" -> {
                c.put("medication", r.has("medicationCodeableConcept") ? concept(r.get("medicationCodeableConcept")) : reference(r.get("medicationReference")));
                c.put("status", text(r.get("status")));
                c.put("whenHandedOver", text(r.get("whenHandedOver")));
                c.put("quantity", Fhir.quantity(r.get("quantity")));
                c.put("daysSupply", Fhir.quantity(r.get("daysSupply")));
                c.put("performer", r.path("performer").size() > 0 ? reference(r.path("performer").get(0).get("actor")) : null);
            }
            case "AllergyIntolerance" -> {
                c.put("code", concept(r.get("code")));
                c.put("clinicalStatus", concept(r.get("clinicalStatus")));
                c.put("verificationStatus", concept(r.get("verificationStatus")));
                c.put("category", strings(r.path("category")));
                c.put("criticality", text(r.get("criticality")));
                c.put("reaction", r.path("reaction").size() > 0 ? concepts(r.path("reaction").get(0).path("manifestation")) : null);
            }
            case "Immunization" -> {
                c.put("vaccine", concept(r.get("vaccineCode")));
                c.put("status", text(r.get("status")));
                c.put("occurrence", choiceText(r, "occurrence"));
                c.put("primarySource", text(r.get("primarySource")));
            }
            case "Procedure" -> {
                c.put("code", concept(r.get("code")));
                c.put("status", text(r.get("status")));
                c.put("performed", choiceText(r, "performed"));
                c.put("encounter", reference(r.get("encounter")));
            }
            case "Encounter" -> {
                c.put("class", Fhir.coding(r.get("class")));
                c.put("type", concepts(r.path("type")));
                c.put("status", text(r.get("status")));
                c.put("period", period(r.get("period")));
                c.put("serviceProvider", reference(r.get("serviceProvider")));
                c.put("location", r.path("location").size() > 0 ? reference(r.path("location").get(0).get("location")) : null);
            }
            case "DocumentReference" -> {
                c.put("type", concept(r.get("type")));
                c.put("category", concepts(r.path("category")));
                c.put("status", text(r.get("status")));
                c.put("date", text(r.get("date")));
                List<String> ct = new ArrayList<>();
                for (JsonNode content : r.path("content")) {
                    ct.add(text(content.path("attachment").get("contentType")));
                }
                c.put("content", String.join(", ", ct));
            }
            case "DiagnosticReport" -> {
                c.put("code", concept(r.get("code")));
                c.put("category", concepts(r.path("category")));
                c.put("status", text(r.get("status")));
                c.put("effective", choiceText(r, "effective"));
                c.put("results", String.valueOf(r.path("result").size()));
            }
            case "CarePlan" -> {
                c.put("category", concepts(r.path("category")));
                c.put("status", text(r.get("status")));
                c.put("intent", text(r.get("intent")));
                c.put("period", period(r.get("period")));
                c.put("activities", String.valueOf(r.path("activity").size()));
            }
            case "CareTeam" -> {
                c.put("status", text(r.get("status")));
                c.put("name", text(r.get("name")));
                List<String> members = new ArrayList<>();
                for (JsonNode p : r.path("participant")) {
                    members.add(nz(concepts(p.path("role"))) + " " + nz(reference(p.get("member"))));
                }
                c.put("participants", String.join("; ", members));
            }
            case "Goal" -> {
                c.put("description", concept(r.get("description")));
                c.put("lifecycleStatus", text(r.get("lifecycleStatus")));
                c.put("target", r.path("target").size() > 0 ? choiceText(r.path("target").get(0), "due") : null);
            }
            case "Device" -> {
                c.put("type", concept(r.get("type")));
                c.put("status", text(r.get("status")));
                c.put("udi", r.path("udiCarrier").size() > 0 ? text(r.path("udiCarrier").get(0).get("deviceIdentifier")) : null);
                c.put("expiration", text(r.get("expirationDate")));
            }
            case "ServiceRequest" -> {
                c.put("code", concept(r.get("code")));
                c.put("category", concepts(r.path("category")));
                c.put("status", text(r.get("status")));
                c.put("intent", text(r.get("intent")));
                c.put("authoredOn", text(r.get("authoredOn")));
            }
            case "Provenance" -> {
                c.put("recorded", text(r.get("recorded")));
                c.put("targets", references(r.path("target")));
                List<String> agents = new ArrayList<>();
                for (JsonNode a : r.path("agent")) {
                    agents.add(nz(concept(a.get("type"))) + " " + nz(reference(a.get("who"))));
                }
                c.put("agents", String.join("; ", agents));
            }
            case "Organization", "Practitioner", "PractitionerRole", "Location", "RelatedPerson" -> {
                if (r.has("name") && r.get("name").isArray()) {
                    c.put("name", PatientSummary.of(r, null).display());
                } else {
                    c.put("name", text(r.get("name")));
                }
                c.put("identifiers", identifiers(r));
                c.put("active", text(r.get("active")));
                if (r.has("address") && r.get("address").isArray() && r.get("address").size() > 0) {
                    JsonNode a = r.get("address").get(0);
                    c.put("address", Fhir.join(List.of(nz(text(a.get("city"))), nz(text(a.get("state"))), nz(text(a.get("postalCode"))))));
                }
                if (r.has("practitioner")) {
                    c.put("practitioner", reference(r.get("practitioner")));
                    c.put("organization", reference(r.get("organization")));
                    c.put("specialty", concepts(r.path("specialty")));
                }
            }
            case "InsurancePlan" -> {
                c.put("name", text(r.get("name")));
                c.put("status", text(r.get("status")));
                c.put("type", concepts(r.path("type")));
                c.put("period", period(r.get("period")));
                c.put("ownedBy", reference(r.get("ownedBy")));
            }
            case "MedicationKnowledge" -> {
                c.put("code", concept(r.get("code")));
                c.put("status", text(r.get("status")));
                c.put("doseForm", concept(r.get("doseForm")));
            }
            case "Basic" -> {
                c.put("code", concept(r.get("code")));
                c.put("subject", reference(r.get("subject")));
                c.put("created", text(r.get("created")));
            }
            default -> {
                c.put("status", text(r.get("status")));
                c.put("code", concept(r.get("code")));
            }
        }
        c.values().removeIf(v -> v == null || v.isBlank() || v.equals("null"));
        return c;
    }

    public static ResourceRow row(JsonNode resource, ProfileLiteChecker.Report checks) {
        return new ResourceRow(resource.path("resourceType").asText(null), Fhir.idOf(resource), Fhir.profiles(resource),
                Fhir.lastUpdated(resource), columns(resource), checks, resource);
    }

    static String observationValue(JsonNode r) {
        String v = choiceText(r, "value");
        if (v != null) {
            return v;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode comp : r.path("component")) {
            parts.add(nz(concept(comp.get("code"))) + ": " + nz(choiceText(comp, "value")));
        }
        if (!parts.isEmpty()) {
            return String.join(", ", parts);
        }
        JsonNode dar = r.get("dataAbsentReason");
        return dar == null ? null : "absent: " + concept(dar);
    }

    static String identifier(JsonNode r, String typeCode) {
        for (JsonNode i : r.path("identifier")) {
            if (Fhir.hasCoding(i.path("type"), null, typeCode)) {
                return text(i.get("value"));
            }
        }
        return r.path("identifier").size() > 0 ? text(r.path("identifier").get(0).get("value")) : null;
    }

    static String identifiers(JsonNode r) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : r.path("identifier")) {
            String type = Fhir.code(i.path("type"), null);
            out.add((type == null ? "" : type + " ") + nz(text(i.get("value"))));
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    static String concepts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : arr) {
            String t = concept(c);
            if (t != null) {
                out.add(t);
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    static String references(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : arr) {
            String t = reference(c);
            if (t != null) {
                out.add(t);
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    static String strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : arr) {
            out.add(c.asText());
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
