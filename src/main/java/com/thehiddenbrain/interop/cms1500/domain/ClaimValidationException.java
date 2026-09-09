package com.thehiddenbrain.interop.cms1500.domain;

import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;

import java.util.List;

/** The claim content breaks one or more CMS-1500 rules; every violation is listed in the details. */
public class ClaimValidationException extends ClaimException {

    public ClaimValidationException(String claimNumber, List<ViolationDetail> violations) {
        super(ErrorCode.VALIDATION_ERROR, claimNumber,
                "Claim failed validation with " + violations.size() + " error(s): "
                        + violations.stream().map(v -> v.getField() + ": " + v.getMessage()).toList(),
                violations);
    }
}
