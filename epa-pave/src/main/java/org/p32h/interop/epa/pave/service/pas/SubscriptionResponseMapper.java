package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FhirViews;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.BundleResourceView;
import org.p32h.interop.epa.pave.model.pas.ExtensionView;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads the response to a subscription call into the designed view, its codes and an outcome.
 *
 * <p>The response is read through {@link TrackedJson}. Every field placed in the view is consumed
 * by the read that placed it, and whatever is left is returned as additional fields, so a field
 * this mapper was never told about appears on screen without anyone having to add it here. The
 * same reading is applied to a success and to a fault.
 *
 * <p>One thing a subscription can hold is deliberately not shown in full: the headers the service
 * sends with a notification. A header is a credential for the receiver, so the designed view names
 * it and never shows its value.
 */
public final class SubscriptionResponseMapper {

    /** The two codes of the FHIR issue severity value set that say an operation did not succeed. */
    private static final String SEVERITY_ERROR = "error";
    private static final String SEVERITY_FATAL = "fatal";

    /**
     * @param operation the call that was made, which decides what a success means
     * @param askedId   the id of the subscription the call was about, or null for a create and a list
     */
    public SubscriptionReading read(VendorExchange received, EpaOperation operation, String askedId) {
        // What the view copies out of the body must not show a secret the request carried, any more than the wire view does.
        VendorExchange exchange = ReturnedSecrets.masked(received);
        // The body as received when it could not be parsed, and anything worth knowing about the
        // body as a whole, come first so they are not lost below a long list.
        List<AdditionalField> additional = new ArrayList<>(exchange.bodyFields());
        List<SubscriptionView> subscriptions = new ArrayList<>();
        List<BundleResourceView> others = new ArrayList<>();
        List<IssueView> issues = new ArrayList<>();
        Shown bundleType = Shown.absent();
        Shown bundleId = Shown.absent();
        Shown total = Shown.absent();
        SubscriptionResponseKind kind;
        String note = null;

        JsonNode json = exchange.json();
        if (json == null) {
            if (exchange.httpStatus() == null) {
                kind = SubscriptionResponseKind.NO_RESPONSE;
                note = "No response arrived.";
            } else if (exchange.responseBody() == null) {
                kind = SubscriptionResponseKind.NO_BODY;
                note = exchange.ok() ? "The response has no body. For this call that is a normal answer." : "The response has no body.";
            } else {
                kind = SubscriptionResponseKind.NOT_JSON;
                note = ("The body could not be read as JSON. It is shown as received, but for any place that repeats a secret the request "
                        + "carried, under the fields that were returned "
                        + "but not interpreted, and in the wire view. " + Texts.orElse(exchange.parseError(), "")).strip();
            }
        } else {
            TrackedJson tracked = TrackedJson.of(json);
            TrackedJson.Node root = tracked.root();
            String type = JsonSafe.text(json, "resourceType");
            if ("Subscription".equals(type)) {
                kind = SubscriptionResponseKind.SUBSCRIPTION;
                subscriptions.add(subscription(root, Shown.absent(), Shown.absent()));
            } else if ("Bundle".equals(type)) {
                kind = SubscriptionResponseKind.BUNDLE;
                root.text("resourceType");
                bundleType = root.shown("type");
                bundleId = root.shown("id");
                total = root.shown("total");
                for (TrackedJson.Node entry : root.children("entry")) {
                    TrackedJson.Node resource = entry.child("resource");
                    if (!resource.isObject()) {
                        continue;
                    }
                    if ("Subscription".equals(JsonSafe.text(resource.raw(), "resourceType"))) {
                        TrackedJson.Node search = entry.child("search");
                        subscriptions.add(subscription(resource, entry.shown("fullUrl"), search.isObject() ? search.shown("mode") : Shown.absent()));
                    } else if (OperationOutcomes.is(resource.raw())) {
                        resource.text("resourceType");
                        issues.addAll(OperationOutcomes.issues(resource));
                    } else {
                        // Everything else the entry holds is listed with the resource it belongs to.
                        others.add(new BundleResourceView(resource.path(), resource.shown("resourceType"), resource.shown("id"),
                                entry.shown("fullUrl"), entry.remainder()));
                    }
                }
            } else if (OperationOutcomes.is(json)) {
                kind = SubscriptionResponseKind.OPERATION_OUTCOME;
                root.text("resourceType");
                issues.addAll(OperationOutcomes.issues(root));
            } else {
                kind = SubscriptionResponseKind.OTHER_JSON;
                note = root.isObject()
                        ? "The body is JSON but is not a Subscription, a bundle or an OperationOutcome, so none of it could be "
                        + "interpreted. All of it is listed below."
                        : "The body is JSON but is not an object, so none of it could be interpreted. All of it is listed below.";
            }
            additional.addAll(tracked.unconsumed());
        }

        boolean aboutOne = Texts.present(askedId);
        boolean reportsError = kind == SubscriptionResponseKind.OPERATION_OUTCOME && issues.stream().anyMatch(SubscriptionResponseMapper::failed);
        SubscriptionOutcome outcome = SubscriptionOutcomeRules.overall(operation, exchange, kind, aboutOne, reportsError);
        if (note == null && reportsError && outcome == SubscriptionOutcome.UNRECOGNISED_RESPONSE && operation.write()) {
            note = "The service answered with a success status and with an OperationOutcome whose severity says the call failed. The two "
                    + "contradict each other, so whether the call was applied is not known.";
        }
        if (note == null) {
            note = mismatch(operation, askedId, kind, subscriptions);
        }
        SubscriptionDetail detail = new SubscriptionDetail(kind, note, subscriptions, bundleType, bundleId, total, others, issues, null, null,
                null, List.of(), true);
        return new SubscriptionReading(detail, outcome, additional);
    }

    /** True when the severity code of an issue says the call failed. The code decides, never the wording beside it. */
    private static boolean failed(IssueView issue) {
        return issue.severity().hasValue() && (SEVERITY_ERROR.equals(issue.severity().value()) || SEVERITY_FATAL.equals(issue.severity().value()));
    }

    /**
     * What a tester should know when the answer is not about what was asked: a different
     * subscription than the one named, one subscription where a list was asked for, or the
     * reverse. The answer is still shown as what it is. Null when there is nothing of the kind.
     */
    private static String mismatch(EpaOperation operation, String askedId, SubscriptionResponseKind kind, List<SubscriptionView> subscriptions) {
        if (Texts.present(askedId) && kind == SubscriptionResponseKind.SUBSCRIPTION && subscriptions.get(0).id().hasValue()
                && !subscriptions.get(0).id().value().equals(askedId.strip())) {
            return "The service was asked about the subscription " + askedId.strip() + " and answered with the subscription "
                    + subscriptions.get(0).id().value() + ".";
        }
        if (operation != EpaOperation.SUBSCRIPTION_GET) {
            return null;
        }
        if (Texts.present(askedId) && kind == SubscriptionResponseKind.BUNDLE) {
            return "One subscription was asked for and the service answered with a list.";
        }
        if (Texts.blank(askedId) && kind == SubscriptionResponseKind.SUBSCRIPTION) {
            return "A list was asked for and the service answered with one subscription. The url this call went to may name one: "
                    + "check it in the wire view.";
        }
        return null;
    }

    private static SubscriptionView subscription(TrackedJson.Node resource, Shown fullUrl, Shown searchMode) {
        resource.text("resourceType");
        List<ExtensionView> extensions = new ArrayList<>();
        for (TrackedJson.Node extension : resource.children("extension")) {
            if (extension.isObject()) {
                extensions.add(PasResponseMapper.extension(extension, Texts.orElse(extension.presentText("url"), "(no url)")));
            }
        }
        List<Shown> profiles = new ArrayList<>();
        for (TrackedJson.Node profile : resource.child("meta").children("profile")) {
            profiles.add(profile.shown());
        }

        List<Shown> filters = new ArrayList<>();
        for (TrackedJson.Node extension : resource.child("_criteria").children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            String url = extension.presentText("url");
            if (OnyxPasConventions.FILTER_CRITERIA.equals(url)) {
                filters.add(PasResponseMapper.present(FhirViews.extensionValueShown(extension)));
            } else {
                extensions.add(PasResponseMapper.extension(extension, "criteria: " + Texts.orElse(url, "(no url)")));
            }
        }

        TrackedJson.Node channel = resource.child("channel");
        Shown heartbeat = Shown.absent();
        Shown timeout = Shown.absent();
        Shown maxCount = Shown.absent();
        for (TrackedJson.Node extension : channel.children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            String url = extension.presentText("url");
            if (OnyxPasConventions.HEARTBEAT_PERIOD.equals(url)) {
                heartbeat = PasResponseMapper.present(FhirViews.extensionValueShown(extension));
            } else if (OnyxPasConventions.TIMEOUT.equals(url)) {
                timeout = PasResponseMapper.present(FhirViews.extensionValueShown(extension));
            } else if (OnyxPasConventions.MAX_COUNT.equals(url)) {
                maxCount = PasResponseMapper.present(FhirViews.extensionValueShown(extension));
            } else {
                extensions.add(PasResponseMapper.extension(extension, "channel: " + Texts.orElse(url, "(no url)")));
            }
        }
        Shown payloadContent = Shown.absent();
        for (TrackedJson.Node extension : channel.child("_payload").children("extension")) {
            if (!extension.isObject()) {
                continue;
            }
            String url = extension.presentText("url");
            if (OnyxPasConventions.PAYLOAD_CONTENT.equals(url) && payloadContent.presence() == Presence.ABSENT) {
                payloadContent = PasResponseMapper.present(FhirViews.extensionCode(extension));
            } else {
                extensions.add(PasResponseMapper.extension(extension, "payload: " + Texts.orElse(url, "(no url)")));
            }
        }
        List<Shown> headers = new ArrayList<>();
        for (TrackedJson.Node header : channel.children("header")) {
            headers.add(headerName(header));
        }

        return new SubscriptionView(resource.path(), fullUrl, resource.shown("id"), profiles,
                PasCodes.coded(PasCodes.SUBSCRIPTION_STATUS, resource.shown("status")), resource.shown("criteria"), filters,
                resource.shown("reason"), resource.shown("end"), resource.shown("error"),
                channel.isObject() ? channel.shown("type") : Shown.absent(),
                channel.isObject() ? channel.shown("endpoint") : Shown.absent(),
                channel.isObject() ? channel.shown("payload") : Shown.absent(),
                PasCodes.coded(PasCodes.PAYLOAD_CONTENT, payloadContent), heartbeat, timeout, maxCount, headers, searchMode, extensions);
    }

    /**
     * The name of one notification header and nothing of its value. A header is written
     * "Name: value", and the value is what lets a caller into the receiver, so the whole entry is
     * taken off the list of returned fields and only the name is put on screen. An entry with no
     * colon cannot be split, so none of it is shown.
     */
    private static Shown headerName(TrackedJson.Node header) {
        String text = JsonSafe.text(header.raw());
        header.consume();
        int colon = text == null ? -1 : text.indexOf(':');
        String name = colon > 0 ? text.substring(0, colon).strip() : null;
        return Shown.of(Texts.present(name) ? name + ": (value not shown)" : "(a header that could not be read as Name: value, not shown)");
    }
}
