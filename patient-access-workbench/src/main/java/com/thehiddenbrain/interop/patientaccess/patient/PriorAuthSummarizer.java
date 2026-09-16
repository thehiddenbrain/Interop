package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.concept;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.money;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.reference;
import static com.thehiddenbrain.interop.patientaccess.patient.Fhir.text;

/** Reads a PDex PriorAuthorization EOB (profile 2.x) into a {@link PriorAuthSummary}. */
@Component
public class PriorAuthSummarizer {

    public static final String PDEX_PA_PROFILE = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization";
    public static final String EXT_LEVEL_OF_SERVICE = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-levelOfServiceCode";
    public static final String EXT_REVIEW_ACTION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-reviewAction";
    public static final String EXT_REVIEW_ACTION_CODE = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-reviewActionCode";
    public static final String EXT_WHEN_ADJUDICATED = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-when-adjudicated";
    public static final String EXT_UTILIZATION = "http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/PriorAuthorizationUtilization";
    public static final String EXT_ITEM_TRACE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber";
    public static final String EXT_ITEM_PREAUTH_ISSUE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemPreAuthIssueDate";
    public static final String EXT_ITEM_PREAUTH_PERIOD = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemPreAuthPeriod";
    public static final String EXT_AUTH_NUMBER = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-authorizationNumber";
    public static final String EXT_ADMIN_REF = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-administrationReferenceNumber";
    public static final String EXT_AUTHORIZED_DETAIL = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemAuthorizedDetail";
    public static final String EXT_AUTHORIZED_PROVIDER = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemAuthorizedProvider";
    public static final String CS_PDEX_DISCRIMINATOR = "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PDexAdjudicationDiscriminator";

    /** X12 278 HCR01 "Action Code" (X12 code list 306) as used by the PDex reviewActionCode extension. */
    public static final Map<String, String> REVIEW_ACTION_CODES = Map.of(
            "A1", "Certified in total",
            "A2", "Certified - partial",
            "A3", "Not certified",
            "A4", "Pended",
            "A6", "Modified",
            "C", "Cancelled",
            "CT", "Contact payer",
            "NA", "No action required");

    private final IgCatalog catalog;

    public PriorAuthSummarizer(IgCatalog catalog) {
        this.catalog = catalog;
    }

    public static boolean isPriorAuth(JsonNode eob) {
        if (!"ExplanationOfBenefit".equals(eob.path("resourceType").asText())) {
            return false;
        }
        if ("preauthorization".equals(eob.path("use").asText())) {
            return true;
        }
        for (JsonNode p : eob.path("meta").path("profile")) {
            if (p.asText().startsWith(PDEX_PA_PROFILE)) {
                return true;
            }
        }
        return false;
    }

    public PriorAuthSummary summarize(JsonNode eob) {
        List<String> issues = new ArrayList<>();
        List<String> profiles = Fhir.profiles(eob);
        boolean pdex = profiles.stream().anyMatch(p -> p.startsWith(PDEX_PA_PROFILE));
        if (!pdex) {
            issues.add("meta.profile does not declare " + PDEX_PA_PROFILE);
        }
        String use = text(eob.get("use"));
        if (!"preauthorization".equals(use)) {
            issues.add("use is '" + use + "', the PDex PriorAuthorization profile fixes it to 'preauthorization'");
        }
        List<String> identifiers = new ArrayList<>();
        for (JsonNode i : eob.path("identifier")) {
            String type = Fhir.code(i.path("type"), null);
            identifiers.add((type == null ? "" : type + " ") + text(i.get("value")));
        }
        List<String> preAuthRef = new ArrayList<>();
        for (JsonNode p : eob.path("preAuthRef")) {
            preAuthRef.add(p.asText());
        }
        List<String> diagnoses = new ArrayList<>();
        for (JsonNode d : eob.path("diagnosis")) {
            String dx = d.has("diagnosisCodeableConcept") ? concept(d.get("diagnosisCodeableConcept")) : reference(d.get("diagnosisReference"));
            String type = d.path("type").size() > 0 ? concept(d.path("type").get(0)) : null;
            diagnoses.add((type == null ? "" : type + ": ") + dx);
        }
        List<String> procedures = new ArrayList<>();
        for (JsonNode p : eob.path("procedure")) {
            procedures.add(p.has("procedureCodeableConcept") ? concept(p.get("procedureCodeableConcept")) : reference(p.get("procedureReference")));
        }
        List<PriorAuthSummary.Adjudication> claimAdj = adjudications(eob.path("adjudication"));
        List<PriorAuthSummary.Item> items = new ArrayList<>();
        Set<String> denialReasons = new LinkedHashSet<>();
        Set<String> decisions = new LinkedHashSet<>();
        String decisionDate = null;
        String validFrom = null;
        String validTo = null;
        JsonNode refPeriod = eob.get("preAuthRefPeriod");
        if (refPeriod != null && refPeriod.isArray()) {
            // preAuthRefPeriod is 0..* in R4 (the PDex example sends a list); the first period is the authorization's validity
            refPeriod = refPeriod.size() > 0 ? refPeriod.get(0) : null;
        }
        if (refPeriod != null) {
            validFrom = text(refPeriod.get("start"));
            validTo = text(refPeriod.get("end"));
        }
        for (JsonNode it : eob.path("item")) {
            List<PriorAuthSummary.Adjudication> adj = adjudications(it.path("adjudication"));
            String allowed = null;
            String consumed = null;
            List<String> itemDenials = new ArrayList<>();
            String itemDecision = null;
            for (PriorAuthSummary.Adjudication a : adj) {
                if ("allowedunits".equals(a.category())) {
                    allowed = a.value();
                } else if ("consumedunits".equals(a.category())) {
                    consumed = a.value();
                } else if ("denialreason".equals(a.category()) && a.reason() != null) {
                    itemDenials.add(a.reason());
                }
                if (a.reviewAction() != null && a.reviewAction().code() != null && itemDecision == null) {
                    itemDecision = a.reviewAction().code();
                }
                if (a.actionDate() != null && decisionDate == null) {
                    decisionDate = a.actionDate();
                }
            }
            String preAuthIssue = Fhir.extensionValue(it, EXT_ITEM_PREAUTH_ISSUE);
            if (preAuthIssue != null && decisionDate == null) {
                decisionDate = preAuthIssue;
            }
            JsonNode preAuthPeriodExt = Fhir.extension(it, EXT_ITEM_PREAUTH_PERIOD);
            String preAuthPeriod = preAuthPeriodExt == null ? null : Fhir.period(preAuthPeriodExt.get("valuePeriod"));
            if (preAuthPeriodExt != null && validFrom == null && validTo == null) {
                validFrom = text(preAuthPeriodExt.path("valuePeriod").get("start"));
                validTo = text(preAuthPeriodExt.path("valuePeriod").get("end"));
            }
            List<String> traces = new ArrayList<>();
            for (JsonNode t : Fhir.extensions(it, EXT_ITEM_TRACE)) {
                JsonNode v = t.get("valueIdentifier");
                traces.add(v == null ? Fhir.choiceText(t, "value") : text(v.get("value")));
            }
            List<String> providers = new ArrayList<>();
            for (JsonNode p : Fhir.extensions(it, EXT_AUTHORIZED_PROVIDER)) {
                String who = null;
                for (JsonNode inner : p.path("extension")) {
                    if ("provider".equals(inner.path("url").asText())) {
                        who = reference(inner.get("valueReference"));
                    }
                }
                providers.add(who == null ? p.toString() : who);
            }
            List<String> modifiers = new ArrayList<>();
            for (JsonNode m : it.path("modifier")) {
                modifiers.add(concept(m));
            }
            String decisionDisplay = itemDecision == null ? deriveFromAdjudication(itemDenials, allowed) : display(itemDecision);
            if (decisionDisplay != null) {
                decisions.add(decisionDisplay);
            }
            denialReasons.addAll(itemDenials);
            items.add(new PriorAuthSummary.Item(it.path("sequence").asInt(), concept(it.get("category")), concept(it.get("productOrService")),
                    modifiers, Fhir.choiceText(it, "serviced"), Fhir.quantity(it.get("quantity")), money(it.get("net")), decisionDisplay,
                    preAuthPeriod, preAuthIssue, traces, identifierValue(Fhir.extension(it, EXT_AUTH_NUMBER)),
                    Fhir.extensionValue(it, EXT_ADMIN_REF), providers, allowed, consumed, itemDenials, adj));
        }
        for (PriorAuthSummary.Adjudication a : claimAdj) {
            if ("denialreason".equals(a.category()) && a.reason() != null) {
                denialReasons.add(a.reason());
            }
            if (a.reviewAction() != null && a.reviewAction().code() != null) {
                decisions.add(display(a.reviewAction().code()));
            }
            if (a.actionDate() != null && decisionDate == null) {
                decisionDate = a.actionDate();
            }
        }
        List<PriorAuthSummary.Total> totals = new ArrayList<>();
        for (JsonNode t : eob.path("total")) {
            String cat = Fhir.code(t.path("category"), null);
            String util = Fhir.extensionValue(t, EXT_UTILIZATION);
            totals.add(new PriorAuthSummary.Total(cat, concept(t.get("category")), money(t.get("amount")), util));
        }
        List<String> notes = new ArrayList<>();
        for (JsonNode n : eob.path("processNote")) {
            notes.add(text(n.get("text")));
        }
        String status = text(eob.get("status"));
        String outcome = text(eob.get("outcome"));
        String[] decision = overallDecision(decisions, status, outcome, denialReasons.isEmpty());
        if (items.isEmpty()) {
            issues.add("no item: the items and services of the authorization are not listed");
        }
        if (decisions.isEmpty()) {
            issues.add("no reviewAction extension on any adjudication: the decision (approved / denied / pended) is not expressed explicitly");
        }
        if (decisionDate == null) {
            issues.add("no decision date (item preAuthIssueDate or adjudication when-adjudicated extension)");
        }
        if (validFrom == null && validTo == null) {
            issues.add("no authorization period (preAuthRefPeriod or item preAuthPeriod)");
        }
        if (eob.path("insurer").isMissingNode() || eob.path("provider").isMissingNode()) {
            issues.add("insurer and provider are required (1..1) by the profile");
        }
        return new PriorAuthSummary(Fhir.idOf(eob), identifiers, profiles, pdex, use, status, outcome, decision[0], decision[1],
                concept(eob.get("type")), Fhir.extensionValue(eob, EXT_LEVEL_OF_SERVICE), text(eob.get("created")), decisionDate, validFrom,
                validTo, Fhir.lastUpdated(eob), reference(eob.get("patient")), reference(eob.get("insurer")), reference(eob.get("provider")),
                reference(eob.get("facility")), reference(eob.get("enterer")), preAuthRef, diagnoses, procedures, claimAdj, items, totals,
                new ArrayList<>(denialReasons), notes, issues, eob);
    }

    List<PriorAuthSummary.Adjudication> adjudications(JsonNode arr) {
        List<PriorAuthSummary.Adjudication> out = new ArrayList<>();
        for (JsonNode a : arr) {
            String cat = Fhir.code(a.path("category"), null);
            String catDisplay = concept(a.get("category"));
            if (cat != null) {
                String known = catalog.display("pdexAdjudicationDiscriminator", cat);
                if (known == null) {
                    known = catalog.display("c4bbAdjudication", cat);
                }
                if (known != null && (catDisplay == null || catDisplay.equals(cat))) {
                    catDisplay = known;
                }
            }
            PriorAuthSummary.ReviewAction ra = reviewAction(Fhir.extension(a, EXT_REVIEW_ACTION));
            out.add(new PriorAuthSummary.Adjudication(cat, catDisplay, money(a.get("amount")), text(a.get("value")), concept(a.get("reason")), ra,
                    Fhir.extensionValue(a, EXT_WHEN_ADJUDICATED)));
        }
        return out;
    }

    static PriorAuthSummary.ReviewAction reviewAction(JsonNode ext) {
        if (ext == null) {
            return null;
        }
        String code = null;
        String number = null;
        Boolean second = null;
        List<String> reasons = new ArrayList<>();
        for (JsonNode inner : ext.path("extension")) {
            String url = inner.path("url").asText();
            if (url.equals("code") || url.equals(EXT_REVIEW_ACTION_CODE)) {
                JsonNode cc = inner.get("valueCodeableConcept");
                code = cc != null ? Fhir.code(cc, null) : Fhir.choiceText(inner, "value");
            } else if (url.equals("number")) {
                number = Fhir.choiceText(inner, "value");
            } else if (url.equals("reasonCode")) {
                reasons.add(concept(inner.get("valueCodeableConcept")));
            } else if (url.equals("secondSurgicalOpinionFlag")) {
                second = inner.path("valueBoolean").asBoolean();
            }
        }
        return new PriorAuthSummary.ReviewAction(code, code == null ? null : display(code), number, reasons, second);
    }

    static String display(String code) {
        String d = REVIEW_ACTION_CODES.get(code);
        return d == null ? code : d + " (" + code + ")";
    }

    static String identifierValue(JsonNode ext) {
        if (ext == null) {
            return null;
        }
        JsonNode v = ext.get("valueIdentifier");
        return v == null ? Fhir.choiceText(ext, "value") : text(v.get("value"));
    }

    static String deriveFromAdjudication(List<String> denials, String allowedUnits) {
        if (!denials.isEmpty()) {
            return "Denied (from denialreason adjudication)";
        }
        if (allowedUnits != null) {
            return "Approved units " + allowedUnits + " (from allowedunits adjudication)";
        }
        return null;
    }

    /** [decision, how it was derived]. */
    static String[] overallDecision(Set<String> itemDecisions, String status, String outcome, boolean noDenials) {
        boolean approved = itemDecisions.stream().anyMatch(d -> d.startsWith("Certified in total") || d.startsWith("Approved"));
        boolean partial = itemDecisions.stream().anyMatch(d -> d.startsWith("Certified - partial") || d.startsWith("Modified"));
        boolean denied = itemDecisions.stream().anyMatch(d -> d.startsWith("Not certified") || d.startsWith("Denied"));
        boolean pended = itemDecisions.stream().anyMatch(d -> d.startsWith("Pended") || d.startsWith("Contact payer"));
        boolean cancelled = itemDecisions.stream().anyMatch(d -> d.startsWith("Cancelled")) || "cancelled".equals(status);
        if (cancelled) {
            return new String[]{"CANCELLED", "status cancelled or a review action 'Cancelled'"};
        }
        if (pended && !approved && !denied) {
            return new String[]{"PENDING", "review action 'Pended' / 'Contact payer'"};
        }
        if (denied && !approved && !partial) {
            return new String[]{"DENIED", "review action 'Not certified' or denial reason on every decided item"};
        }
        if (partial || (approved && denied)) {
            return new String[]{"PARTIALLY APPROVED", "mix of approved and denied / modified items"};
        }
        if (approved) {
            return new String[]{"APPROVED", "review action 'Certified in total'"};
        }
        if ("queued".equals(outcome) || "draft".equals(status)) {
            return new String[]{"PENDING", "outcome queued / status draft (no explicit review action)"};
        }
        if ("error".equals(outcome)) {
            return new String[]{"ERROR", "outcome error"};
        }
        if ("complete".equals(outcome) && noDenials) {
            return new String[]{"APPROVED (implied)", "outcome complete without denial reasons and without an explicit review action"};
        }
        if ("partial".equals(outcome)) {
            return new String[]{"PARTIAL (implied)", "outcome partial without explicit review actions"};
        }
        return new String[]{"UNKNOWN", "no review action, denial reason or outcome to derive a decision from"};
    }
}
