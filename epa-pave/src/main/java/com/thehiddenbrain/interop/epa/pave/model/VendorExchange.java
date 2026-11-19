package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One call to an external system and what came back, before any interpretation.
 *
 * <p>Clients return this for every outcome. A vendor fault is described here, never thrown, so the
 * tester always gets to see the request that caused it.
 *
 * @param request         the request as built
 * @param httpStatus      the HTTP status, or null when no response arrived
 * @param responseBody    the body as received, or null when there was none
 * @param json            the body parsed as JSON, or null when it is absent or not JSON
 * @param parseError      why a body that was present could not be parsed, or null
 * @param failureType     NONE unless the call failed at the transport or HTTP level
 * @param failureDetail   a short description of a transport failure. Never contains request data
 * @param signals         vendor-specific findings recognised by the client layer
 */
public record VendorExchange(
        WireRequest request,
        Integer httpStatus,
        List<WireHeader> responseHeaders,
        String responseBody,
        @JsonIgnore JsonNode json,
        String parseError,
        long elapsedMillis,
        OnyxFailureType failureType,
        String failureDetail,
        Set<VendorSignal> signals) {

    public VendorExchange {
        responseHeaders = responseHeaders == null ? List.of() : List.copyOf(responseHeaders);
        failureType = failureType == null ? OnyxFailureType.NONE : failureType;
        signals = signals == null || signals.isEmpty() ? Set.of() : Set.copyOf(signals);
    }

    /** True when a response arrived with a success status and a readable body. */
    public boolean ok() {
        return failureType == OnyxFailureType.NONE;
    }

    public boolean has(VendorSignal signal) {
        return signals.contains(signal);
    }

    public VendorExchange withSignals(Set<VendorSignal> newSignals) {
        EnumSet<VendorSignal> merged = EnumSet.noneOf(VendorSignal.class);
        merged.addAll(signals);
        if (newSignals != null) {
            merged.addAll(newSignals);
        }
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, json, parseError, elapsedMillis,
                failureType, failureDetail, merged);
    }

    /** The same exchange with the parsed body replaced, for a client that normalises a vendor shape. */
    public VendorExchange withJson(JsonNode newJson) {
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, newJson, parseError, elapsedMillis,
                failureType, failureDetail, signals);
    }

    /** The response half of the wire view. */
    public WireResponse wireResponse() {
        // Written as statements, not a nested conditional: mixing null and a primitive long in one
        // conditional expression unboxes the null.
        Long bytes;
        if (responseBody != null) {
            bytes = (long) responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } else if (httpStatus != null) {
            bytes = 0L;
        } else {
            bytes = null;
        }
        return new WireResponse(httpStatus, responseHeaders, responseBody, bytes, json != null, parseError);
    }

    public WireView wire(MemberIdForms memberId) {
        return new WireView(request, wireResponse(), memberId);
    }

    /** Redacted, because both bodies carry member data. */
    @Override
    public String toString() {
        return "VendorExchange[status=" + httpStatus + ", failure=" + failureType + ", elapsedMillis=" + elapsedMillis + "]";
    }
}
