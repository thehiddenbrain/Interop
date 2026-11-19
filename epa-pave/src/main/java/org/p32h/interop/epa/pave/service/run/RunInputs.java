package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The form checks every protocol shares. A run that fails one is refused with a 422 naming each
 * field, before anything is sent and before anything is recorded.
 *
 * <p>Each check appends to a list, so a tester sees every problem with the form at once and not
 * one per attempt.
 */
public final class RunInputs {

    private final List<FieldError> errors = new ArrayList<>();
    private final Clock clock;

    public RunInputs(Clock clock) {
        this.clock = clock;
    }

    /** A member ID is required and must not be blank once whitespace is removed. */
    public RunInputs member(MemberDetail member) {
        if (member == null || MemberIds.noSpace(member.memberId()).isEmpty()) {
            errors.add(new FieldError("member.memberId", "Enter a member ID."));
            return this;
        }
        if (Texts.present(member.dateOfBirth())) {
            LocalDate dateOfBirth = IsoDates.parse(member.dateOfBirth());
            if (dateOfBirth == null) {
                errors.add(new FieldError("member.dateOfBirth", "Enter the date of birth as YYYY-MM-DD, for example 1970-01-31."));
            } else if (dateOfBirth.isAfter(LocalDate.now(clock))) {
                errors.add(new FieldError("member.dateOfBirth", "The date of birth is in the future."));
            }
        }
        return this;
    }

    /** An NPI is required and its check digit must be right. */
    public RunInputs provider(ProviderDetail provider) {
        String npi = provider == null ? null : Texts.trimToNull(provider.npi());
        if (npi == null) {
            errors.add(new FieldError("provider.npi", "Enter the ordering provider's NPI."));
        } else if (!Npi.isValid(npi)) {
            errors.add(new FieldError("provider.npi", "This is not a valid NPI: it must be ten digits and its check digit does not match. "
                    + "Check for a typing mistake."));
        }
        return this;
    }

    /** At least one procedure code is required, and none may be blank. */
    public RunInputs codes(List<CodeInput> codes) {
        if (codes == null || codes.isEmpty()) {
            errors.add(new FieldError("procedureCodes", "Choose at least one procedure code."));
            return this;
        }
        for (int i = 0; i < codes.size(); i++) {
            if (codes.get(i) == null || Texts.blank(codes.get(i).code())) {
                errors.add(new FieldError("procedureCodes[" + i + "].code", "This procedure code is blank."));
            }
        }
        return this;
    }

    /** An optional date that must be a real calendar date when given. */
    public RunInputs date(String field, String value) {
        if (Texts.present(value) && !IsoDates.valid(value)) {
            errors.add(new FieldError(field, "Enter the date as YYYY-MM-DD, for example 2026-01-31."));
        }
        return this;
    }

    /**
     * A declared expectation must be something this operation can be checked against: outcomes it
     * can produce and elements it returns. A misspelt one is refused here, before sending, because
     * afterwards it could only be reported as a failed test with a misleading reason.
     */
    public RunInputs expectation(String expected, OutcomeCode[] vocabulary, Collection<String> elements) {
        for (String problem : Expectations.problems(expected, vocabulary, elements)) {
            errors.add(new FieldError("expected", problem));
        }
        return this;
    }

    /**
     * A scenario can be chosen on the mock profile only. On live it is refused, not ignored:
     * letting a tester force an outcome against the real service would produce a result that did
     * not come from the service, and ignoring the choice silently would leave them believing it had.
     */
    public RunInputs scenario(String scenarioId, EpaProperties epa) {
        if (Texts.present(scenarioId) && !epa.mock()) {
            errors.add(new FieldError("scenarioId", "A scenario can be chosen on the mock profile only. On live the answer comes from the service."));
        }
        return this;
    }

    public RunInputs require(boolean condition, String field, String message) {
        if (!condition) {
            errors.add(new FieldError(field, message));
        }
        return this;
    }

    public List<FieldError> errors() {
        return List.copyOf(errors);
    }

    /** Refuses the run when any check failed. */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
