package org.p32h.interop.domain;

import org.p32h.interop.api.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 422: a date of birth was supplied and matches none of the records MMI returned for this id. */
public class DobMismatchException extends ResolutionException {

    public static final String CODE = "DOB_MISMATCH";

    public DobMismatchException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, CODE,
                "dateOfBirth does not match the plan record(s) for this member id",
                List.of(new ErrorDetail("dateOfBirth", CODE, "no returned record has this date of birth")));
    }
}
