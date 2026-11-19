package org.p32h.interop.epa.pave.service.cases;

import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
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
    public CaseRun run(CaseContext context) {
        return CaseRun.of(crd.run(request(context, hook), context.origin(), null));
    }

    /** The CRD request for a case, with the expectation and the chain the case itself names. */
    public static CrdRunRequest request(CaseContext context, String hook) {
        return request(context, hook, context.spec().expected(), context.spec().chainId());
    }

    /** The same request for a chain, whose first step it is: the chain says what this step expects and which chain it joins. */
    static CrdRunRequest request(CaseContext context, String hook, String expected, String chainId) {
        CaseSpec spec = context.spec();
        return CrdRunRequest.builder()
                .hook(hook)
                .member(context.member())
                .provider(context.provider())
                .procedureCodes(context.codes())
                .dateOfService(spec.dateOfService())
                .scenarioId(spec.scenarioId())
                .expected(expected)
                .testerRef(spec.testerRef())
                .note(spec.note())
                .chainId(chainId)
                .configEpoch(spec.configEpoch())
                .build();
    }
}
