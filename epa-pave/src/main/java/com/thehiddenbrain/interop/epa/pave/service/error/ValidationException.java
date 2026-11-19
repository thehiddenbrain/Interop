package com.thehiddenbrain.interop.epa.pave.service.error;

import com.thehiddenbrain.interop.epa.pave.model.FieldError;
import java.util.List;

/** The submitted form is not valid. Nothing was sent. Answered with HTTP 422. */
public class ValidationException extends RuntimeException {

    private final List<FieldError> fieldErrors;

    public ValidationException(String title, List<FieldError> fieldErrors) {
        super(title);
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ValidationException(List<FieldError> fieldErrors) {
        this("The form has " + (fieldErrors == null ? 0 : fieldErrors.size()) + " problem(s). Nothing was sent.", fieldErrors);
    }

    public static ValidationException of(String field, String message) {
        return new ValidationException(List.of(new FieldError(field, message)));
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
