package org.p32h.interop.epa.pave.service.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The line a step shows where the tester sends it, before anything is sent (D-321): the collection
 * request the step is sent from carries a diagnosis or a servicing provider, and the case names
 * none. A tester may mean to send a check without one, so it is a line and never a block.
 *
 * <p>The request is the one a call of the step would follow (the request chosen, the one a typed
 * address goes to, else the first mapped), read by fixed paths and by presence only
 * ({@link SampleOrder}): no code, no id and no name of the sample is shown or kept. With no
 * collection, no request behind the step, a request with no body, a body that is not JSON or a
 * sample of an error, nothing is said.
 */
public final class BeforeSending {

    /** The steps that send a case built by this tool and whose collection request may carry either. */
    public static final Set<EpaOperation> STEPS = Set.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.CRD_ORDER_SELECT,
            EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, EpaOperation.PAS_SUBMIT);

    public static final String BOTH = "The collection's request for this step carries a diagnosis and a servicing provider. This case "
            + "names neither. Add Diagnosis codes on the Service card and a Servicing provider on the Provider card, or send as it is.";
    public static final String DIAGNOSIS = "The collection's request for this step carries a diagnosis. This case names none. Add "
            + "Diagnosis codes on the Service card, or send as it is.";
    public static final String SERVICING = "The collection's request for this step carries a servicing provider. This case names none. "
            + "Add a Servicing provider on the Provider card, or send as it is.";

    /**
     * What a step asks before it is sent: its operation, the request chosen for it, the values typed
     * for the run (which can decide where a typed address goes), and the two parts of the case.
     */
    public record Ask(String operation, RequestRef request, Map<String, String> variables, List<CodeInput> diagnosisCodes,
                      ProviderDetail servicingProvider) {

        public Ask {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            diagnosisCodes = diagnosisCodes == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(diagnosisCodes));
        }

        /** Redacted: the case's diagnosis and servicing provider are member and provider data. */
        @Override
        public String toString() {
            return "Ask[" + operation + "]";
        }
    }

    /**
     * The answer: the line, or none.
     *
     * @param line            the sentence the step shows, or absent when there is nothing to say
     * @param sampleDiagnosis whether the request the step is sent from carries a diagnosis
     * @param sampleServicing whether it carries a servicing provider
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Said(String line, boolean sampleDiagnosis, boolean sampleServicing) {
    }

    private BeforeSending() {
    }

    /** The line for what the sample carries and what the case names, or null when nothing is missing. */
    public static String line(SampleOrder sample, boolean caseDiagnosis, boolean caseServicing) {
        boolean diagnosis = sample.carriesDiagnosis() && !caseDiagnosis;
        boolean servicing = sample.carriesPerformer() && !caseServicing;
        if (diagnosis && servicing) {
            return BOTH;
        }
        return diagnosis ? DIAGNOSIS : servicing ? SERVICING : null;
    }

    /** True when a case states a diagnosis: one code that is not blank. */
    public static boolean statesDiagnosis(List<CodeInput> codes) {
        return codes != null && codes.stream().anyMatch(code -> code != null && Texts.present(code.code()));
    }

    /**
     * What a step says before it is sent.
     *
     * @throws ValidationException for an operation that is not one of the steps
     */
    public static Said ask(OperationGateway gateway, Ask ask) {
        EpaOperation operation = STEPS.stream().filter(step -> step.name().equals(Texts.trimToNull(ask.operation()))).findFirst()
                .orElseThrow(() -> ValidationException.of("operation", "Name the step: CRD_ORDER_SIGN, CRD_ORDER_SELECT, "
                        + "DTR_QUESTIONNAIRE_PACKAGE or PAS_SUBMIT."));
        OperationGateway.Sample sample = gateway.sample(operation, ask.request(), ask.variables());
        SampleOrder order = sample == null ? SampleOrder.NONE : SampleOrder.ofCall(sample.name(), sample.body());
        return new Said(line(order, statesDiagnosis(ask.diagnosisCodes()), CaseResources.names(ask.servicingProvider())),
                order.carriesDiagnosis(), order.carriesPerformer());
    }
}
