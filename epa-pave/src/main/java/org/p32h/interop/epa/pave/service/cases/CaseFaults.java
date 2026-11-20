package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a case that could not go on is reported, the same way for the first call of a case and for
 * a later step of a chain. Each cause has its own status, and a failure of this tool is never
 * reported as a fact about the service or about the row.
 */
public final class CaseFaults {

    private static final Logger log = LoggerFactory.getLogger(CaseFaults.class);

    private CaseFaults() {
    }

    /**
     * Classifies what a step threw.
     *
     * @param what the step in plain words, such as "PAS submit", for the sentence of a tool failure
     */
    public static CaseRun.Interruption classify(RuntimeException e, String what) {
        if (e instanceof ValidationException validation) {
            return new CaseRun.Interruption(CaseStatus.INVALID, describe(validation.fieldErrors()), null);
        }
        if (e instanceof BadInputException || e instanceof NotFoundException) {
            return new CaseRun.Interruption(CaseStatus.INVALID, e.getMessage(), null);
        }
        if (e instanceof UnresolvedVariablesException unresolved) {
            return new CaseRun.Interruption(CaseStatus.NOT_CONFIGURED, "The active configuration has no value for: "
                    + String.join(", ", unresolved.variables()) + ". Set it on the Config tab and run the case again.", null);
        }
        if (e instanceof EndpointNotConfiguredException || e instanceof ConfigurationChangedException) {
            return new CaseRun.Interruption(CaseStatus.NOT_CONFIGURED, e.getMessage(), null);
        }
        String reference = UUID.randomUUID().toString();
        // The exception types and frames only. Never its message, and never the case, which holds a member ID.
        log.error("A case failed inside this tool, reference {}: {}", reference, Faults.describe(e));
        return new CaseRun.Interruption(CaseStatus.NOT_RUNNABLE, toolFailed(what, reference), reference);
    }

    /**
     * The sentence for a failure of this tool. The failure can come after a request left, for
     * example while the result was being recorded, so the sentence never says nothing was sent.
     */
    static String toolFailed(String what, String reference) {
        return "This tool failed while running " + (what == null ? "the case" : what) + ". Whether a request left for it is not known, "
                + "and nothing can be said about the service from this. Look for it in History before running it again. Reference "
                + reference + ".";
    }

    /** The steps that were sent, in plain words: "CRD order-sign was sent" or "CRD order-sign and PAS submit were sent". */
    static String sent(List<RunEnvelope<?>> done) {
        List<String> labels = new ArrayList<>();
        for (RunEnvelope<?> envelope : done) {
            labels.add(envelope.operation() == null ? "a step" : envelope.operation().label());
        }
        return labels.size() == 1 ? labels.get(0) + " was sent" : String.join(" and ", labels) + " were sent";
    }

    static String describe(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "The case inputs are not valid.";
        }
        List<String> parts = new ArrayList<>();
        for (FieldError error : errors) {
            parts.add(error.field() + ": " + error.message());
        }
        return String.join(" ", parts);
    }
}
