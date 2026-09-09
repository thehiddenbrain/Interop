package com.thehiddenbrain.interop.cms1500.domain;

import com.thehiddenbrain.interop.cms1500.contract.ClaimFault;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.FaultStatus;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;

import java.util.List;

/**
 * Any failure the caller should hear about as a {@link ClaimFault}: the REST layer maps the
 * {@link ErrorCode} to an HTTP status, the SOAP layer to a SOAP fault with the same detail.
 */
public class ClaimException extends RuntimeException {

    private final ErrorCode code;
    private final String claimNumber;
    private final List<ViolationDetail> details;

    public ClaimException(ErrorCode code, String claimNumber, String message) {
        this(code, claimNumber, message, List.of(), null);
    }

    public ClaimException(ErrorCode code, String claimNumber, String message, Throwable cause) {
        this(code, claimNumber, message, List.of(), cause);
    }

    public ClaimException(ErrorCode code, String claimNumber, String message, List<ViolationDetail> details) {
        this(code, claimNumber, message, details, null);
    }

    public ClaimException(ErrorCode code, String claimNumber, String message,
                          List<ViolationDetail> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.claimNumber = claimNumber;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public List<ViolationDetail> getDetails() {
        return details;
    }

    public ClaimFault toFault() {
        ClaimFault fault = new ClaimFault();
        fault.setStatus(FaultStatus.ERROR);
        fault.setClaimNumber(claimNumber);
        fault.setCode(code);
        fault.setMessage(getMessage());
        fault.getDetails().addAll(details);
        return fault;
    }

    public static ViolationDetail detail(String field, String message) {
        ViolationDetail d = new ViolationDetail();
        d.setField(field);
        d.setMessage(message);
        return d;
    }
}
