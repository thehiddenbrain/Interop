package com.thehiddenbrain.interop.memberid.domain;

import com.thehiddenbrain.interop.memberid.api.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 400: Onyx sent something the service cannot act on. Carries every problem found, not just the first. */
public class InvalidRequestException extends ResolutionException {

    public static final String CODE = "INVALID_REQUEST";

    public InvalidRequestException(List<ErrorDetail> details) {
        super(HttpStatus.BAD_REQUEST, CODE, "request rejected", details);
    }

    public InvalidRequestException(ErrorDetail detail) {
        this(List.of(detail));
    }
}
