package org.p32h.interop.epa.pave.service.cases;

import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.pas.PasInquireRequest;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;

/**
 * Runs a PAS case, a submission or an inquiry, through the same service methods the PAS tab calls.
 *
 * <p>A submission run from a case always gets a new claim identifier, also when the case is a
 * replay of an earlier submission: sending it again is a new submission, and giving it the old
 * identifier would tie the new answer to the old request. An inquiry is about one claim, so it
 * uses the identifier the case carries, and without one the service refuses it before sending.
 */
public final class PasCaseStep implements CaseStep {

    private final PasService pas;
    private final boolean inquiry;

    public PasCaseStep(PasService pas, EpaOperation operation) {
        if (operation != EpaOperation.PAS_SUBMIT && operation != EpaOperation.PAS_INQUIRE) {
            throw new IllegalArgumentException("A PAS case is a submission or an inquiry.");
        }
        this.pas = pas;
        this.inquiry = operation == EpaOperation.PAS_INQUIRE;
    }

    @Override
    public CaseRun run(CaseContext context) {
        CaseSpec spec = context.spec();
        if (inquiry) {
            return CaseRun.of(pas.runInquire(PasInquireRequest.builder()
                    .claimId(spec.correlation().get(RunRecord.CORR_CLAIM_ID))
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
                    .build(), context.origin(), null));
        }
        return CaseRun.of(pas.runSubmit(submission(context, spec.expected(), spec.chainId(), null), context.origin(), null));
    }

    /** A submission for a case. The chain uses it for its last step, with what the earlier steps handed on. */
    static PasSubmitRequest submission(CaseContext context, String expected, String chainId,
                                       java.util.Map<String, java.util.List<String>> carry) {
        CaseSpec spec = context.spec();
        return PasSubmitRequest.builder()
                .member(context.member())
                .provider(context.provider())
                .procedureCodes(context.codes())
                .dateOfService(spec.dateOfService())
                .scenarioId(spec.chain() ? null : spec.scenarioId())
                .expected(expected)
                .testerRef(spec.testerRef())
                .note(spec.note())
                .chainId(chainId)
                .carry(carry)
                .configEpoch(spec.configEpoch())
                .build();
    }
}
