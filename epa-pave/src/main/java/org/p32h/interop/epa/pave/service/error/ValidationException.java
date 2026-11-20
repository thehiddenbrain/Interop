package org.p32h.interop.epa.pave.service.error;

import org.p32h.interop.epa.pave.model.FieldError;
import java.util.List;

/** The submitted form is not valid. Nothing was sent. Answered with HTTP 422. */
public class ValidationException extends RuntimeException {

    private final List<FieldError> fieldErrors;

    public ValidationException(String title, List<FieldError> fieldErrors) {
        super(title);
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ValidationException(List<FieldError> fieldErrors) {
        this("The form has " + problems(fieldErrors == null ? 0 : fieldErrors.size()) + ". Nothing was sent.", fieldErrors);
    }

    /**
     * A count of problems in words that agree with it. The sentence after it in a refusal names a
     * send, and the exception handler rewords it for a preview, a comparison or an upload.
     */
    public static String problems(int count) {
        return count == 1 ? "1 problem" : count + " problems";
    }

    public static ValidationException of(String field, String message) {
        return new ValidationException(List.of(new FieldError(field, message)));
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
