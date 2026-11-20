package org.p32h.interop.epa.pave.service.cases;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;

/**
 * Runs the one DTR call a case can make by itself: fetching the questionnaire package, through the
 * same service method the DTR tab calls. The steps after it, answering the questions, need what a
 * person answers, so they are not something a suite row or a replay can run.
 */
public final class DtrCaseStep implements CaseStep {

    private final DtrService dtr;

    public DtrCaseStep(DtrService dtr) {
        this.dtr = dtr;
    }

    @Override
    public CaseRun run(CaseContext context) {
        CaseSpec spec = context.spec();
        // A replayed fetch asks for the questionnaires its ledger line names. A suite row names none, and the service
        // then asks for whatever the order calls for.
        List<String> questionnaires = addresses(spec.correlation().get(RunRecord.CORR_QUESTIONNAIRE));
        return CaseRun.of(dtr.runPackage(packageRequest(context, spec.expected(), spec.chainId(), questionnaires, null), context.origin(), null));
    }

    /**
     * A statement the ledger writes in place of something it did not keep, such as "(not a url, 12
     * characters)" or "(not recorded, 40 characters)". It holds spaces, so it must never be split
     * into words and sent as addresses.
     */
    private static final Pattern STATEMENT = Pattern.compile("\\(not [a-z ]+, \\d+ characters\\)");

    /** True when the questionnaires a line names hold a statement in place of an address, so the request cannot be rebuilt. */
    static boolean holdsStatement(String named) {
        return named != null && STATEMENT.matcher(named).find();
    }

    /**
     * The questionnaire addresses a line names, separated by white space. A statement in their
     * place is left out whole, never cut into words; {@link #holdsStatement} lets a caller refuse
     * the case first, so that a request missing an address is not sent as if it were the same one.
     */
    static List<String> addresses(String named) {
        if (Texts.blank(named)) {
            return List.of();
        }
        return Arrays.stream(STATEMENT.matcher(named).replaceAll(" ").strip().split("\\s+")).filter(Texts::present).toList();
    }

    /** A package request for a case. The chain uses it for its second step, with what CRD handed on. */
    static DtrPackageRequest packageRequest(CaseContext context, String expected, String chainId, List<String> questionnaires,
                                            Map<String, List<String>> carry) {
        CaseSpec spec = context.spec();
        return DtrPackageRequest.builder()
                .member(context.member())
                .provider(context.provider())
                .procedureCodes(context.codes())
                .dateOfService(spec.dateOfService())
                .questionnaires(questionnaires)
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
