package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.run.MemberInText;
import org.p32h.interop.epa.pave.service.run.RunSupport;

/**
 * Runs one case from its inputs alone, with no browser, for a suite row and for a replay.
 *
 * <p>A case takes exactly the path a manual run takes: the member and provider are looked up
 * through the same lookups, the request goes through the same protocol service, and the run
 * writes the same kind of ledger line. What differs is only where the inputs came from.
 *
 * <p>It never throws. Each way a case can fail to run has its own status and a reason in words,
 * so a suite table shows why a row has no result, and a write the guardrail refused is reported
 * as blocked and never as a failed test. A failure of this tool carries the reference it was
 * logged under, and says that whether a request left before it is not known.
 *
 * <p>A chain that stops part way, at a step that was refused, blocked or failed, keeps the steps
 * it already sent in the outcome, so nothing that went out is ever reported as not sent.
 */
public class ProtocolCaseRunner implements CaseRunner {

    private final MemberLookup members;
    private final ProviderLookup providers;
    private final Map<EpaOperation, CaseStep> steps = new EnumMap<>(EpaOperation.class);
    private CaseStep chain;
    private java.util.function.BiPredicate<Protocol, String> knownScenario;
    private MemberLookup previewMembers;

    public ProtocolCaseRunner(MemberLookup members, ProviderLookup providers) {
        this.members = members;
        this.providers = providers;
    }

    /** Says how one operation is run for a case. An operation with no step is reported as not runnable. */
    public ProtocolCaseRunner register(EpaOperation operation, CaseStep step) {
        steps.put(operation, step);
        return this;
    }

    /**
     * Says how to tell whether a mock scenario exists for a protocol, so a case that names one that
     * does not is found before anything is sent. Without it the service finds it when the case runs.
     */
    public ProtocolCaseRunner scenarios(java.util.function.BiPredicate<Protocol, String> known) {
        this.knownScenario = known;
        return this;
    }

    /**
     * Gives the lookup a check before sending may use, so that the free text of a case is held
     * against the member the run will look up, and a preview counts what the run will refuse.
     * Given only where a lookup sends nothing anywhere (the mock profile): without it the service
     * finds such text when the case runs.
     */
    public ProtocolCaseRunner previewMembers(MemberLookup lookup) {
        this.previewMembers = lookup;
        return this;
    }

    /** Says how a chain case is run. */
    public ProtocolCaseRunner registerChain(CaseStep step) {
        this.chain = step;
        return this;
    }

    @Override
    public CaseOutcome run(CaseSpec spec) {
        if (spec == null) {
            return new CaseOutcome(null, CaseStatus.INVALID, "There is no case to run.", List.of());
        }
        Lookups lookups = new Lookups();
        try {
            if (!spec.unrecognised().isEmpty()) {
                // A misspelt key would otherwise be dropped, and a case with no "expected" runs unverified without a word.
                return ended(spec, CaseStatus.INVALID, "The case has keys this tool does not know: "
                        + String.join(", ", spec.unrecognised().keySet()) + ". Check their spelling.", lookups, null, null);
            }
            String cannot = cannotRun(spec);
            if (cannot != null) {
                return ended(spec, Texts.present(cannot) && spec.operation() == null && !spec.chain() ? CaseStatus.INVALID
                        : CaseStatus.NOT_RUNNABLE, cannot, lookups, null, null);
            }
            CaseStep step = spec.chain() ? chain : steps.get(spec.operation());
            CaseContext context = new CaseContext(spec, member(spec, lookups), provider(spec, lookups), codes(spec), RunOrigin.of(spec));
            return completed(spec, step.run(context), lookups);
        } catch (RuntimeException e) {
            String what = spec.chain() ? "the chain" : spec.operation() == null ? null : spec.operation().label();
            CaseRun.Interruption cause = CaseFaults.classify(e, what, spec.configEpoch() != null);
            return ended(spec, cause.status(), cause.reason(), lookups, cause.faultReference(), cause.faultCause());
        }
    }

    /**
     * Why this runner cannot run the case at all, in plain words, or null when it can. The same
     * answer the run gives, so a suite preview can say before anything is sent which rows will not
     * run and why.
     */
    public String cannotRun(CaseSpec spec) {
        if (spec.chain()) {
            return chain == null ? "This build cannot run a chain from a case." : null;
        }
        EpaOperation operation = spec.operation();
        if (operation == null) {
            return "The case names no operation and is not a chain.";
        }
        if (!operation.outbound()) {
            return operation.label() + " records something that arrived or a step completed inside the tool. There is nothing to send again.";
        }
        if (operation == EpaOperation.DTR_QUESTIONNAIRE_PACKAGE
                && DtrCaseStep.holdsStatement(spec.correlation().get(RunRecord.CORR_QUESTIONNAIRE))) {
            return "The questionnaires this case names hold a statement that something was there in place of an address, because "
                    + "what was there did not have the shape of one. Sent without it, the request would not be the same one.";
        }
        if (steps.containsKey(operation)) {
            return null;
        }
        return switch (operation) {
            case DTR_NEXT_QUESTION, DTR_RESPONSE -> operation.label() + " answers a questionnaire, and the answers are a person's: a "
                    + "case does not hold them. Run it from the DTR tab, or run a chain, which stops where a person has to answer.";
            case SUBSCRIPTION_CREATE, SUBSCRIPTION_GET, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE -> operation.label() + " works on a "
                    + "subscription this tool holds for a submission, which a case does not set up. Run it from the PAS tab.";
            case CRD_DISCOVERY -> "This build does not run CRD discovery from a case. Run it from the CRD tab.";
            case MEMBER_LOOKUP, PROVIDER_LOOKUP -> "A lookup is made for every case already, before its request is built. It is not a "
                    + "case of its own.";
            case UNMAPPED -> "A request that is mapped to no operation is sent from the Config tab as it is. A case does not hold its body.";
            default -> "This build cannot run " + operation.label() + " from a case.";
        };
    }

    /**
     * What the services would refuse in this case before sending anything, found from the case
     * alone: each problem named by the column of a suite file that holds the value. Empty when
     * nothing is found here, which is not a promise that the service accepts the case. A case
     * this runner cannot run at all is not looked at: {@link #cannotRun} says why.
     */
    public List<String> refusals(CaseSpec spec) {
        List<String> problems = new ArrayList<>();
        if (spec == null || cannotRun(spec) != null) {
            return problems;
        }
        boolean noCodes = spec.procedureCodes().stream().noneMatch(Texts::present);
        boolean noNpi = Texts.blank(spec.npi());
        if (spec.chain()) {
            if (Texts.present(spec.scenarioId())) {
                problems.add("scenarioId: " + ChainCaseStep.SCENARIO_REFUSED);
            }
            if (spec.operation() != null && !ChainCaseStep.startsAChain(spec.operation())) {
                problems.add("operation: " + ChainCaseStep.OPERATION_REFUSED);
            }
            if (noCodes) {
                problems.add("procedureCodes: A chain starts with CRD, which needs at least one procedure code.");
            }
            if (noNpi) {
                problems.add("npi: A chain starts with CRD, which needs the ordering provider's NPI.");
            }
            return problems;
        }
        EpaOperation operation = spec.operation();
        if (noNpi) {
            problems.add("npi: Enter the ordering provider's NPI.");
        }
        if (noCodes && operation != EpaOperation.PAS_INQUIRE) {
            // A DTR package can be asked for by questionnaire instead, and a suite row has no column for one.
            problems.add("procedureCodes: Choose at least one procedure code.");
        }
        if (operation == EpaOperation.PAS_INQUIRE && Texts.blank(spec.correlation().get(RunRecord.CORR_CLAIM_ID))) {
            problems.add("claimId: Enter the claim id of the submission to ask about.");
        }
        if (Texts.present(spec.scenarioId()) && knownScenario != null && !knownScenario.test(operation.protocol(), spec.scenarioId().strip())) {
            problems.add("scenarioId: There is no " + operation.protocol().name() + " scenario with that id.");
        }
        // The service's own check of the note, the tester reference and the expectation, with the member it will be given, in the
        // service's own words, so the reason a preview gives is the reason the run gives.
        if (previewMembers != null && Texts.present(MemberIds.noSpace(spec.memberId()))) {
            MemberLookupResult found = previewMembers.lookup(spec.memberId());
            if (found.status() == LookupStatus.FOUND && found.member() != null) {
                MemberInText.problems(spec.note(), spec.testerRef(), spec.expected(), found.member(), "Nothing was sent.")
                        .forEach((field, problem) -> problems.add(field + ": " + problem));
            }
        }
        return problems;
    }

    /** The operations this runner has a step for. */
    public boolean runs(EpaOperation operation) {
        return operation != null && steps.containsKey(operation);
    }

    private static CaseOutcome completed(CaseSpec spec, CaseRun run, Lookups lookups) {
        // Each record comes from its receipt, which carries it whether or not the ledger kept it.
        List<RunRecord> records = new ArrayList<>();
        List<String> notRecorded = new ArrayList<>();
        List<String> scenarios = new ArrayList<>();
        boolean several = run.envelopes().size() > 1 || run.interrupted() != null;
        for (RunEnvelope<?> envelope : run.envelopes()) {
            records.add(envelope.ledger().record());
            if (!envelope.ledger().recorded()) {
                notRecorded.add(envelope.ledger().message());
            }
            String line = defaultScenario(envelope);
            if (line != null) {
                scenarios.add(several && envelope.operation() != null ? envelope.operation().label() + ": " + line : line);
            }
        }
        notRecorded.addAll(run.notRecorded());
        boolean sent = records.stream().anyMatch(ProtocolCaseRunner::sent);
        List<String> notes = lookups.notes(sent);
        notes.addAll(scenarios);
        if (run.interrupted() != null) {
            // The steps already sent stay in the outcome with their runs. The case as a whole stopped before a step that was due.
            CaseRun.Interruption cause = run.interrupted();
            return new CaseOutcome(spec, cause.status(), cause.reason(), records, notRecorded, notes, null, cause.faultReference(),
                    cause.faultCause());
        }
        RunEnvelope<?> last = run.last();
        if (RunSupport.isBlocked(last)) {
            String headline = last.verdict() == null ? "The production guardrail refused this write." : last.verdict().headline();
            // In a chain the steps before the blocked one went out, so the reason says which, and never that nothing was sent.
            String reason = run.envelopes().size() == 1 ? headline
                    : CaseFaults.sent(run.envelopes().subList(0, run.envelopes().size() - 1)) + "; "
                    + (last.operation() == null ? "the next step" : last.operation().label()) + " was blocked by the production "
                    + "guardrail, so it was not sent.";
            return new CaseOutcome(spec, CaseStatus.BLOCKED, reason, records, notRecorded, notes).withFound(last.verdict(), last.nextStep());
        }
        // What the last step found, as its endpoint returned it, so a replay can show it as the screens do.
        return new CaseOutcome(spec, CaseStatus.COMPLETED, run.stopped(), records, notRecorded, notes, run.overall())
                .withFound(last.verdict(), last.nextStep());
    }

    /** True when a record is of a call that went out: not a write the guardrail held back, nor a call this tool could not sign in for. */
    static boolean sent(RunRecord record) {
        return record != null && record.wentOut();
    }

    /**
     * The verdict's line saying the default mock scenario answered, or null. Without it a row
     * shows the default answer as if it were an answer about the member and code of the row.
     */
    private static String defaultScenario(RunEnvelope<?> envelope) {
        if (envelope.verdict() == null || envelope.verdict().supporting() == null) {
            return null;
        }
        for (String line : envelope.verdict().supporting()) {
            if (line != null && line.startsWith("Mock scenario ") && line.contains(": the default")) {
                return line;
            }
        }
        return null;
    }

    private static CaseOutcome ended(CaseSpec spec, CaseStatus status, String reason, Lookups lookups, String faultReference,
                                     String faultCause) {
        // Nothing went out, or nothing is known to have gone out, so no note may say that it did.
        return new CaseOutcome(spec, status, reason, List.of(), List.of(), lookups.notes(false), null, faultReference, faultCause);
    }

    /**
     * What the lookups of a case found, kept apart from what was then sent. A note is worded
     * once the case is over, so it never says a request was sent with the ID alone when nothing
     * was sent at all.
     */
    private static final class Lookups {
        private String member;
        private String provider;

        List<String> notes(boolean sent) {
            List<String> notes = new ArrayList<>();
            if (member != null) {
                notes.add(member + (sent ? " The request was sent with the member ID alone." : ""));
            }
            if (provider != null) {
                notes.add(provider + (sent ? " The request was sent with the NPI alone." : ""));
            }
            return notes;
        }
    }

    /**
     * The member for the case. Looked up by ID through the same lookup a tester uses. When the
     * lookup does not find the member, is not configured or fails, the case still runs, with the
     * member ID alone, exactly as a tester would send it with nothing else typed in: nothing is
     * made up to fill the gap, and the result says the lookup gave nothing.
     */
    private MemberDetail member(CaseSpec spec, Lookups lookups) {
        if (Texts.blank(MemberIds.noSpace(spec.memberId()))) {
            return null;
        }
        MemberLookupResult result = members.lookup(spec.memberId());
        if (result.status() == LookupStatus.FOUND && result.member() != null) {
            return result.member();
        }
        lookups.member = switch (result.status()) {
            case NOT_FOUND -> "The member lookup does not know this member ID.";
            case DISABLED -> "The member lookup is not configured.";
            case FAULT -> "The member lookup failed.";
            case FOUND -> "The member lookup answered with no member.";
        };
        return MemberDetail.builder().memberId(spec.memberId()).build();
    }

    private ProviderDetail provider(CaseSpec spec, Lookups lookups) {
        if (Texts.blank(spec.npi())) {
            return null;
        }
        ProviderLookupResult result = providers.lookup(spec.npi());
        if (result.status() == LookupStatus.FOUND && result.provider() != null) {
            return result.provider();
        }
        lookups.provider = switch (result.status()) {
            case NOT_FOUND -> "The provider lookup does not know this NPI.";
            case DISABLED -> "The provider lookup is not configured.";
            case FAULT -> "The provider lookup failed.";
            case FOUND -> "The provider lookup answered with no provider.";
        };
        return ProviderDetail.builder().npi(spec.npi().strip()).build();
    }

    private static List<CodeInput> codes(CaseSpec spec) {
        return spec.procedureCodes().stream().filter(Texts::present).map(code -> CodeInput.of(code.strip())).toList();
    }
}
