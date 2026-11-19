package org.p32h.interop.epa.pave.client.lookup;

import java.util.List;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;

/**
 * Says how a lookup ended whenever it did not end with a record: not found, or one of the faults.
 *
 * <p>The two are never given the same words. An identifier the far side does not recognise is a
 * finding about the test data, and has no error panel. A far side that is down, refuses the call
 * or answers with something that cannot be read is a finding about the environment or about the
 * mapping, and says which.
 */
final class LookupOutcomes {

    /**
     * @param message one sentence for the confirmation strip
     * @param error   the error panel for a fault. Null for not found, which is an answer and not a fault
     */
    record Ending(LookupStatus status, String message, ErrorView error) {
    }

    private LookupOutcomes() {
    }

    /**
     * How the call ended when it did not end with a body to read. Null when it did, in which case
     * the record is looked for next.
     *
     * @param tracked the response, so that an OperationOutcome is read issue by issue and what was
     *                read is not listed a second time among the returned fields
     */
    static Ending ofCall(VendorExchange exchange, LookupWords words, TrackedJson tracked) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.NONE) {
            return null;
        }
        Integer status = exchange.httpStatus();
        if (Exchanges.INCOMPLETE_BODY.equals(exchange.parseError())) {
            // Even with a 404 status. An answer that stopped part way is not taken as an answer.
            return fault(kindOf(failure), words.api() + " started to answer with HTTP " + status + " and the answer did not arrive in full.",
                    "The part that arrived is shown as received under the returned fields. Leave the field again to retry, and report "
                            + "the environment if it repeats. Until then, " + words.byHand() + ".", exchange, tracked);
        }
        return switch (failure) {
            case NONE -> null;
            case BAD_REQUEST -> rejected(exchange, status == null ? 0 : status, words, tracked);
            case SERVER_ERROR -> fault(ErrorKind.VENDOR_ERROR, words.api() + " failed with HTTP " + status + ".",
                    "This is a fault in the environment, not in the " + words.identifier() + ". " + words.byHandSentence()
                            + " Report the environment if it repeats.", exchange, tracked);
            case TIMEOUT -> fault(ErrorKind.TIMEOUT, words.api() + " did not answer inside the read timeout.",
                    "A lookup gives up quickly so that it never holds up the form. " + words.byHandSentence()
                            + " Leave the field again to retry.", exchange, tracked);
            case UNAVAILABLE -> {
                String message = words.api() + " could not be reached.";
                ErrorView error = new ErrorView(ErrorKind.UNREACHABLE, (message + " " + Texts.orElse(exchange.failureDetail(), "")).strip(),
                        "Check " + words.property() + ".url, the network and any proxy or trust store settings. Until then, "
                                + words.byHand() + ".", null, List.of(), List.of(), null);
                yield new Ending(LookupStatus.FAULT, message, error);
            }
            case MALFORMED_RESPONSE -> exchange.responseBody() == null
                    ? fault(ErrorKind.MALFORMED_RESPONSE, words.api() + " answered HTTP " + status + " with an empty body, so there is "
                            + "nothing to read a " + words.record() + " from.",
                    "An empty answer does not say whether the " + words.identifier() + " is unknown, so this is not reported as not "
                            + "found. If this API answers an unknown " + words.identifier() + " this way, the mapping has to be told so. "
                            + "Until then, " + words.byHand() + ".", exchange, tracked)
                    : fault(ErrorKind.MALFORMED_RESPONSE, words.api() + " answered HTTP " + status + " with a body that is not JSON.",
                    "The body is shown exactly as received under the returned fields. " + words.byHandSentence()
                            + " Report it as a defect in the service.", exchange, tracked);
        };
    }

    private static Ending rejected(VendorExchange exchange, int status, LookupWords words, TrackedJson tracked) {
        if (status == 404) {
            return new Ending(LookupStatus.NOT_FOUND, words.api() + " does not know this " + words.identifier() + " (it answered HTTP 404).",
                    null);
        }
        if (status == 401 || status == 403) {
            boolean credentialSent = exchange.request() != null && exchange.request().headers().stream()
                    .map(WireHeader::name).anyMatch(name -> "authorization".equalsIgnoreCase(name));
            return fault(ErrorKind.VENDOR_REJECTED, words.api() + " refused the lookup with HTTP " + status
                            + ", which means the credentials were not accepted or none were sent.",
                    "The lookup went out " + (credentialSent ? "with" : "without") + " an Authorization header. The headers that were "
                            + "sent are in the wire view. Check " + words.property() + ".headers, and if this API takes the Onyx access "
                            + "token, " + words.property() + ".use-onyx-auth and the onyx.auth settings. Until then, " + words.byHand()
                            + ".", exchange, tracked);
        }
        if (status >= 300 && status < 400) {
            return fault(ErrorKind.VENDOR_REJECTED, words.api() + " answered the lookup with a redirect, HTTP " + status
                            + ", which this tool does not follow.",
                    "A redirect means " + words.property() + ".url is not the address this API answers at. Correct it. Until then, "
                            + words.byHand() + ".", exchange, tracked);
        }
        return fault(ErrorKind.VENDOR_REJECTED, words.api() + " did not accept the lookup and answered HTTP " + status + ".",
                "What it returned is listed under the returned fields. If the " + words.identifier() + " is written correctly, check that "
                        + words.property() + ".url has the form this API expects. Until then, " + words.byHand() + ".", exchange, tracked);
    }

    /**
     * The lookup is switched off in the configuration. Not an error: the tester types the details
     * by hand. The same on both profiles, so the state can be seen on mock.
     */
    static Ending switchedOff(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is switched off (" + words.property() + ".enabled is false): "
                + words.byHand() + ".", null);
    }

    /** The response holds no record: an empty object, an empty list, or a wrapper holding nothing. */
    static Ending noRecord(LookupWords words) {
        return new Ending(LookupStatus.NOT_FOUND, words.api() + " answered, and its answer holds no " + words.record() + " for this "
                + words.identifier() + ".", null);
    }

    /** A record came back in which nothing has a value. */
    static Ending hollowRecord(LookupWords words) {
        return new Ending(LookupStatus.NOT_FOUND, words.api() + " answered with a record that holds no values, which this tool reads as "
                + "not found.", null);
    }

    static Ending several(LookupWords words, int count, VendorExchange exchange) {
        return unreadable(words.api() + " returned " + count + " records for one " + words.identifier()
                        + ", and this tool does not choose between them.",
                "A lookup by identifier expects one record. Every record that came back is listed under the returned fields. "
                        + words.byHandSentence(), exchange);
    }

    static Ending notARecord(LookupWords words, VendorExchange exchange) {
        return unreadable(words.api() + " answered with JSON that this tool cannot read as one " + words.record() + " record.",
                "What it returned is listed under the returned fields. " + words.byHandSentence(), exchange);
    }

    /** A record came back with values in it, and none of them under a name the mapping knows. */
    static Ending unrecognised(LookupWords words, VendorExchange exchange) {
        return unreadable(words.api() + " answered, but nothing in its answer is a field this tool reads a " + words.record() + " from.",
                "Everything it returned is listed under the returned fields. The response contract of this API is not final, and those "
                        + "fields are the evidence needed to finish the mapping. Until then, " + words.byHand() + ".", exchange);
    }

    private static Ending unreadable(String title, String action, VendorExchange exchange) {
        return new Ending(LookupStatus.FAULT, title,
                new ErrorView(ErrorKind.MALFORMED_RESPONSE, title, action, exchange.httpStatus(), List.of(), List.of(), null));
    }

    private static Ending fault(ErrorKind kind, String title, String action, VendorExchange exchange, TrackedJson tracked) {
        List<IssueView> issues = List.of();
        if (exchange.json() != null && OperationOutcomes.is(exchange.json())) {
            // The resource type was read to decide this, so it is not listed again as a field nobody interpreted.
            tracked.root().child("resourceType").consume();
            issues = OperationOutcomes.issues(tracked.root());
        }
        return new Ending(LookupStatus.FAULT, title, new ErrorView(kind, title, action, exchange.httpStatus(), List.of(), issues, null));
    }

    private static ErrorKind kindOf(OnyxFailureType failure) {
        return switch (failure) {
            case SERVER_ERROR -> ErrorKind.VENDOR_ERROR;
            case BAD_REQUEST -> ErrorKind.VENDOR_REJECTED;
            case TIMEOUT -> ErrorKind.TIMEOUT;
            case UNAVAILABLE -> ErrorKind.UNREACHABLE;
            case NONE, MALFORMED_RESPONSE -> ErrorKind.MALFORMED_RESPONSE;
        };
    }
}
