package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.CaseRunner;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one case from its inputs alone, with no browser, for a suite row and for a replay.
 *
 * <p>A case takes exactly the path a manual run takes: the member and provider are looked up
 * through the same lookups, the request goes through the same protocol service, and the run
 * writes the same kind of ledger line. What differs is only where the inputs came from.
 *
 * <p>It never throws. Each way a case can fail to run has its own status and a reason in words,
 * so a suite table shows why a row has no result, and a write the guardrail refused is reported
 * as blocked and never as a failed test.
 */
public class ProtocolCaseRunner implements CaseRunner {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCaseRunner.class);

    private final MemberLookup members;
    private final ProviderLookup providers;
    private final Map<EpaOperation, CaseStep> steps = new EnumMap<>(EpaOperation.class);
    private CaseStep chain;

    public ProtocolCaseRunner(MemberLookup members, ProviderLookup providers) {
        this.members = members;
        this.providers = providers;
    }

    /** Says how one operation is run for a case. An operation with no step is reported as not runnable. */
    public ProtocolCaseRunner register(EpaOperation operation, CaseStep step) {
        steps.put(operation, step);
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
        List<String> notes = new ArrayList<>();
        try {
            if (!spec.unrecognised().isEmpty()) {
                // A misspelt key would otherwise be dropped, and a case with no "expected" runs unverified without a word.
                return ended(spec, CaseStatus.INVALID, "The case has keys this tool does not know: "
                        + String.join(", ", spec.unrecognised().keySet()) + ". Check their spelling.", notes);
            }
            CaseStep step;
            if (spec.chain()) {
                step = chain;
                if (step == null) {
                    return ended(spec, CaseStatus.NOT_RUNNABLE, "This build cannot run a chain from a case.", notes);
                }
            } else {
                EpaOperation operation = spec.operation();
                if (operation == null) {
                    return ended(spec, CaseStatus.INVALID, "The case names no operation and is not a chain.", notes);
                }
                if (!operation.outbound()) {
                    return ended(spec, CaseStatus.NOT_RUNNABLE, operation.label() + " records something that arrived or a step completed "
                            + "inside the tool. There is nothing to send again.", notes);
                }
                step = steps.get(operation);
                if (step == null) {
                    return ended(spec, CaseStatus.NOT_RUNNABLE, "This build cannot run " + operation.label() + " from a case.", notes);
                }
            }
            CaseContext context = new CaseContext(spec, member(spec, notes), provider(spec, notes), codes(spec), RunOrigin.of(spec));
            return completed(spec, step.run(context), notes);
        } catch (ValidationException e) {
            return ended(spec, CaseStatus.INVALID, describe(e.fieldErrors()), notes);
        } catch (BadInputException | NotFoundException e) {
            return ended(spec, CaseStatus.INVALID, e.getMessage(), notes);
        } catch (UnresolvedVariablesException e) {
            return ended(spec, CaseStatus.NOT_CONFIGURED, "The active configuration has no value for: " + String.join(", ", e.variables())
                    + ". Set it on the Config tab and run the case again.", notes);
        } catch (EndpointNotConfiguredException | ConfigurationChangedException e) {
            return ended(spec, CaseStatus.NOT_CONFIGURED, e.getMessage(), notes);
        } catch (RuntimeException e) {
            String reference = UUID.randomUUID().toString();
            // The exception types and frames only. Never its message, and never the case, which holds a member ID.
            log.error("A case failed inside this tool, reference {}: {}", reference, Faults.describe(e));
            return ended(spec, CaseStatus.NOT_RUNNABLE, "This tool failed while running the case. Nothing can be said about the service "
                    + "from this row. Reference " + reference + ".", notes);
        }
    }

    private static CaseOutcome completed(CaseSpec spec, RunEnvelope<?> envelope, List<String> notes) {
        // The record comes from the receipt, which carries it whether or not the ledger kept it.
        List<String> notRecorded = envelope.ledger().recorded() ? List.of() : List.of(envelope.ledger().message());
        if (RunSupport.isBlocked(envelope)) {
            return new CaseOutcome(spec, CaseStatus.BLOCKED, envelope.verdict().headline(), List.of(envelope.ledger().record()), notRecorded,
                    notes);
        }
        return new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(envelope.ledger().record()), notRecorded, notes);
    }

    private static CaseOutcome ended(CaseSpec spec, CaseStatus status, String reason, List<String> notes) {
        return new CaseOutcome(spec, status, reason, List.of(), List.of(), notes);
    }

    /**
     * The member for the case. Looked up by ID through the same lookup a tester uses. When the
     * lookup does not find the member, is not configured or fails, the case still runs, with the
     * member ID alone, exactly as a tester would send it with nothing else typed in: nothing is
     * made up to fill the gap, and the result says the lookup gave nothing.
     */
    private MemberDetail member(CaseSpec spec, List<String> notes) {
        if (Texts.blank(MemberIds.noSpace(spec.memberId()))) {
            return null;
        }
        MemberLookupResult result = members.lookup(spec.memberId());
        if (result.status() == LookupStatus.FOUND && result.member() != null) {
            return result.member();
        }
        notes.add(switch (result.status()) {
            case NOT_FOUND -> "The member lookup does not know this member ID. The request was sent with the ID alone.";
            case DISABLED -> "The member lookup is not configured. The request was sent with the member ID alone.";
            case FAULT -> "The member lookup failed. The request was sent with the member ID alone.";
            case FOUND -> "The member lookup answered with no member. The request was sent with the member ID alone.";
        });
        return MemberDetail.builder().memberId(spec.memberId()).build();
    }

    private ProviderDetail provider(CaseSpec spec, List<String> notes) {
        if (Texts.blank(spec.npi())) {
            return null;
        }
        ProviderLookupResult result = providers.lookup(spec.npi());
        if (result.status() == LookupStatus.FOUND && result.provider() != null) {
            return result.provider();
        }
        notes.add(switch (result.status()) {
            case NOT_FOUND -> "The provider lookup does not know this NPI. The request was sent with the NPI alone.";
            case DISABLED -> "The provider lookup is not configured. The request was sent with the NPI alone.";
            case FAULT -> "The provider lookup failed. The request was sent with the NPI alone.";
            case FOUND -> "The provider lookup answered with no provider. The request was sent with the NPI alone.";
        });
        return ProviderDetail.builder().npi(spec.npi().strip()).build();
    }

    private static List<CodeInput> codes(CaseSpec spec) {
        return spec.procedureCodes().stream().filter(Texts::present).map(code -> CodeInput.of(code.strip())).toList();
    }

    private static String describe(List<FieldError> errors) {
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
