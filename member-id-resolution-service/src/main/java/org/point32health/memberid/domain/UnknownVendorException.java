package org.point32health.memberid.domain;

import org.point32health.memberid.api.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 400: the vendor code is not in the configured table. The received value is never echoed. */
public class UnknownVendorException extends ResolutionException {

    public static final String CODE = "UNKNOWN_VENDOR";

    public UnknownVendorException(List<String> knownCodes) {
        super(HttpStatus.BAD_REQUEST, CODE, "vendor is not configured",
                List.of(new ErrorDetail("vendor", "KNOWN_VENDORS", String.join(", ", knownCodes))));
    }
}
