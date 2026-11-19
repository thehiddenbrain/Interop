package org.p32h.interop.epa.pave.service.cases;

import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;

/** Runs a CRD case through the CRD service, as order-sign or order-select. */
public final class CrdCaseStep implements CaseStep {

    private final CrdService crd;
    private final String hook;

    public CrdCaseStep(CrdService crd, EpaOperation operation) {
        this.crd = crd;
        this.hook = operation == EpaOperation.CRD_ORDER_SELECT ? CrdRunRequest.ORDER_SELECT : CrdRunRequest.ORDER_SIGN;
    }

    @Override
    public RunEnvelope<?> run(CaseContext context) {
        return crd.run(request(context, hook), context.origin(), null);
    }

    /** The CRD request for a case. Shared with the chain, whose first step is this same request. */
    public static CrdRunRequest request(CaseContext context, String hook) {
        CaseSpec spec = context.spec();
        return CrdRunRequest.builder()
                .hook(hook)
                .member(context.member())
                .provider(context.provider())
                .procedureCodes(context.codes())
                .dateOfService(spec.dateOfService())
                .scenarioId(spec.scenarioId())
                .expected(spec.expected())
                .testerRef(spec.testerRef())
                .note(spec.note())
                .chainId(spec.chainId())
                .configEpoch(spec.configEpoch())
                .build();
    }
}
