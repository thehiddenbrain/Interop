package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.FhirViews;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.AdjudicationView;
import org.p32h.interop.epa.pave.model.pas.AttachmentRefView;
import org.p32h.interop.epa.pave.model.pas.BundleResourceView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.DecisionSource;
import org.p32h.interop.epa.pave.model.pas.ExtensionView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasErrorView;
import org.p32h.interop.epa.pave.model.pas.PasItemView;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;
import org.p32h.interop.epa.pave.model.pas.ProcessNoteView;
import org.p32h.interop.epa.pave.model.pas.ReviewActionView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads the response to a PAS submission or inquiry into the designed view, the decision codes and
 * an outcome.
 *
 * <p>The response is read through {@link TrackedJson}. Every field placed in the view is consumed
 * by the read that placed it, and whatever is left is returned as additional fields, so a field
 * this mapper was never told about appears on screen without anyone having to add it here.
 *
 * <p>The same reading is applied to a success, a 400, a 500 and an OperationOutcome. A fault
 * response is not reduced to its status.
 *
 * <p>A ClaimResponse answers items by their sequence, and the tester asked about procedure codes.
 * Each decision is tied to the requested item it is for, from what the request carried, and a
 * requested item that no review action applies to is on the result as undecided. See
 * {@link #decisions} for exactly what ties a decision to an item.
 *
 * <p>The receiver uses {@link #readBundle} for the resources a notification carries, so a decision
 * that arrives in a notification is read by the same rules as one that arrives in a response.
 */
public final class PasResponseMapper {

    /**
     * What a bundle, or a single resource, carried.
     *
     * @param issues the issues of every OperationOutcome it carried
     */
    public record Carried(Shown bundleType, Shown bundleId, Shown bundleTimestamp, List<ClaimResponseView> claimResponses,
                          List<BundleResourceView> otherResources, List<IssueView> issues) {

        static Carried nothing() {
            return new Carried(Shown.absent(), Shown.absent(), Shown.absent(), List.of(), List.of(), List.of());
        }
    }

    /** One review action as read, with every code it carried. The view shows the first; none is dropped from the decision. */
    private record Action(ReviewActionView view, List<CodedValue> codes) {
    }

    /**
     * One response item as read, before it is tied to a requested item.
     *
     * @param authorizedFrom the start of the item's itemPreAuthPeriod, or null
     * @param authorizedTo   the end of it, or null
     */
    private record ItemRead(PasItemView view, Integer sequence, List<String> traceNumbers, List<Action> actions, String authorizedFrom,
                            String authorizedTo, Presence authorizedPeriod) {
    }

    /** Where PAS 2.0.1 does not place a review action, said beside one found there. */
    static final String ON_THE_RESPONSE = "This review action sat on the ClaimResponse itself. PAS 2.0.1 places a review action on "
            + "ClaimResponse.item.adjudication only; it is read here all the same.";
    static final String ON_THE_ITEM = "This review action sat on the item itself. PAS 2.0.1 places a review action on the item's "
            + "adjudication only; it is read here all the same.";

    /** One error as read: the requested item it names, when it names one, and its codes. */
    private record ErrorRead(Integer itemSequence, List<String> codes) {
    }

    /** A resource a reference names, found in the same response. */
    private record Resolved(TrackedJson.Node resource, TrackedJson.Node holder, int entryIndex) {
    }

    /** Reads a response to a request whose claim is not known. No item is then said to be undecided. */
    public PasReading read(VendorExchange exchange) {
        return read(exchange, SentClaim.unknown());
    }

    /**
     * @param sentClaim the claim the request carried, with its items in the order sent
     */
    public PasReading read(VendorExchange exchange, SentClaim sentClaim) {
        return read(exchange, sentClaim, false);
    }

    /**
     * @param sentClaim the claim the request carried, with its items in the order sent
     * @param inquiry   true for the answer to an inquiry, which PAS 2.0.1 makes a bundle of zero to
     *                  many ClaimResponses, so that an empty one means that nothing matched
     */
    public PasReading read(VendorExchange received, SentClaim sentClaim, boolean inquiry) {
        // What the view copies out of the body must not show a secret the request carried, any more than the wire view does.
        VendorExchange exchange = received.withReturnedSecretsMasked();
        SentClaim sent = sentClaim == null ? SentClaim.unknown() : sentClaim;
        // The body as received when it could not be parsed, and anything worth knowing about the
        // body as a whole, come first so they are not lost below a long list.
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
        Carried carried = Carried.nothing();
        PasResponseKind kind;
        String note = null;

        JsonNode json = exchange.json();
        if (json == null) {
            if (exchange.httpStatus() == null) {
                kind = PasResponseKind.NO_RESPONSE;
                note = "No response arrived.";
            } else if (exchange.responseBody() == null) {
                kind = PasResponseKind.NO_BODY;
                note = "The response has no body.";
            } else {
                kind = PasResponseKind.NOT_JSON;
                note = ("The body could not be read as JSON. It is shown as received, but for any place that repeats a secret the request "
                        + "carried, under the fields that were returned "
                        + "but not interpreted, and in the wire view. " + Texts.orElse(exchange.parseError(), "")).strip();
            }
        } else {
            TrackedJson tracked = TrackedJson.of(json);
            TrackedJson.Node root = tracked.root();
            String type = JsonSafe.text(json, "resourceType");
            if ("Bundle".equals(type)) {
                kind = PasResponseKind.BUNDLE;
                carried = readBundle(root, sent, Set.of());
                if (carried.claimResponses().isEmpty() && carried.issues().isEmpty()) {
                    note = inquiry ? "The bundle carries no ClaimResponse. PAS 2.0.1 answers an inquiry with one ClaimResponse for each "
                            + "prior authorization that matches, so none means that none matched."
                            : "The bundle carries no ClaimResponse, so it holds no decision.";
                }
            } else if ("ClaimResponse".equals(type)) {
                kind = PasResponseKind.CLAIM_RESPONSE;
                carried = new Carried(Shown.absent(), Shown.absent(), Shown.absent(),
                        List.of(claimResponse(root, Shown.absent(), sent, null, List.of(), new HashSet<>())), List.of(), List.of());
            } else if (OperationOutcomes.is(json)) {
                kind = PasResponseKind.OPERATION_OUTCOME;
                root.text("resourceType");
                carried = new Carried(Shown.absent(), Shown.absent(), Shown.absent(), List.of(), List.of(), OperationOutcomes.issues(root));
            } else {
                kind = PasResponseKind.OTHER_JSON;
                note = root.isObject()
                        ? "The body is JSON but is not a bundle, a ClaimResponse or an OperationOutcome, so none of it could be "
                        + "interpreted. All of it is listed below."
                        : "The body is JSON but is not an object, so none of it could be interpreted. All of it is listed below.";
            }
            additional.addAll(tracked.unconsumed());
        }

        PasDetail detail = new PasDetail(kind, note, carried.bundleType(), carried.bundleId(), carried.bundleTimestamp(),
                carried.claimResponses(), carried.otherResources(), carried.issues(), null, null, List.of(), true);
        boolean emptyInquiryAnswer = inquiry && kind == PasResponseKind.BUNDLE && carried.claimResponses().isEmpty()
                && carried.issues().isEmpty();
        PasOutcome outcome = PasOutcomeRules.overall(exchange, OnyxPasConventions.authorizationNotFound(exchange),
                carried.claimResponses().stream().map(ClaimResponseView::decision).toList(), emptyInquiryAnswer);
        return new PasReading(detail, outcome, sent, additional);
    }

    /**
     * Reads the resources of a bundle: every ClaimResponse into its view, every OperationOutcome
     * issue by issue, and every other resource as itself, listed with all it holds.
     *
     * <p>A bundle carried as an entry, such as the PAS response bundle a full-resource notification
     * of the 2.2.0 ballot carries, is read the same way, and what it holds is listed with the rest.
     * Its own type, id and timestamp are listed as the fields of that entry.
     *
     * <p>Items are tied to the requested items only when the bundle holds exactly one
     * ClaimResponse. With several, which of them answers the request that was sent cannot be told
     * from their item sequences, so nothing is tied and each decision is named by its ClaimResponse.
     *
     * @param sent        the claim the request carried
     * @param alreadyRead positions of entries the caller has read itself, which are left alone
     */
    public Carried readBundle(TrackedJson.Node bundle, SentClaim sent, Set<Integer> alreadyRead) {
        bundle.text("resourceType");
        Shown type = bundle.shown("type");
        Shown id = bundle.shown("id");
        Shown timestamp = bundle.shown("timestamp");
        List<TrackedJson.Node> entries = bundle.children("entry");
        Set<Integer> used = new HashSet<>(alreadyRead);

        List<Integer> withClaimResponse = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!used.contains(i) && "ClaimResponse".equals(JsonSafe.text(entries.get(i).child("resource").raw(), "resourceType"))) {
                withClaimResponse.add(i);
            }
        }
        boolean one = withClaimResponse.size() == 1;
        used.addAll(withClaimResponse);
        List<ClaimResponseView> claimResponses = new ArrayList<>();
        for (int index : withClaimResponse) {
            TrackedJson.Node entry = entries.get(index);
            TrackedJson.Node resource = entry.child("resource");
            String resourceId = JsonSafe.presentText(resource.raw(), "id");
            String name = one ? null : resourceId == null ? resource.path() : "ClaimResponse/" + resourceId;
            claimResponses.add(claimResponse(resource, entry.shown("fullUrl"), one ? sent : SentClaim.unknown(), name, entries, used));
        }

        List<IssueView> issues = new ArrayList<>();
        List<BundleResourceView> others = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            TrackedJson.Node entry = entries.get(i);
            TrackedJson.Node resource = entry.child("resource");
            if (used.contains(i) || !resource.isObject() || !"Bundle".equals(JsonSafe.text(resource.raw(), "resourceType"))) {
                continue;
            }
            used.add(i);
            Carried inner = readBundle(resource, SentClaim.unknown(), Set.of());
            claimResponses.addAll(inner.claimResponses());
            others.addAll(inner.otherResources());
            issues.addAll(inner.issues());
            List<AdditionalField> fields = new ArrayList<>();
            listed(fields, resource.path() + ".type", inner.bundleType());
            listed(fields, resource.path() + ".timestamp", inner.bundleTimestamp());
            fields.addAll(entry.remainder());
            others.add(new BundleResourceView(resource.path(), Shown.of("Bundle"), inner.bundleId(), entry.shown("fullUrl"), fields));
        }
        for (int i = 0; i < entries.size(); i++) {
            TrackedJson.Node entry = entries.get(i);
            TrackedJson.Node resource = entry.child("resource");
            if (used.contains(i) || !resource.isObject()) {
                continue;
            }
            if (OperationOutcomes.is(resource.raw())) {
                resource.text("resourceType");
                issues.addAll(OperationOutcomes.issues(resource));
            } else {
                // Everything else the entry holds is listed with the resource it belongs to, and so
                // does not crowd the list of fields nobody anticipated.
                others.add(new BundleResourceView(resource.path(), resource.shown("resourceType"), resource.shown("id"),
                        entry.shown("fullUrl"), entry.remainder()));
            }
        }
        return new Carried(type, id, timestamp, claimResponses, others, issues);
    }

    /** Adds a value that was read into a Shown to a list of fields, unless it was absent. */
    private static void listed(List<AdditionalField> fields, String path, Shown value) {
        if (value.presence() != Presence.ABSENT) {
            fields.add(new AdditionalField(path, value.presence(), value.orNull()));
        }
    }

    /**
     * @param name    how the decisions of this ClaimResponse are prefixed when the result holds
     *                several. Null when it is the only one
     * @param entries the entries of the bundle it came in, for resolving what it refers to
     * @param used    positions of entries already read, added to when a reference is resolved
     */
    private ClaimResponseView claimResponse(TrackedJson.Node resource, Shown fullUrl, SentClaim sent, String name,
                                            List<TrackedJson.Node> entries, Set<Integer> used) {
        resource.text("resourceType");
        String foundAt = resource.path();
        CodedValue outcome = PasCodes.coded(PasCodes.CLAIM_OUTCOME, resource.shown("outcome"));

        TrackedJson.Node period = resource.child("preAuthPeriod");
        Shown periodStart = period.isObject() ? period.shown("start") : period.shown();
        Shown periodEnd = period.isObject() ? period.shown("end") : Shown.absent();

        TrackedJson.Node request = resource.child("request");
        Shown requestIdentifier = request.isObject() && request.child("identifier").exists() ? identifier(request.child("identifier"))
                : Shown.absent();
        // A request that is named by its identifier alone has no reference to show. That is absent,
        // not empty: the identifier beside it is what the response gave.
        boolean identifierOnly = request.isObject() && !request.child("reference").exists() && !request.child("display").exists();
        Shown requestShown = identifierOnly ? Shown.absent() : FhirViews.reference(request);

        List<Shown> identifiers = new ArrayList<>();
        for (TrackedJson.Node identifier : resource.children("identifier")) {
            identifiers.add(identifier(identifier));
        }

        List<Action> responseActions = new ArrayList<>();
        List<ExtensionView> extensions = new ArrayList<>();
        extensions(resource, responseActions, extensions, ON_THE_RESPONSE);

        List<ItemRead> items = new ArrayList<>();
        for (TrackedJson.Node item : resource.children("item")) {
            if (item.isObject()) {
                items.add(item(item));
            }
        }

        List<PasErrorView> errors = new ArrayList<>();
        List<ErrorRead> errorReads = new ArrayList<>();
        for (TrackedJson.Node error : resource.children("error")) {
            if (error.isObject()) {
                PasErrorView view = error(error);
                errors.add(view);
                errorReads.add(new ErrorRead(JsonSafe.integer(error.raw(), "itemSequence"), view.codes()));
            }
        }

        List<AttachmentRefView> attachments = new ArrayList<>();
        for (TrackedJson.Node reference : resource.children("communicationRequest")) {
            attachments.add(attachment(reference, resource, entries, used));
        }

        List<ProcessNoteView> notes = new ArrayList<>();
        for (TrackedJson.Node note : resource.children("processNote")) {
            if (note.isObject()) {
                notes.add(new ProcessNoteView(note.shown("number"), note.shown("type"), note.shown("text")));
            }
        }

        List<PasItemView> itemViews = new ArrayList<>();
        List<PasDecision> decisions = decisions(items, responseActions, errorReads, sent, name, itemViews);
        boolean errorsReported = !errors.isEmpty() || outcome.is("error");
        boolean documentationWanted = attachments.stream().anyMatch(AttachmentRefView::open);
        PasOutcome decision = PasOutcomeRules.forClaimResponse(decisions.stream().map(PasDecision::outcome).toList(), errorsReported,
                documentationWanted);

        return new ClaimResponseView(foundAt, fullUrl, resource.shown("id"), resource.shown("status"),
                FhirViews.concept(resource.child("type")), resource.shown("use"), FhirViews.reference(resource.child("patient")),
                resource.shown("created"), FhirViews.reference(resource.child("insurer")), FhirViews.reference(resource.child("requestor")),
                outcome, resource.shown("disposition"), resource.shown("preAuthRef"), periodStart, periodEnd, requestShown,
                requestIdentifier, identifiers, responseActions.stream().map(Action::view).toList(), decision, decisions, itemViews,
                errors, attachments, notes, extensions, subsetted(resource.raw()), addItemReviewActions(resource), period.presence(),
                resource.presences("identifier", "item", "error", "processNote", "communicationRequest", "extension"));
    }

    /**
     * Where review actions sit on ClaimResponse.addItem.adjudication, read without consuming, so the
     * added items are still listed as returned. An added item is the payer's own and answers none
     * of the requested items by its sequence, so its review action is not taken as a decision.
     */
    private static List<String> addItemReviewActions(TrackedJson.Node resource) {
        List<String> out = new ArrayList<>();
        List<JsonNode> added = JsonSafe.elements(resource.raw(), "addItem");
        for (int i = 0; i < added.size(); i++) {
            List<JsonNode> adjudications = JsonSafe.elements(added.get(i), "adjudication");
            for (int j = 0; j < adjudications.size(); j++) {
                List<JsonNode> extensions = JsonSafe.elements(adjudications.get(j), "extension");
                for (int k = 0; k < extensions.size(); k++) {
                    if (OnyxPasConventions.reviewAction(JsonSafe.presentText(extensions.get(k), "url"))) {
                        out.add(resource.path() + ".addItem[" + i + "].adjudication[" + j + "].extension[" + k + "]");
                    }
                }
            }
        }
        return out;
    }

    /**
     * True when the resource declares the security tag SUBSETTED. PAS 2.0.1 requires it of a
     * ClaimResponse that leaves out items of a revised request. Read without consuming, so the
     * meta element is still listed with everything else it holds.
     */
    static boolean subsetted(JsonNode resource) {
        for (JsonNode tag : JsonSafe.elements(JsonSafe.child(resource, "meta"), "security")) {
            if ("SUBSETTED".equals(JsonSafe.presentText(tag, "code"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The decision for each thing that was asked about. What ties a decision to a requested item,
     * and nothing else is taken as evidence:
     * <ol>
     *   <li>a response item that echoes the item trace number the requested item was sent with,
     *       which PAS 2.0.1 lets a ClaimResponse item carry and which names the item whatever its
     *       sequence</li>
     *   <li>a response item that echoes no trace number of a requested item, and whose sequence is
     *       the sequence of the requested item</li>
     *   <li>a response item with no sequence, when exactly one item was requested and exactly one
     *       came back, so there is nothing else it could be about</li>
     *   <li>a review action stated for the response as a whole, which is the decision for every
     *       item that states none of its own: a request certified in total certifies each item of it</li>
     * </ol>
     * A requested item that none of these reaches has no decision. A response item that answers no
     * requested item is still listed, under its sequence, and is for none of the requested codes.
     * When the request is not known, each response item is its own decision, and a response with no
     * items holds one decision about the whole request.
     *
     * @param itemViews filled with the item views, each named for the requested item it is tied to
     */
    private static List<PasDecision> decisions(List<ItemRead> items, List<Action> responseActions, List<ErrorRead> errors, SentClaim sent,
                                               String name, List<PasItemView> itemViews) {
        List<PasDecision> out = new ArrayList<>();
        PasItemView[] views = new PasItemView[items.size()];
        if (!sent.items().isEmpty()) {
            boolean[] tied = new boolean[items.size()];
            int[] answers = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                answers[i] = requestedItemAnswered(items.get(i), sent.items(), items.size());
            }
            for (int r = 0; r < sent.items().size(); r++) {
                SentClaim.Item requested = sent.items().get(r);
                List<ItemRead> mine = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    if (answers[i] == r) {
                        mine.add(items.get(i));
                        tied[i] = true;
                        views[i] = items.get(i).view().attributedTo(requested.code(), requested.label());
                    }
                }
                out.add(decision(requested.label(), requested.code(), requested.sequence(), mine, responseActions,
                        errorCodes(errors, requested.sequence(), false)));
            }
            for (int i = 0; i < items.size(); i++) {
                if (!tied[i]) {
                    String subject = untied(items.get(i));
                    views[i] = items.get(i).view().attributedTo(null, subject);
                    out.add(decision(subject, null, items.get(i).sequence(), List.of(items.get(i)), responseActions,
                            errorCodes(errors, items.get(i).sequence(), false)));
                }
            }
        } else if (!items.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                String subject = (name == null ? "" : name + " ") + untied(items.get(i));
                views[i] = items.get(i).view().attributedTo(null, subject);
                out.add(decision(subject, null, items.get(i).sequence(), List.of(items.get(i)), responseActions,
                        errorCodes(errors, items.get(i).sequence(), false)));
            }
        } else {
            out.add(decision(name, null, null, List.of(), responseActions, errorCodes(errors, null, true)));
        }
        itemViews.addAll(Arrays.asList(views));
        return out;
    }

    /**
     * The position of the requested item a response item answers, or -1 for none: the one whose
     * trace number it echoes, else the one with its sequence, else the only one when exactly one
     * item was requested and one came back with no sequence.
     */
    private static int requestedItemAnswered(ItemRead item, List<SentClaim.Item> requested, int responseItems) {
        for (int r = 0; r < requested.size(); r++) {
            String trace = requested.get(r).traceNumber();
            if (Texts.present(trace) && item.traceNumbers().contains(trace.strip())) {
                return r;
            }
        }
        for (int r = 0; r < requested.size(); r++) {
            if (requested.get(r).sequence() != null && requested.get(r).sequence().equals(item.sequence())) {
                return r;
            }
        }
        return item.sequence() == null && requested.size() == 1 && responseItems == 1 ? 0 : -1;
    }

    private static PasDecision decision(String subject, String procedureCode, Integer sequence, List<ItemRead> mine,
                                        List<Action> responseActions, List<String> errorCodes) {
        List<Action> own = mine.stream().flatMap(item -> item.actions().stream()).toList();
        // The authorization number and period PAS 2.0.1 gives each item: the number part of its
        // review action, and its itemPreAuthPeriod extension.
        List<String> numbers = own.stream().map(action -> action.view().number()).filter(Shown::hasValue).map(Shown::value).distinct()
                .toList();
        if (numbers.isEmpty() && own.isEmpty()) {
            numbers = responseActions.stream().map(action -> action.view().number()).filter(Shown::hasValue).map(Shown::value).distinct()
                    .toList();
        }
        String from = mine.stream().map(ItemRead::authorizedFrom).filter(Texts::present).findFirst().orElse(null);
        String to = mine.stream().map(ItemRead::authorizedTo).filter(Texts::present).findFirst().orElse(null);
        // A period that was stated wins over one sent with nothing in it, as its start and end do.
        Presence period = mine.stream().map(ItemRead::authorizedPeriod).filter(p -> p == Presence.VALUE).findFirst()
                .orElse(mine.stream().map(ItemRead::authorizedPeriod).filter(p -> p != null).findFirst().orElse(null));
        List<Action> applies;
        DecisionSource source;
        if (states(own)) {
            applies = own;
            source = DecisionSource.ITEM;
        } else if (states(responseActions)) {
            applies = responseActions;
            source = DecisionSource.RESPONSE;
        } else {
            // Review actions that were sent with no code in them decide nothing. They are kept so
            // the grid can show that the element was there and empty, which is not the same as absent.
            applies = own.isEmpty() ? responseActions : own;
            source = DecisionSource.NONE;
        }
        List<CodedValue> codes = applies.stream().flatMap(action -> action.codes().stream()).distinct().toList();
        List<String> reasons = source == DecisionSource.NONE ? List.of()
                : applies.stream().flatMap(action -> action.view().reasonCodes().stream()).distinct().toList();
        return new PasDecision(subject, procedureCode, sequence, mine.isEmpty() ? null : mine.get(0).view().foundAt(), codes, source,
                PasOutcomeRules.forItem(codes), reasons, errorCodes, numbers, from, to, period);
    }

    /** True when at least one of the review actions carries a code. */
    private static boolean states(List<Action> actions) {
        return actions.stream().flatMap(action -> action.codes().stream()).anyMatch(code -> code.code() != null);
    }

    /** How a response item that answers no requested item is named: by its sequence, else by where it sat. */
    private static String untied(ItemRead item) {
        return item.sequence() == null ? item.view().foundAt() : "item " + item.sequence();
    }

    /**
     * The error codes that bear on one item: those the response reports against no item in
     * particular, and those it reports against this one.
     *
     * @param all true for the decision about the whole request, which every error bears on
     */
    private static List<String> errorCodes(List<ErrorRead> errors, Integer sequence, boolean all) {
        List<String> out = new ArrayList<>();
        for (ErrorRead error : errors) {
            if (all || error.itemSequence() == null || error.itemSequence().equals(sequence)) {
                error.codes().stream().filter(code -> !out.contains(code)).forEach(out::add);
            }
        }
        return out;
    }

    private ItemRead item(TrackedJson.Node item) {
        List<Action> actions = new ArrayList<>();
        List<Action> onItem = new ArrayList<>();
        List<ExtensionView> extensions = new ArrayList<>();
        extensions(item, onItem, extensions, ON_THE_ITEM);
        actions.addAll(onItem);

        List<AdjudicationView> adjudications = new ArrayList<>();
        for (TrackedJson.Node adjudication : item.children("adjudication")) {
            if (!adjudication.isObject()) {
                continue;
            }
            List<Action> onAdjudication = new ArrayList<>();
            List<ExtensionView> adjudicationExtensions = new ArrayList<>();
            extensions(adjudication, onAdjudication, adjudicationExtensions, null);
            actions.addAll(onAdjudication);
            adjudications.add(new AdjudicationView(adjudication.path(), FhirViews.concept(adjudication.child("category")),
                    FhirViews.concept(adjudication.child("reason")), money(adjudication.child("amount")), adjudication.shown("value"),
                    onAdjudication.stream().map(Action::view).toList(), adjudicationExtensions, adjudication.presences("extension")));
        }
        List<Shown> noteNumbers = new ArrayList<>();
        for (TrackedJson.Node number : item.children("noteNumber")) {
            noteNumbers.add(number.shown());
        }
        Integer sequence = JsonSafe.integer(item.raw(), "itemSequence");
        PasItemView view = new PasItemView(item.path(), item.shown("itemSequence"), null, null, onItem.stream().map(Action::view).toList(),
                adjudications, extensions, noteNumbers, item.presences("adjudication", "extension", "noteNumber"));
        // Read without consuming: the extension is listed with the item's others as it came.
        String from = null;
        String to = null;
        Presence period = null;
        for (JsonNode extension : JsonSafe.elements(item.raw(), "extension")) {
            if (OnyxPasConventions.pasExtension(JsonSafe.presentText(extension, "url"), OnyxPasConventions.ITEM_PRE_AUTH_PERIOD)) {
                from = JsonSafe.presentText(JsonSafe.child(extension, "valuePeriod"), "start");
                to = JsonSafe.presentText(JsonSafe.child(extension, "valuePeriod"), "end");
                period = JsonSafe.presence(JsonSafe.child(extension, "valuePeriod"));
            }
        }
        return new ItemRead(view, sequence, SentClaim.traceNumbers(item.raw()), actions, from, to, period);
    }

    /**
     * Sorts the extensions of an element into the review actions among them and everything else.
     *
     * @param placement null where PAS 2.0.1 places a review action, else the sentence a review action found here carries
     */
    private void extensions(TrackedJson.Node element, List<Action> actions, List<ExtensionView> others, String placement) {
        for (TrackedJson.Node extension : element.children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            String url = extension.presentText("url");
            if (OnyxPasConventions.reviewAction(url)) {
                actions.add(reviewAction(extension, placement));
            } else {
                others.add(extension(extension, Texts.orElse(url, "(no url)")));
            }
        }
    }

    private Action reviewAction(TrackedJson.Node extension, String placement) {
        // Sent, and deciding nothing, until a code is found in it.
        CodedValue code = new CodedValue(Presence.EMPTY, null, null, true);
        List<CodedValue> codes = new ArrayList<>();
        Shown returned = Shown.absent();
        Shown number = Shown.absent();
        Shown reason = Shown.absent();
        Shown secondOpinion = Shown.absent();
        List<String> reasonCodes = new ArrayList<>();
        List<ExtensionView> other = new ArrayList<>();

        for (TrackedJson.Node part : extension.children("extension")) {
            if (!part.isObject()) {
                continue;
            }
            String url = part.presentText("url");
            switch (OnyxPasConventions.reviewPart(url)) {
                case CODE -> {
                    CodedValue found = PasCodes.coded(PasCodes.REVIEW_ACTION, present(codeOf(part)));
                    if (codes.isEmpty()) {
                        code = found;
                        returned = present(FhirViews.extensionValueShown(part));
                    } else {
                        // A second code in one review action is shown where it sat and still counts
                        // toward the decision: none of them is chosen over the others.
                        other.add(extension(part, url + " (repeated)"));
                    }
                    codes.add(found);
                }
                case NUMBER -> number = present(FhirViews.extensionValueShown(part));
                case REASON -> {
                    reasonCodes.addAll(valueCodes(part));
                    reason = present(FhirViews.extensionValueShown(part));
                }
                case SECOND_SURGICAL_OPINION -> secondOpinion = present(FhirViews.extensionValueShown(part));
                case OTHER -> other.add(extension(part, Texts.orElse(url, "(no url)")));
            }
        }
        if (codes.isEmpty()) {
            codes.add(code);
            // A review action written with a value of its own in place of parts is not the shape
            // this tool reads a decision from. The value is still shown, so it is not lost.
            returned = FhirViews.extensionValueShown(extension);
        }
        return new Action(new ReviewActionView(extension.path(), code, returned, number, reason, reasonCodes, secondOpinion, other, placement),
                codes);
    }

    private PasErrorView error(TrackedJson.Node error) {
        Shown element = Shown.absent();
        Shown path = Shown.absent();
        Shown followup = Shown.absent();
        List<ExtensionView> other = new ArrayList<>();
        for (TrackedJson.Node extension : error.children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            String url = extension.presentText("url");
            switch (OnyxPasConventions.errorPart(url)) {
                case ELEMENT -> element = present(FhirViews.extensionValueShown(extension));
                case PATH -> path = present(FhirViews.extensionValueShown(extension));
                case FOLLOWUP_ACTION -> followup = present(FhirViews.extensionValueShown(extension));
                case OTHER -> other.add(extension(extension, Texts.orElse(url, "(no url)")));
            }
        }
        TrackedJson.Node code = error.child("code");
        List<String> codes = FhirViews.conceptCodes(code);
        return new PasErrorView(error.path(), error.shown("itemSequence"), error.shown("detailSequence"), error.shown("subDetailSequence"),
                FhirViews.concept(code), codes, element, path, followup, other);
    }

    /**
     * One reference to a request for documentation, with the status of the resource it names when
     * the response carries that resource, in the bundle or contained in the ClaimResponse.
     */
    private AttachmentRefView attachment(TrackedJson.Node reference, TrackedJson.Node claimResponse, List<TrackedJson.Node> entries,
                                         Set<Integer> used) {
        String foundAt = reference.path();
        Resolved resolved = resolve(JsonSafe.presentText(reference.raw(), "reference"), claimResponse, entries, used);
        Shown shown = FhirViews.reference(reference);
        if (resolved == null) {
            return new AttachmentRefView(foundAt, shown, false, Shown.absent(), true, Shown.absent(), List.of(), List.of());
        }
        TrackedJson.Node resource = resolved.resource();
        boolean open = !OnyxPasConventions.documentationRequestClosed(JsonSafe.presentText(resource.raw(), "status"));
        List<String> categories = new ArrayList<>();
        for (TrackedJson.Node category : resource.children("category")) {
            Shown one = FhirViews.concept(category);
            if (one.hasValue()) {
                categories.add(one.value());
            }
        }
        List<Shown> wanted = new ArrayList<>();
        for (TrackedJson.Node payload : resource.children("payload")) {
            if (payload.child("contentString").exists()) {
                wanted.add(payload.shown("contentString"));
            } else if (payload.child("contentReference").exists()) {
                wanted.add(FhirViews.reference(payload.child("contentReference")));
            }
        }
        return new AttachmentRefView(foundAt, shown, true, resource.shown("status"), open,
                categories.isEmpty() ? Shown.absent() : Shown.of(String.join("; ", categories)), wanted, resolved.holder().remainder());
    }

    /**
     * The resource a reference names, when the same response carries it: an entry of the bundle
     * whose type and id, or whose full url, is the reference, or a resource contained in the
     * ClaimResponse for a reference that starts with a hash. Null when it is not carried.
     */
    private static Resolved resolve(String reference, TrackedJson.Node claimResponse, List<TrackedJson.Node> entries, Set<Integer> used) {
        if (Texts.blank(reference)) {
            return null;
        }
        if (reference.startsWith("#")) {
            for (TrackedJson.Node contained : claimResponse.children("contained")) {
                if (reference.substring(1).equals(JsonSafe.presentText(contained.raw(), "id"))) {
                    return new Resolved(contained, contained, -1);
                }
            }
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            TrackedJson.Node entry = entries.get(i);
            TrackedJson.Node resource = entry.child("resource");
            if (used.contains(i) || !resource.isObject()) {
                continue;
            }
            String type = JsonSafe.presentText(resource.raw(), "resourceType");
            String id = JsonSafe.presentText(resource.raw(), "id");
            String local = type != null && id != null ? type + "/" + id : null;
            boolean named = local != null && (reference.equals(local) || reference.endsWith("/" + local));
            if (named || reference.equals(JsonSafe.presentText(entry.raw(), "fullUrl"))) {
                used.add(i);
                return new Resolved(resource, entry, i);
            }
        }
        return null;
    }

    /**
     * One extension as returned, with the extensions nested inside it. The readers of the other
     * resources this package shows use it too, so an extension nobody anticipated is listed the
     * same way wherever it sat.
     */
    static ExtensionView extension(TrackedJson.Node extension, String name) {
        List<ExtensionView> parts = new ArrayList<>();
        for (TrackedJson.Node part : extension.children("extension")) {
            if (part.isObject()) {
                parts.add(extension(part, Texts.orElse(part.presentText("url"), "(no url)")));
            }
        }
        return new ExtensionView(name, FhirViews.extensionValueShown(extension), parts);
    }

    /**
     * The code an extension part carries, read without consuming anything, so the value can then be
     * shown in full. A concept gives the code of its first coding that has one, a coding its code,
     * a plain value its text. The presence says whether the value was absent, null, empty or there.
     */
    private static Shown codeOf(TrackedJson.Node part) {
        Map.Entry<String, TrackedJson.Node> value = FhirViews.extensionValue(part);
        if (value == null) {
            return Shown.absent();
        }
        JsonNode raw = value.getValue().raw();
        if (raw.isNull()) {
            return Shown.explicitNull();
        }
        if (raw.isObject()) {
            List<String> codes = valueCodes(part);
            return codes.isEmpty() ? Shown.empty() : Shown.of(codes.get(0));
        }
        String text = JsonSafe.presentText(raw);
        return text == null ? Shown.empty() : Shown.of(text);
    }

    /** Every code value an extension part carries, in order, without consuming anything. */
    private static List<String> valueCodes(TrackedJson.Node part) {
        List<String> out = new ArrayList<>();
        Map.Entry<String, TrackedJson.Node> value = FhirViews.extensionValue(part);
        if (value == null) {
            return out;
        }
        JsonNode raw = value.getValue().raw();
        if (raw.isObject()) {
            out.addAll(FhirViews.conceptCodes(value.getValue()));
            String direct = JsonSafe.presentText(raw, "code");
            if (out.isEmpty() && direct != null) {
                out.add(direct);
            }
        } else {
            String text = JsonSafe.presentText(raw);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    /** An identifier as "system|value", using whichever parts are present. */
    private static Shown identifier(TrackedJson.Node identifier) {
        if (!identifier.isObject()) {
            return identifier.shown();
        }
        String system = identifier.presentText("system");
        String value = identifier.presentText("value");
        String text = system == null ? value : system + "|" + (value == null ? "" : value);
        return text == null ? Shown.empty() : Shown.of(text);
    }

    /** An amount as its value and currency, using whichever parts are present. */
    private static Shown money(TrackedJson.Node amount) {
        if (!amount.isObject()) {
            return amount.shown();
        }
        String value = amount.presentText("value");
        String currency = amount.presentText("currency");
        String text = value != null && currency != null ? value + " " + currency : Texts.firstPresent(value, currency);
        return text == null ? Shown.empty() : Shown.of(text);
    }

    /**
     * The value of a part that is present. When the part has no value at all the answer is EMPTY,
     * not ABSENT: the part was sent, it just says nothing, and that difference is what a tester
     * chasing a contract mismatch needs to see.
     */
    static Shown present(Shown value) {
        return value.presence() == Presence.ABSENT ? Shown.empty() : value;
    }
}
