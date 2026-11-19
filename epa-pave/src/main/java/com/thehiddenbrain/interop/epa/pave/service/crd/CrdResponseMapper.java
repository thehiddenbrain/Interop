package com.thehiddenbrain.interop.epa.pave.service.crd;

import com.thehiddenbrain.interop.epa.pave.client.crd.OnyxCrdConventions;
import com.thehiddenbrain.interop.epa.pave.model.AdditionalField;
import com.thehiddenbrain.interop.epa.pave.model.CrdOutcome;
import com.thehiddenbrain.interop.epa.pave.model.FhirViews;
import com.thehiddenbrain.interop.epa.pave.model.IssueView;
import com.thehiddenbrain.interop.epa.pave.model.JsonSafe;
import com.thehiddenbrain.interop.epa.pave.model.OperationOutcomes;
import com.thehiddenbrain.interop.epa.pave.model.Presence;
import com.thehiddenbrain.interop.epa.pave.model.Shown;
import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.model.TrackedJson;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.model.crd.ActionView;
import com.thehiddenbrain.interop.epa.pave.model.crd.CardLinkView;
import com.thehiddenbrain.interop.epa.pave.model.crd.CardView;
import com.thehiddenbrain.interop.epa.pave.model.crd.CodedValue;
import com.thehiddenbrain.interop.epa.pave.model.crd.CoverageDetailView;
import com.thehiddenbrain.interop.epa.pave.model.crd.CoverageInformationView;
import com.thehiddenbrain.interop.epa.pave.model.crd.CrdDetail;
import com.thehiddenbrain.interop.epa.pave.model.crd.CrdResponseKind;
import com.thehiddenbrain.interop.epa.pave.model.crd.SubExtensionView;
import com.thehiddenbrain.interop.epa.pave.model.crd.SuggestionView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads a CRD response into the designed view, the standard codes and an outcome.
 *
 * <p>The response is read through {@link TrackedJson}. Every field placed in the view is consumed
 * by the read that placed it, and whatever is left is returned as additional fields, so a field
 * this mapper was never told about appears on screen without anyone having to add it here.
 *
 * <p>The same reading is applied to a success, a 400, a 500 and an OperationOutcome. A fault
 * response is not reduced to its status: it often carries the most useful detail of a session.
 */
public final class CrdResponseMapper {

    public CrdReading read(VendorExchange exchange) {
        List<CardView> cards = new ArrayList<>();
        List<ActionView> systemActions = new ArrayList<>();
        List<CoverageInformationView> coverage = new ArrayList<>();
        List<IssueView> issues = List.of();
        List<AdditionalField> additional = List.of();
        Presence cardsPresence = Presence.ABSENT;
        Presence actionsPresence = Presence.ABSENT;
        CrdResponseKind kind;
        String note = null;

        JsonNode json = exchange.json();
        if (json == null) {
            if (exchange.httpStatus() == null) {
                kind = CrdResponseKind.NO_RESPONSE;
                note = "No response arrived.";
            } else if (exchange.responseBody() == null) {
                kind = CrdResponseKind.NO_BODY;
                note = "The response has no body.";
            } else {
                kind = CrdResponseKind.NOT_JSON;
                note = "The body could not be read as JSON, so it is shown exactly as received in the wire view. "
                        + Texts.orElse(exchange.parseError(), "");
            }
        } else {
            TrackedJson tracked = TrackedJson.of(json);
            TrackedJson.Node root = tracked.root();
            if (OperationOutcomes.is(json)) {
                kind = CrdResponseKind.OPERATION_OUTCOME;
                root.text("resourceType");
                issues = OperationOutcomes.issues(root);
            } else if (root.isObject()) {
                kind = CrdResponseKind.CDS_HOOKS;
                cardsPresence = root.presence("cards");
                int index = 0;
                for (TrackedJson.Node card : root.children("cards")) {
                    if (card.isObject()) {
                        cards.add(card(card, index, coverage));
                    }
                    index++;
                }
                actionsPresence = root.presence("systemActions");
                index = 0;
                for (TrackedJson.Node action : root.children("systemActions")) {
                    if (action.isObject()) {
                        systemActions.add(action(action, "systemActions[" + index + "]", coverage));
                    }
                    index++;
                }
            } else {
                kind = CrdResponseKind.OTHER_JSON;
                note = "The body is JSON but is not an object, so none of it could be interpreted. All of it is listed below.";
            }
            additional = tracked.unconsumed();
        }

        Map<String, List<String>> codes = new LinkedHashMap<>();
        Set<String> vendors = new LinkedHashSet<>();
        List<CrdOutcome> outcomes = new ArrayList<>();
        for (CoverageInformationView view : coverage) {
            outcomes.add(view.outcome());
            addCode(codes, CrdCodes.COVERED, view.covered());
            addCode(codes, CrdCodes.PA_NEEDED, view.paNeeded());
            addCode(codes, CrdCodes.DOC_NEEDED, view.docNeeded());
            view.docPurpose().forEach(value -> addCode(codes, CrdCodes.DOC_PURPOSE, value));
            view.infoNeeded().forEach(value -> addCode(codes, CrdCodes.INFO_NEEDED, value));
            if (view.satisfiedPaId().hasValue()) {
                add(codes, CrdCodes.SATISFIED_PA_ID, view.satisfiedPaId().value());
            }
            if (Texts.present(view.delegatedVendor())) {
                vendors.add(view.delegatedVendor());
                add(codes, CrdCodes.DELEGATED_VENDOR, view.delegatedVendor());
            }
        }
        CrdDetail detail = new CrdDetail(kind, note, cardsPresence, cards, actionsPresence, systemActions, coverage, issues,
                List.of(), true);
        return new CrdReading(detail, CrdOutcomeRules.overall(exchange, outcomes), codes,
                vendors.isEmpty() ? null : String.join("; ", vendors), additional);
    }

    private static void addCode(Map<String, List<String>> codes, String element, CodedValue value) {
        if (value != null && Texts.present(value.code())) {
            add(codes, element, value.code());
        }
    }

    private static void add(Map<String, List<String>> codes, String element, String value) {
        List<String> values = codes.computeIfAbsent(element, key -> new ArrayList<>());
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private CardView card(TrackedJson.Node card, int index, List<CoverageInformationView> coverage) {
        String path = "cards[" + index + "]";
        TrackedJson.Node source = card.child("source");
        Shown topic = source.child("topic").exists() ? FhirViews.coding(source.child("topic")) : Shown.absent();

        List<CardLinkView> links = new ArrayList<>();
        for (TrackedJson.Node link : card.children("links")) {
            if (link.isObject()) {
                links.add(new CardLinkView(link.shown("label"), link.shown("url"), link.shown("type"), link.shown("appContext")));
            }
        }

        List<SuggestionView> suggestions = new ArrayList<>();
        int suggestionIndex = 0;
        for (TrackedJson.Node suggestion : card.children("suggestions")) {
            if (suggestion.isObject()) {
                List<ActionView> actions = new ArrayList<>();
                int actionIndex = 0;
                for (TrackedJson.Node action : suggestion.children("actions")) {
                    if (action.isObject()) {
                        actions.add(action(action, path + ".suggestions[" + suggestionIndex + "].actions[" + actionIndex + "]", coverage));
                    }
                    actionIndex++;
                }
                suggestions.add(new SuggestionView(suggestion.shown("label"), suggestion.shown("uuid"),
                        suggestion.shown("isRecommended"), actions));
            }
            suggestionIndex++;
        }

        List<Shown> overrideReasons = new ArrayList<>();
        for (TrackedJson.Node reason : card.children("overrideReasons")) {
            overrideReasons.add(FhirViews.coding(reason));
        }

        cardExtension(card.child("extension"), path + ".extension", coverage);

        return new CardView(index, card.shown("uuid"), card.shown("summary"), card.shown("detail"), card.shown("indicator"),
                source.exists() ? source.shown("label") : Shown.absent(),
                source.exists() ? source.shown("url") : Shown.absent(),
                source.exists() ? source.shown("icon") : Shown.absent(),
                topic, card.shown("selectionBehavior"), links, suggestions, overrideReasons);
    }

    private ActionView action(TrackedJson.Node action, String path, List<CoverageInformationView> coverage) {
        TrackedJson.Node resource = action.child("resource");
        Shown resourceType = Shown.absent();
        Shown resourceId = action.shown("resourceId");
        if (resource.isObject()) {
            resourceType = resource.shown("resourceType");
            if (!resourceId.hasValue()) {
                resourceId = resource.shown("id");
            } else {
                resource.shown("id");
            }
            resourceExtensions(resource, path + ".resource", coverage);
        }
        return new ActionView(path, action.shown("type"), action.shown("description"), resourceType, resourceId);
    }

    /** Coverage information on a FHIR resource: its extension array, each entry carrying the extension url. */
    private void resourceExtensions(TrackedJson.Node resource, String path, List<CoverageInformationView> coverage) {
        String type = JsonSafe.presentText(resource.raw(), "resourceType");
        String id = JsonSafe.presentText(resource.raw(), "id");
        Shown appliesTo = type == null && id == null ? Shown.absent() : Shown.of(type != null && id != null ? type + "/" + id : Texts.firstPresent(type, id));
        extensionArray(resource.child("extension"), path + ".extension", appliesTo, coverage);
    }

    private void extensionArray(TrackedJson.Node array, String path, Shown appliesTo, List<CoverageInformationView> coverage) {
        int index = 0;
        for (TrackedJson.Node extension : array.elements()) {
            if (isCoverageInformation(extension)) {
                extension.text("url");
                coverage.add(coverageInformation(extension.children("extension"), path + "[" + index + "]", appliesTo));
            }
            index++;
        }
    }

    /**
     * Coverage information on a card. A CDS Hooks card extension is a plain object, not a FHIR
     * extension array, and vendors differ on what they put in it, so several shapes are accepted:
     * a FHIR-style array, an object whose values are extension objects or arrays of them, and an
     * object keyed by the extension url whose value is the list of sub-extensions.
     */
    private void cardExtension(TrackedJson.Node extension, String path, List<CoverageInformationView> coverage) {
        if (extension.isArray()) {
            extensionArray(extension, path, Shown.absent(), coverage);
            return;
        }
        if (!extension.isObject()) {
            return;
        }
        for (Map.Entry<String, TrackedJson.Node> property : extension.properties()) {
            String propertyPath = path + "." + property.getKey();
            TrackedJson.Node value = property.getValue();
            if (isCoverageInformation(value)) {
                value.text("url");
                coverage.add(coverageInformation(value.children("extension"), propertyPath, Shown.absent()));
            } else if (value.isArray()) {
                boolean anyTyped = value.elements().stream().anyMatch(CrdResponseMapper::isCoverageInformation);
                if (anyTyped) {
                    extensionArray(value, propertyPath, Shown.absent(), coverage);
                } else if (namesCoverageInformation(property.getKey()) && looksLikeSubExtensions(value)) {
                    coverage.add(coverageInformation(value.elements(), propertyPath, Shown.absent()));
                }
            } else if (value.isObject() && namesCoverageInformation(property.getKey()) && value.child("extension").isArray()) {
                coverage.add(coverageInformation(value.children("extension"), propertyPath + ".extension", Shown.absent()));
            }
        }
    }

    private static boolean isCoverageInformation(TrackedJson.Node node) {
        return node.isObject() && CrdCodes.COVERAGE_INFORMATION.equals(JsonSafe.text(node.raw(), "url"));
    }

    private static boolean namesCoverageInformation(String key) {
        return key != null && key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").contains("coverageinformation");
    }

    private static boolean looksLikeSubExtensions(TrackedJson.Node array) {
        List<TrackedJson.Node> elements = array.elements();
        return !elements.isEmpty() && elements.stream().allMatch(e -> e.isObject() && JsonSafe.presentText(e.raw(), "url") != null);
    }

    private CoverageInformationView coverageInformation(List<TrackedJson.Node> subExtensions, String foundAt, Shown appliesTo) {
        CodedValue covered = CodedValue.notReturned();
        CodedValue paNeeded = CodedValue.notReturned();
        CodedValue docNeeded = CodedValue.notReturned();
        boolean seenCovered = false;
        boolean seenPaNeeded = false;
        boolean seenDocNeeded = false;
        List<CodedValue> docPurpose = new ArrayList<>();
        List<CodedValue> infoNeeded = new ArrayList<>();
        Shown satisfiedPaId = Shown.absent();
        Shown coverageRef = Shown.absent();
        Shown assertionId = Shown.absent();
        Shown date = Shown.absent();
        List<Shown> questionnaires = new ArrayList<>();
        List<Shown> reasons = new ArrayList<>();
        List<CoverageDetailView> details = new ArrayList<>();
        List<SubExtensionView> other = new ArrayList<>();
        boolean coverageInactive = false;
        String vendor = null;
        String vendorBasis = null;

        for (TrackedJson.Node sub : subExtensions) {
            if (!sub.isObject()) {
                continue;
            }
            String url = sub.presentText("url");
            if (url == null) {
                other.add(new SubExtensionView("(no url)", FhirViews.extensionValueShown(sub)));
                continue;
            }
            switch (url) {
                case CrdCodes.COVERED -> {
                    if (seenCovered) {
                        other.add(new SubExtensionView(url + " (repeated)", FhirViews.extensionValueShown(sub)));
                    } else {
                        covered = CrdCodes.coded(url, presentCode(sub));
                        seenCovered = true;
                    }
                }
                case CrdCodes.PA_NEEDED -> {
                    if (seenPaNeeded) {
                        other.add(new SubExtensionView(url + " (repeated)", FhirViews.extensionValueShown(sub)));
                    } else {
                        paNeeded = CrdCodes.coded(url, presentCode(sub));
                        seenPaNeeded = true;
                    }
                }
                case CrdCodes.DOC_NEEDED -> {
                    if (seenDocNeeded) {
                        other.add(new SubExtensionView(url + " (repeated)", FhirViews.extensionValueShown(sub)));
                    } else {
                        docNeeded = CrdCodes.coded(url, presentCode(sub));
                        seenDocNeeded = true;
                    }
                }
                case CrdCodes.DOC_PURPOSE -> docPurpose.add(CrdCodes.coded(url, presentCode(sub)));
                case CrdCodes.INFO_NEEDED -> infoNeeded.add(CrdCodes.coded(url, presentCode(sub)));
                case CrdCodes.SATISFIED_PA_ID -> satisfiedPaId = present(FhirViews.extensionValueShown(sub));
                case "coverage" -> coverageRef = present(FhirViews.extensionValueShown(sub));
                case "coverage-assertion-id" -> assertionId = present(FhirViews.extensionValueShown(sub));
                case "date" -> date = present(FhirViews.extensionValueShown(sub));
                case "questionnaire" -> questionnaires.add(present(FhirViews.extensionValueShown(sub)));
                case "reason" -> {
                    Map.Entry<String, TrackedJson.Node> value = FhirViews.extensionValue(sub);
                    if (value != null && OnyxCrdConventions.coverageInactive(FhirViews.conceptCodes(value.getValue()))) {
                        coverageInactive = true;
                    }
                    reasons.add(present(FhirViews.extensionValueShown(sub)));
                }
                case CrdCodes.DETAIL -> {
                    Shown code = Shown.absent();
                    Shown value = Shown.absent();
                    Shown qualifier = Shown.absent();
                    List<String> codeKeys = new ArrayList<>();
                    String plainValue = null;
                    for (TrackedJson.Node part : sub.children("extension")) {
                        if (!part.isObject()) {
                            continue;
                        }
                        String partUrl = part.presentText("url");
                        Map.Entry<String, TrackedJson.Node> partValue = FhirViews.extensionValue(part);
                        if ("code".equals(partUrl)) {
                            if (partValue != null) {
                                codeKeys.addAll(FhirViews.conceptCodes(partValue.getValue()));
                                codeKeys.add(plain(partValue.getValue().raw()));
                            }
                            code = present(FhirViews.extensionValueShown(part));
                        } else if ("value".equals(partUrl)) {
                            plainValue = partValue == null ? null : plain(partValue.getValue().raw());
                            value = present(FhirViews.extensionValueShown(part));
                        } else if ("qualifier".equals(partUrl)) {
                            qualifier = present(FhirViews.extensionValueShown(part));
                        } else {
                            other.add(new SubExtensionView("detail." + Texts.orElse(partUrl, "(no url)"), FhirViews.extensionValueShown(part)));
                        }
                    }
                    details.add(new CoverageDetailView(code, value, qualifier));
                    if (vendor == null) {
                        OnyxCrdConventions.VendorMatch match = OnyxCrdConventions.delegatedVendor(codeKeys, plainValue);
                        if (match != null) {
                            vendor = match.vendor();
                            vendorBasis = match.basis();
                        }
                    }
                }
                default -> other.add(new SubExtensionView(url, FhirViews.extensionValueShown(sub)));
            }
        }

        CrdOutcome outcome = CrdOutcomeRules.forExtension(covered, paNeeded, docNeeded, infoNeeded, coverageInactive);
        return new CoverageInformationView(foundAt, appliesTo, outcome, covered, paNeeded, docNeeded, docPurpose, infoNeeded,
                satisfiedPaId, coverageRef, assertionId, date, questionnaires, reasons, details, vendor, vendorBasis, other);
    }

    /**
     * The code of a sub-extension that is present. When the sub-extension has no value at all the
     * answer is EMPTY, not ABSENT: the element was sent, it just says nothing, and that difference
     * is what a tester chasing a contract mismatch needs to see.
     */
    private static Shown presentCode(TrackedJson.Node sub) {
        return present(FhirViews.extensionCode(sub));
    }

    private static Shown present(Shown value) {
        return value.presence() == Presence.ABSENT ? Shown.empty() : value;
    }

    /** The plain text of a value for matching: a scalar as is, a concept's text or first display, a coding's display or code. */
    private static String plain(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isContainer()) {
            return JsonSafe.presentText(value);
        }
        String text = JsonSafe.presentText(value, "text");
        if (text != null) {
            return text;
        }
        for (JsonNode coding : JsonSafe.elements(value, "coding")) {
            String display = Texts.firstPresent(JsonSafe.presentText(coding, "display"), JsonSafe.presentText(coding, "code"));
            if (display != null) {
                return display;
            }
        }
        return Texts.firstPresent(JsonSafe.presentText(value, "display"), JsonSafe.presentText(value, "code"));
    }
}
