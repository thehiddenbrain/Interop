package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The coded values this tool knows for PAS, subscriptions and notifications, with a plain language
 * meaning for each code.
 *
 * <p>The implementation guides could not be fetched while this was built, so the value sets below
 * are the builder's knowledge of them: the X12 review action codes a PAS ClaimResponse carries, the
 * FHIR ClaimResponse outcome codes, the R4 subscription status codes and the notification types and
 * payload contents of the subscriptions backport. A code outside its value set is never hidden and
 * never mapped to the nearest known one. It is shown as "Unrecognised code: X".
 */
public final class PasCodes {

    /** The payer's coded decision, on the response or on one item. */
    public static final String REVIEW_ACTION = "review-action";
    /** The coded reason given with a decision. */
    public static final String REVIEW_REASON = "review-reason";
    /** The ClaimResponse outcome code: how far processing got. */
    public static final String CLAIM_OUTCOME = "claim-outcome";
    /** The code of an error the ClaimResponse reported. */
    public static final String ERROR_CODE = "error-code";

    public static final String SUBSCRIPTION_STATUS = "subscription-status";
    public static final String CHANNEL_TYPE = "channel-type";
    public static final String PAYLOAD_CONTENT = "payload-content";
    public static final String NOTIFICATION_TYPE = "notification-type";

    /** The element names a declared expectation may assert on for a submission or an inquiry. */
    public static final List<String> EXPECTATION_ELEMENTS = List.of(REVIEW_ACTION, REVIEW_REASON, CLAIM_OUTCOME, ERROR_CODE);

    /** The element names a declared expectation may assert on for a subscription operation. */
    public static final List<String> SUBSCRIPTION_ELEMENTS = List.of(SUBSCRIPTION_STATUS, CHANNEL_TYPE, PAYLOAD_CONTENT);

    public static final String APPROVED = "A1";
    public static final String PARTIAL = "A2";
    public static final String DENIED = "A3";
    public static final String PENDED = "A4";
    public static final String MODIFIED = "A6";
    public static final String CANCELLED = "C";
    public static final String CONTACT_PAYER = "CT";
    public static final String NO_ACTION = "NA";

    public static final String CONTENT_EMPTY = "empty";
    public static final String CONTENT_ID_ONLY = "id-only";
    public static final String CONTENT_FULL = "full-resource";

    public static final String TYPE_HANDSHAKE = "handshake";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_EVENT = "event-notification";
    public static final String TYPE_QUERY_STATUS = "query-status";
    public static final String TYPE_QUERY_EVENT = "query-event";

    private static final Map<String, Map<String, String>> VALUE_SETS = new LinkedHashMap<>();

    static {
        VALUE_SETS.put(REVIEW_ACTION, ordered(
                APPROVED, "Certified in total",
                PARTIAL, "Certified in part",
                DENIED, "Not certified",
                PENDED, "Pended",
                MODIFIED, "Modified",
                CANCELLED, "Cancelled",
                CONTACT_PAYER, "Contact payer",
                NO_ACTION, "No action required"));
        VALUE_SETS.put(CLAIM_OUTCOME, ordered(
                "queued", "Queued: received, and processing has not finished",
                "complete", "Complete: processing has finished",
                "error", "Error: the request could not be processed",
                "partial", "Partial: some of the processing has been done"));
        VALUE_SETS.put(SUBSCRIPTION_STATUS, ordered(
                "requested", "Requested: the service has not yet activated it",
                "active", "Active",
                "error", "Error: the service could not deliver to it",
                "off", "Off",
                "entered-in-error", "Entered in error"));
        VALUE_SETS.put(PAYLOAD_CONTENT, ordered(
                CONTENT_EMPTY, "Empty: a notification says only that something happened",
                CONTENT_ID_ONLY, "Ids only: a notification names the resource and does not carry it",
                CONTENT_FULL, "Full resource: a notification carries the resource"));
        VALUE_SETS.put(NOTIFICATION_TYPE, ordered(
                TYPE_HANDSHAKE, "Handshake",
                TYPE_HEARTBEAT, "Heartbeat",
                TYPE_EVENT, "Event notification",
                TYPE_QUERY_STATUS, "Status report",
                TYPE_QUERY_EVENT, "Event report"));
    }

    private PasCodes() {
    }

    /** The codes of an element's value set, in order. Empty for an element this tool holds no value set for. */
    public static Set<String> codes(String element) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set == null ? Set.of() : set.keySet();
    }

    /** The plain language meaning of a code, or null when it is not in the element's value set. */
    public static String display(String element, String code) {
        Map<String, String> set = VALUE_SETS.get(element);
        return set == null || code == null ? null : set.get(code);
    }

    /**
     * A coded element as returned, with what the tool knows about the code.
     *
     * @param raw the code as read from the response, carrying whether it was absent, null or empty
     */
    public static CodedValue coded(String element, Shown raw) {
        if (raw == null || raw.presence() == Presence.ABSENT) {
            return CodedValue.notReturned();
        }
        if (!raw.hasValue()) {
            return new CodedValue(raw.presence(), null, null, true);
        }
        String code = raw.value();
        String display = display(element, code);
        if (display == null) {
            return new CodedValue(Presence.VALUE, code, "Unrecognised code: " + code, false);
        }
        return new CodedValue(Presence.VALUE, code, display, true);
    }

    /**
     * A code that is shown and not checked against a value set, because this tool holds none for
     * its element. It is never reported as unrecognised: the tool cannot say that.
     */
    public static CodedValue plain(Shown raw) {
        if (raw == null || raw.presence() == Presence.ABSENT) {
            return CodedValue.notReturned();
        }
        return new CodedValue(raw.presence(), raw.orNull(), null, true);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
