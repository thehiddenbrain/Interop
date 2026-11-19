package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;

/**
 * Decides the outcome of a subscription call from the HTTP status, the kind of resource that came
 * back and transport facts. No wording is read here.
 *
 * <p>A success status alone does not make a call a success. A service, or something in front of
 * it, can answer 200 with a body that is not what was asked for, and reporting that as "created"
 * would be a claim this tool cannot back. So the body has to be what the call returns, or be
 * absent where the call may answer without one. Anything else is
 * {@link SubscriptionOutcome#UNRECOGNISED_RESPONSE}, shown in full.
 */
public final class SubscriptionOutcomeRules {

    private SubscriptionOutcomeRules() {
    }

    /**
     * @param kind     what kind of thing the response body was
     * @param aboutOne true when the call named one subscription by id. Only then can "not found"
     *                 be a finding about an id: a list that answers 404 was sent to the wrong address
     */
    public static SubscriptionOutcome overall(EpaOperation operation, VendorExchange exchange, SubscriptionResponseKind kind, boolean aboutOne) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.TIMEOUT) {
            return SubscriptionOutcome.TIMEOUT;
        }
        if (failure == OnyxFailureType.UNAVAILABLE) {
            return SubscriptionOutcome.UNREACHABLE;
        }
        if (failure == OnyxFailureType.MALFORMED_RESPONSE) {
            return SubscriptionOutcome.MALFORMED_RESPONSE;
        }
        // Not found is a finding about the id, so it is told apart from a fault even though it
        // arrives with an error status.
        if (aboutOne && OnyxPasConventions.subscriptionNotFound(exchange)) {
            return SubscriptionOutcome.NOT_FOUND;
        }
        if (failure == OnyxFailureType.SERVER_ERROR) {
            return SubscriptionOutcome.SERVICE_ERROR;
        }
        if (failure == OnyxFailureType.BAD_REQUEST) {
            return SubscriptionOutcome.REJECTED;
        }
        boolean resource = kind == SubscriptionResponseKind.SUBSCRIPTION;
        // A write may answer with the resource, with an OperationOutcome that reports on it, or with nothing.
        boolean acknowledged = resource || kind == SubscriptionResponseKind.NO_BODY || kind == SubscriptionResponseKind.OPERATION_OUTCOME;
        return switch (operation) {
            case SUBSCRIPTION_CREATE -> acknowledged ? SubscriptionOutcome.CREATED : SubscriptionOutcome.UNRECOGNISED_RESPONSE;
            case SUBSCRIPTION_UPDATE -> acknowledged ? SubscriptionOutcome.UPDATED : SubscriptionOutcome.UNRECOGNISED_RESPONSE;
            case SUBSCRIPTION_DELETE -> acknowledged ? SubscriptionOutcome.DELETED : SubscriptionOutcome.UNRECOGNISED_RESPONSE;
            case SUBSCRIPTION_GET -> resource ? SubscriptionOutcome.READ
                    : kind == SubscriptionResponseKind.BUNDLE ? SubscriptionOutcome.LISTED : SubscriptionOutcome.UNRECOGNISED_RESPONSE;
            default -> throw new IllegalArgumentException(operation + " is not a subscription call.");
        };
    }
}
