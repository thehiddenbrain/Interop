package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.FhirViews;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.ActionView;
import org.p32h.interop.epa.pave.model.crd.CardLinkView;
import org.p32h.interop.epa.pave.model.crd.CardView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageDetailView;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.model.crd.CrdResponseKind;
import org.p32h.interop.epa.pave.model.crd.SubExtensionView;
import org.p32h.interop.epa.pave.model.crd.SuggestionView;
import java.util.ArrayList;
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
 *
 * <p>A response answers orders, and the tester asked about procedure codes. Each answer is tied to
 * the requested codes it is for, from what the request carried, and a requested code that no
 * answer is tied to is reported as unanswered. Nothing is tied by assumption: see {@link #attribute}.
 */
public final class CrdResponseMapper {

    /** Marks a sub-extension sent again where CRD 2.0.1 allows it once. The checker looks for it. */
    public static final String REPEATED = " (repeated)";

    /** The sub-extensions CRD 2.0.1 allows at most once on one coverage information extension. */
    static final Set<String> SINGLE = Set.of(CrdCodes.COVERAGE, CrdCodes.COVERED, CrdCodes.PA_NEEDED, CrdCodes.DOC_NEEDED,
            CrdCodes.DATE, CrdCodes.COVERAGE_ASSERTION_ID, CrdCodes.SATISFIED_PA_ID);

    /** The CRD 2.0.1 detail code that says authorization is needed only out of network. */
    private static final String AUTH_OUT_NETWORK_ONLY = "auth-out-network-only";

    /** One coverage information extension as found, before it is tied to a requested code. */
    private static final class Found {
        private final CoverageInformationView view;
        /** The codes on the resource the extension sat on, when the response echoed them. */
        private final List<String> echoedCodes;

        private Found(CoverageInformationView view, List<String> echoedCodes) {
            this.view = view;
            this.echoedCodes = echoedCodes;
        }
    }

    /** Reads a response to a request whose orders are not known. No code is then said to be unanswered. */
    public CrdReading read(VendorExchange exchange) {
        return read(exchange, List.of());
    }

    /**
     * @param sentOrders the orders the request carried, in the order sent. Empty when they are not known
     */
    public CrdReading read(VendorExchange received, List<SentOrder> sentOrders) {
        // What the view copies out of the body must not show a secret the request carried, any more than the wire view does.
        VendorExchange exchange = received.withReturnedSecretsMasked();
        List<SentOrder> orders = sentOrders == null ? List.of() : List.copyOf(sentOrders);
        List<CardView> cards = new ArrayList<>();
        List<ActionView> systemActions = new ArrayList<>();
        List<Found> found = new ArrayList<>();
        List<IssueView> issues = List.of();
        // The body as received when it could not be parsed, and anything worth knowing about the
        // body as a whole, come first so they are not lost below a long list.
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
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
                note = ("The body could not be read as JSON. It is shown under the fields that were returned but not interpreted, "
                        + "and in the wire view, as received except that a place repeating a secret the request carried is masked. "
                        + Texts.orElse(exchange.parseError(), "")).strip();
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
                TrackedJson.Node cardsNode = root.child("cards");
                cardsPresence = cardsNode.presence();
                statedByTheView(cardsNode);
                int index = 0;
                for (TrackedJson.Node card : cardsNode.elements()) {
                    if (card.isObject()) {
                        cards.add(card(card, index, found));
                    }
                    index++;
                }
                TrackedJson.Node actionsNode = root.child("systemActions");
                actionsPresence = actionsNode.presence();
                statedByTheView(actionsNode);
                index = 0;
                for (TrackedJson.Node action : actionsNode.elements()) {
                    if (action.isObject()) {
                        systemActions.add(action(action, "systemActions[" + index + "]", found));
                    }
                    index++;
                }
            } else {
                kind = CrdResponseKind.OTHER_JSON;
                note = "The body is JSON but is not an object, so none of it could be interpreted. All of it is listed below.";
            }
            additional.addAll(tracked.unconsumed());
        }

        List<CoverageInformationView> coverage = new ArrayList<>();
        boolean[] answered = new boolean[orders.size()];
        // An order carries one answer for each coverage it is checked against. When the response
        // names more than one coverage, each answer is named by its coverage as well, so two
        // coverages answering differently are never read as two codes answering differently.
        boolean severalCoverages = found.stream().map(one -> one.view.coverage().orNull()).filter(Texts::present).distinct().count() > 1;
        for (Found one : found) {
            List<String> codes = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int index : attribute(one, orders)) {
                answered[index] = true;
                SentOrder order = orders.get(index);
                if (Texts.present(order.code()) && !codes.contains(order.code())) {
                    codes.add(order.code());
                }
                if (!labels.contains(order.label())) {
                    labels.add(order.label());
                }
            }
            String subject = labels.isEmpty() ? Texts.orElse(one.view.appliesTo().orNull(), one.view.foundAt())
                    : String.join(", ", labels);
            if (severalCoverages) {
                subject += " under " + Texts.orElse(one.view.coverage().orNull(), "no named coverage");
            }
            coverage.add(one.view.attributedTo(codes, subject));
        }
        // Only a response that answered something can leave an order unanswered. One that carries
        // no coverage information at all, or is a fault, has its own outcome for the whole call.
        List<String> unanswered = new ArrayList<>();
        if (!coverage.isEmpty()) {
            for (int i = 0; i < orders.size(); i++) {
                if (!answered[i]) {
                    unanswered.add(orders.get(i).label());
                }
            }
        }

        Set<String> vendors = new LinkedHashSet<>();
        List<CrdOutcome> outcomes = new ArrayList<>();
        for (CoverageInformationView view : coverage) {
            outcomes.add(view.outcome());
            if (Texts.present(view.delegatedVendor())) {
                vendors.add(view.delegatedVendor());
            }
        }
        List<String> requested = new ArrayList<>();
        for (SentOrder order : orders) {
            if (Texts.present(order.code()) && !requested.contains(order.code())) {
                requested.add(order.code());
            }
        }
        CrdDetail detail = new CrdDetail(kind, note, cardsPresence, cards, actionsPresence, systemActions, coverage, issues,
                List.of(), true, CrdConformanceChecker.SCOPE);
        return new CrdReading(detail, CrdOutcomeRules.overall(exchange, outcomes, unanswered.size()), requested, unanswered,
                vendors.isEmpty() ? null : String.join("; ", vendors), additional);
    }

    /**
     * Which of the orders sent an answer is for, as positions in the list. Three things can say so,
     * tried in this order, and nothing else is taken as evidence:
     * <ol>
     *   <li>the answer sits on a resource whose reference is the reference of an order sent</li>
     *   <li>that resource carries a code that is the code of an order sent, which covers a service
     *       that gives resources its own ids</li>
     *   <li>the answer sits on no resource at all and exactly one order was sent, so there is
     *       nothing else it could be about</li>
     * </ol>
     * An answer on a resource that names some other order or some other code is for none of them.
     * It is still shown, under the name of the resource it applies to.
     */
    private static List<Integer> attribute(Found found, List<SentOrder> orders) {
        List<Integer> out = new ArrayList<>();
        if (orders.isEmpty()) {
            return out;
        }
        String appliesTo = found.view.appliesTo().orNull();
        if (Texts.present(appliesTo)) {
            for (int i = 0; i < orders.size(); i++) {
                if (sameResource(appliesTo, orders.get(i).reference())) {
                    out.add(i);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        if (!found.echoedCodes.isEmpty()) {
            for (int i = 0; i < orders.size(); i++) {
                String code = orders.get(i).code();
                if (code != null && found.echoedCodes.stream().anyMatch(code::equalsIgnoreCase)) {
                    out.add(i);
                }
            }
            return out;
        }
        if (Texts.blank(appliesTo) && orders.size() == 1) {
            out.add(0);
        }
        return out;
    }

    /** True when two references name one resource. One written without its type matches on the id alone. */
    private static boolean sameResource(String one, String other) {
        if (Texts.blank(one) || Texts.blank(other)) {
            return false;
        }
        if (one.equals(other)) {
            return true;
        }
        boolean oneTyped = one.contains("/");
        boolean otherTyped = other.contains("/");
        if (oneTyped == otherTyped) {
            return false;
        }
        String typed = oneTyped ? one : other;
        String bare = oneTyped ? other : one;
        return typed.substring(typed.lastIndexOf('/') + 1).equals(bare);
    }

    /**
     * The view says for itself whether these two arrays were absent, null or empty, so a null or an
     * empty one is not listed a second time among the fields nobody interpreted. Anything else in
     * that place, such as a string where the array should be, is left to be listed.
     */
    private static void statedByTheView(TrackedJson.Node node) {
        if (node.raw().isNull() || (node.isArray() && node.raw().isEmpty())) {
            node.consume();
        }
    }

    private CardView card(TrackedJson.Node card, int index, List<Found> coverage) {
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

    private ActionView action(TrackedJson.Node action, String path, List<Found> coverage) {
        TrackedJson.Node resource = action.child("resource");
        Shown resourceType = Shown.absent();
        Shown resourceLogicalId = Shown.absent();
        List<AdditionalField> rest = List.of();
        if (resource.isObject()) {
            resourceExtensions(resource, path + ".resource", coverage);
            resourceType = resource.shown("resourceType");
            resourceLogicalId = resource.shown("id");
            // Whatever else the resource holds, such as an echoed order, is shown with the action
            // and so does not crowd the list of fields nobody anticipated.
            rest = resource.remainder();
        }
        return new ActionView(path, action.shown("type"), action.shown("description"), resourceType, action.shown("resourceId"),
                resourceLogicalId, rest);
    }

    /** Coverage information on a FHIR resource: its extension array, each entry carrying the extension url. */
    private void resourceExtensions(TrackedJson.Node resource, String path, List<Found> coverage) {
        String type = JsonSafe.presentText(resource.raw(), "resourceType");
        String id = JsonSafe.presentText(resource.raw(), "id");
        // A resource with no id cannot be referred to, so it names nothing an answer could apply to.
        Shown appliesTo = id == null ? Shown.absent() : Shown.of(type == null ? id : type + "/" + id);
        extensionArray(resource.child("extension"), path + ".extension", appliesTo, SentOrder.codes(resource.raw()), coverage);
    }

    private void extensionArray(TrackedJson.Node array, String path, Shown appliesTo, List<String> echoedCodes, List<Found> coverage) {
        int index = 0;
        for (TrackedJson.Node extension : array.elements()) {
            if (isCoverageInformation(extension)) {
                extension.text("url");
                coverage.add(new Found(coverageInformation(extension.children("extension"), path + "[" + index + "]", appliesTo),
                        echoedCodes));
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
    private void cardExtension(TrackedJson.Node extension, String path, List<Found> coverage) {
        if (extension.isArray()) {
            extensionArray(extension, path, Shown.absent(), List.of(), coverage);
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
                coverage.add(new Found(coverageInformation(value.children("extension"), propertyPath, Shown.absent()), List.of()));
            } else if (value.isArray()) {
                boolean anyTyped = value.elements().stream().anyMatch(CrdResponseMapper::isCoverageInformation);
                if (anyTyped) {
                    extensionArray(value, propertyPath, Shown.absent(), List.of(), coverage);
                } else if (namesCoverageInformation(property.getKey()) && looksLikeSubExtensions(value)) {
                    coverage.add(new Found(coverageInformation(value.elements(), propertyPath, Shown.absent()), List.of()));
                }
            } else if (value.isObject() && namesCoverageInformation(property.getKey()) && value.child("extension").isArray()) {
                coverage.add(new Found(coverageInformation(value.children("extension"), propertyPath + ".extension", Shown.absent()),
                        List.of()));
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
        List<CodedValue> docPurpose = new ArrayList<>();
        List<CodedValue> infoNeeded = new ArrayList<>();
        Shown satisfiedPaId = Shown.absent();
        Shown coverageRef = Shown.absent();
        Shown assertionId = Shown.absent();
        Shown date = Shown.absent();
        List<Shown> questionnaires = new ArrayList<>();
        List<Shown> responses = new ArrayList<>();
        List<Shown> reasons = new ArrayList<>();
        List<String> laterReasons = new ArrayList<>();
        List<CoverageDetailView> details = new ArrayList<>();
        List<SubExtensionView> other = new ArrayList<>();
        // The sub-extensions CRD 2.0.1 allows at most once. A second one is never allowed to replace
        // the first in silence: it is listed beside the others as repeated, and the checker reports it.
        Set<String> seenOnce = new LinkedHashSet<>();
        boolean coverageInactive = false;
        Boolean authOutOfNetworkOnly = null;
        String vendor = null;
        String vendorBasis = null;

        for (TrackedJson.Node sub : subExtensions) {
            if (!sub.isObject()) {
                continue;
            }
            String url = sub.presentText("url");
            if (url == null) {
                other.add(new SubExtensionView("(no url)", FhirViews.extensionValueShown(sub), CrdCodes.definedIn("")));
                continue;
            }
            if (SINGLE.contains(url) && !seenOnce.add(url)) {
                other.add(new SubExtensionView(url + REPEATED, FhirViews.extensionValueShown(sub), CrdCodes.definedIn(url)));
                continue;
            }
            switch (url) {
                case CrdCodes.COVERED -> covered = CrdCodes.coded(url, presentCode(sub));
                case CrdCodes.PA_NEEDED -> paNeeded = CrdCodes.coded(url, presentCode(sub));
                case CrdCodes.DOC_NEEDED -> docNeeded = CrdCodes.coded(url, presentCode(sub));
                case CrdCodes.DOC_PURPOSE -> docPurpose.add(CrdCodes.coded(url, presentCode(sub)));
                case CrdCodes.INFO_NEEDED -> infoNeeded.add(CrdCodes.coded(url, presentCode(sub)));
                case CrdCodes.SATISFIED_PA_ID -> satisfiedPaId = present(FhirViews.extensionValueShown(sub));
                case CrdCodes.COVERAGE -> coverageRef = present(FhirViews.extensionValueShown(sub));
                case CrdCodes.COVERAGE_ASSERTION_ID -> assertionId = present(FhirViews.extensionValueShown(sub));
                case CrdCodes.DATE -> date = present(FhirViews.extensionValueShown(sub));
                case CrdCodes.QUESTIONNAIRE -> questionnaires.add(present(FhirViews.extensionValueShown(sub)));
                case CrdCodes.RESPONSE -> responses.add(present(FhirViews.extensionValueShown(sub)));
                case CrdCodes.REASON -> {
                    Map.Entry<String, TrackedJson.Node> value = FhirViews.extensionValue(sub);
                    if (value != null) {
                        if (OnyxCrdConventions.coverageInactive(FhirViews.conceptCodes(value.getValue()))) {
                            coverageInactive = true;
                        }
                        laterReasons.addAll(OnyxCrdConventions.laterVersionReasons(value.getValue().raw()));
                    }
                    reasons.add(present(FhirViews.extensionValueShown(sub)));
                }
                case CrdCodes.DETAIL -> {
                    Shown code = Shown.absent();
                    Shown value = Shown.absent();
                    Shown qualification = Shown.absent();
                    Shown category = Shown.absent();
                    List<String> codeKeys = new ArrayList<>();
                    String plainValue = null;
                    Boolean booleanValue = null;
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
                            if (partValue != null && partValue.getValue().raw().isBoolean()) {
                                booleanValue = partValue.getValue().raw().asBoolean();
                            }
                            value = present(FhirViews.extensionValueShown(part));
                        } else if ("qualification".equals(partUrl)) {
                            qualification = present(FhirViews.extensionValueShown(part));
                        } else if ("category".equals(partUrl)) {
                            category = present(FhirViews.extensionValueShown(part));
                        } else {
                            String name = "detail." + Texts.orElse(partUrl, "(no url)");
                            other.add(new SubExtensionView(name, FhirViews.extensionValueShown(part), CrdCodes.definedIn(name)));
                        }
                    }
                    details.add(new CoverageDetailView(code, value, qualification, category));
                    if (codeKeys.contains(AUTH_OUT_NETWORK_ONLY) && booleanValue != null) {
                        authOutOfNetworkOnly = booleanValue;
                    }
                    if (vendor == null) {
                        OnyxCrdConventions.VendorMatch match = OnyxCrdConventions.delegatedVendor(codeKeys, plainValue);
                        if (match != null) {
                            vendor = match.vendor();
                            vendorBasis = match.basis();
                        }
                    }
                }
                default -> other.add(new SubExtensionView(url, FhirViews.extensionValueShown(sub), CrdCodes.definedIn(url)));
            }
        }

        boolean memberNotFound = laterReasons.contains(OnyxCrdConventions.LATER_NO_MEMBER_FOUND);
        coverageInactive |= laterReasons.contains(OnyxCrdConventions.LATER_NO_ACTIVE_COVERAGE);
        CrdOutcome outcome = CrdOutcomeRules.forExtension(covered, paNeeded, docNeeded, infoNeeded, coverageInactive, memberNotFound);
        // Which requested codes the answer is for is worked out afterwards, once every answer is known.
        return new CoverageInformationView(foundAt, appliesTo, List.of(), null, outcome, covered, paNeeded, docNeeded, docPurpose,
                infoNeeded, satisfiedPaId, coverageRef, assertionId, date, questionnaires, responses, reasons, laterReasons, details,
                authOutOfNetworkOnly, vendor, vendorBasis, other);
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
