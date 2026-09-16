package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A prior authorization as CMS-0057-F wants members to see it, derived from a PDex PriorAuthorization
 * ExplanationOfBenefit: status and decision, dates (requested, decided, valid period, ended), the items
 * and services with approved / used quantities, and denial reasons.
 */
public record PriorAuthSummary(
        String id,
        List<String> identifiers,
        List<String> profiles,
        boolean pdexProfileDeclared,
        String use,
        String status,
        String outcome,
        String decision,
        String decisionBasis,
        String type,
        String levelOfService,
        String created,
        String decisionDate,
        String validFrom,
        String validTo,
        String lastUpdated,
        String patient,
        String insurer,
        String provider,
        String facility,
        String enterer,
        List<String> preAuthRef,
        List<String> diagnoses,
        List<String> procedures,
        List<Adjudication> claimAdjudications,
        List<Item> items,
        List<Total> totals,
        List<String> denialReasons,
        List<String> processNotes,
        List<String> issues,
        JsonNode resource) {

    public record ReviewAction(String code, String display, String number, List<String> reasons, Boolean secondSurgicalOpinion) {
    }

    public record Adjudication(String category, String categoryDisplay, String amount, String value, String reason, ReviewAction reviewAction,
                               String actionDate) {
    }

    public record Item(int sequence, String category, String productOrService, List<String> modifiers, String serviced, String quantity,
                       String net, String decision, String preAuthPeriod, String preAuthIssueDate, List<String> traceNumbers,
                       String previousAuthorizationNumber, String administrationReferenceNumber, List<String> authorizedProviders,
                       String allowedUnits, String consumedUnits, List<String> denialReasons, List<Adjudication> adjudications) {
    }

    public record Total(String category, String categoryDisplay, String amount, String utilization) {
    }
}
