package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.service.run.PlayedFaults;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CardLinkView;
import org.p32h.interop.epa.pave.model.crd.CardView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdResponseKind;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Says what a CRD result means in plain language, and what the tester should do next.
 *
 * <p>The sentences are built from the outcome and the standard codes. Card wording from the vendor
 * is shown elsewhere on the result and is never quoted here, so the answer at the top of the
 * screen cannot change because a vendor reworded a card.
 */
public final class CrdVerdicts {

    /**
     * The outcomes after which documentation or information wanted decides the next step. A
     * service that is not covered, a coverage that is not active, a member or a provider not found,
     * an answer that differs by code and a fault each keep their own next step.
     */
    private static final Set<CrdOutcome> DTR_FOLLOWS = Set.of(CrdOutcome.AUTH_REQUIRED, CrdOutcome.NO_AUTH_REQUIRED,
            CrdOutcome.AUTH_SATISFIED, CrdOutcome.PERFORMER_AUTH, CrdOutcome.DOCUMENTATION_REQUIRED, CrdOutcome.INFORMATION_NEEDED,
            CrdOutcome.CONDITIONAL, CrdOutcome.AUTH_NOT_STATED);

    /** Said when the payer asks who performs the service or where, which the form has no field for. */
    static final String OWN_REQUEST = "This tool's form has no field for the performer or the location of the service, so they can be "
            + "supplied only in a request of your own, pasted as the body.";

    private CrdVerdicts() {
    }

    /**
     * The verdict in the tone its next step allows. "No authorization is required" and "already
     * met" carry the tone that means nothing further is required of the provider, and in CRD 2.0.1
     * either can still come with documentation or information wanted, which sends the tester on to
     * DTR or back to CRD. The strip is then coloured for something to do, because a tone says what
     * the answer means for the person who must act. The outcome itself is unchanged.
     */
    public static VerdictView toned(VerdictView verdict, NextStepView next) {
        if (verdict.tone() != Tone.CLEAR || next == null || next.action() == NextAction.FLOW_ENDS || next.action() == NextAction.NOTHING_FURTHER) {
            return verdict;
        }
        return new VerdictView(verdict.headline(), verdict.outcomeCode(), verdict.outcomeLabel(), Tone.ACTION, verdict.supporting(),
                verdict.maskedReading());
    }

    public static VerdictView verdict(CrdReading reading, VendorExchange exchange) {
        CrdOutcome outcome = reading.outcome();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        boolean named = reading.several();
        List<String> supporting = new ArrayList<>();
        // First, so a canned answer is never mistaken for an answer about the member and code on the form.
        String scenario = RunSupport.scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case AUTH_REQUIRED -> "Prior authorization is required.";
            case NO_AUTH_REQUIRED -> "Prior authorization is not required.";
            case AUTH_SATISFIED -> "Prior authorization requirements are already met.";
            case PERFORMER_AUTH -> "The performing provider must obtain prior authorization.";
            case DOCUMENTATION_REQUIRED -> {
                for (CoverageInformationView view : coverage) {
                    // An answer that is partly conditional gets a "Definite" line that already
                    // states a recognised pa-needed, so it is not said twice in a row.
                    if (partlyConditional(view) && view.paNeeded().code() != null && view.paNeeded().recognised()) {
                        continue;
                    }
                    supporting.add(prefix(view, named) + "Prior authorization: "
                            + (view.paNeeded().code() == null ? "not stated" : lower(view.paNeeded().display())) + ".");
                }
                yield "Documentation is required.";
            }
            case INFORMATION_NEEDED -> "The payer needs more information before it can answer.";
            case CONDITIONAL -> "The answer is conditional on information not yet supplied.";
            case NOT_COVERED -> "This service is not a covered benefit.";
            case COVERAGE_INACTIVE -> "Coverage is not active on the date of service.";
            case MEMBER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this member.";
            }
            case PROVIDER_NOT_FOUND -> {
                supporting.add("This is a finding about the test data, not about the environment.");
                yield "The service does not recognise this provider.";
            }
            case AUTH_NOT_STATED -> "The payer says this is covered but does not say whether prior authorization is required.";
            case UNRECOGNISED_CODE -> {
                for (CoverageInformationView view : coverage) {
                    for (CodedValue value : List.of(view.covered(), view.paNeeded(), view.docNeeded())) {
                        if (value.code() != null && !value.recognised()) {
                            supporting.add(prefix(view, named) + value.display() + ".");
                        }
                    }
                }
                yield "The service returned a code this tool does not recognise.";
            }
            case MIXED -> {
                boolean byCoverage = differByCoverageOnly(reading);
                if (byCoverage) {
                    supporting.add("The response answers under more than one coverage, and the coverages do not give the same "
                            + "answer. That is a difference between the member's coverages, not between the codes.");
                }
                for (CoverageInformationView view : coverage) {
                    // An answer that could not be tied to a requested code is said to be exactly that,
                    // so nobody reads it as the answer for the code beside it.
                    boolean untied = view.procedureCodes().isEmpty() && !reading.requestedCodes().isEmpty();
                    supporting.add(view.subject() + ": " + lower(view.outcome().label()) + "."
                            + (untied ? " This tool could not tie this answer to one of the codes that were asked about." : ""));
                }
                for (String label : reading.unanswered()) {
                    supporting.add(label + ": no answer was returned for this code. That is not an answer that prior "
                            + "authorization is not required.");
                }
                yield byCoverage ? "The member's coverages did not all give the same answer."
                        : "The codes in this order did not all receive the same answer.";
            }
            case NO_DETERMINATION -> {
                supporting.add("This is not the same as an answer that prior authorization is not required. The service has said nothing either way.");
                yield "The service answered but returned no determination.";
            }
            case REJECTED -> "The service did not accept the request as sent" + status(exchange) + ".";
            case SERVICE_ERROR -> "The service failed" + status(exchange) + ".";
            case TIMEOUT -> "The service did not answer in time.";
            case UNREACHABLE -> "The service could not be reached.";
            case MALFORMED_RESPONSE -> malformedHeadline(reading, exchange);
        };
        // A played fault is described once, by the error panel beside the verdict: the failure detail a
        // scenario writes reads as a real connection failure under a verdict that nothing was sent.
        if (outcome.fault() && Texts.present(exchange.failureDetail()) && !PlayedFaults.played(exchange, outcome)) {
            supporting.add(exchange.failureDetail());
        }
        if (!outcome.fault()) {
            if (named && outcome != CrdOutcome.MIXED) {
                supporting.add("This is the answer for each of: "
                        + String.join(", ", coverage.stream().map(CoverageInformationView::subject).distinct().toList()) + ".");
            }
            supporting.addAll(facets(coverage, named));
        }
        return VerdictView.of(outcome, headline, dedupe(supporting));
    }

    /**
     * What the codes say beyond the headline, element by element: what is definite and what is
     * conditional, the vendor, the documentation and what it is for, the information wanted, and
     * the reason the payer gave.
     */
    private static List<String> facets(List<CoverageInformationView> coverage, boolean named) {
        List<String> out = new ArrayList<>();
        for (CoverageInformationView view : coverage) {
            String prefix = prefix(view, named);
            List<String> definite = new ArrayList<>();
            List<String> conditional = new ArrayList<>();
            sort(view.covered(), "coverage", definite, conditional);
            sort(view.paNeeded(), "prior authorization", definite, conditional);
            sort(view.docNeeded(), "documentation", definite, conditional);
            // Only an answer that is partly conditional is spelled out element by element. Where
            // nothing is conditional the headline already says what is definite.
            if (!conditional.isEmpty()) {
                if (!definite.isEmpty()) {
                    out.add(prefix + "Definite: " + String.join("; ", definite) + ".");
                }
                out.add(prefix + "Conditional on information not yet supplied: " + String.join(", ", conditional) + ".");
            }
            if (Boolean.TRUE.equals(view.authOutOfNetworkOnly())) {
                out.add(prefix + "The payer adds that prior authorization is needed only if the service is delivered out of network "
                        + "(detail auth-out-network-only).");
            }
            if (Texts.present(view.delegatedVendor())) {
                out.add(prefix + "It routes to the delegated vendor " + view.delegatedVendor() + ".");
            }
            if (view.satisfiedPaId().hasValue()) {
                out.add(prefix + "Identifier the payer gives for the requirements already met: " + view.satisfiedPaId().value() + ".");
            }
            if (wantsDocumentation(view)) {
                out.add(prefix + view.docNeeded().display() + ", " + purpose(view) + ".");
            }
            List<String> wanted = view.infoNeeded().stream().filter(i -> i.code() != null).map(i -> lower(i.display())).toList();
            if (!wanted.isEmpty()) {
                out.add(prefix + "The payer needs to know: " + String.join("; ", wanted) + ".");
            }
            if (view.questionnaires().stream().anyMatch(Shown::hasValue)) {
                out.add(prefix + "A payer questionnaire is named for this service.");
            }
            if (view.responses().stream().anyMatch(Shown::hasValue)) {
                out.add(prefix + "A partly completed questionnaire response is named for DTR to start from: "
                        + String.join(", ", view.responses().stream().filter(Shown::hasValue).map(Shown::value).toList()) + ".");
            }
            List<String> reasons = view.reasons().stream().filter(Shown::hasValue).map(CrdVerdicts::reason).toList();
            if (!reasons.isEmpty()) {
                out.add(prefix + "Reason the payer gives: " + String.join("; ", reasons) + ".");
            }
        }
        return out;
    }

    /**
     * A reason as the verdict states it: as returned, with its meaning for a code this tool can
     * read. A CRD 2.0.1 code gets its 2.0.1 meaning, and a later version's code that version's,
     * named as such.
     */
    private static String reason(Shown reason) {
        String value = reason.value();
        if (value.contains(OnyxCrdConventions.REASON_GOLD_CARD)) {
            return value + " (" + CrdCodes.JUDGED_BY + " gold-card: the ordering practitioner has been granted gold card status with "
                    + "this payer or coverage type)";
        }
        if (value.contains(OnyxCrdConventions.REASON_DETAIL_CODE)) {
            return value + " (" + CrdCodes.JUDGED_BY + " detail-code: the ordered code is too general for the payer to decide "
                    + "coverage or prior authorization, and a more detailed code is needed)";
        }
        for (String later : List.of(OnyxCrdConventions.LATER_NO_MEMBER_FOUND, OnyxCrdConventions.LATER_NO_ACTIVE_COVERAGE,
                OnyxCrdConventions.LATER_AUTH_OUT_NETWORK)) {
            if (value.contains(later)) {
                return value + " (a " + CrdCodes.LATER + " code, not in " + CrdCodes.JUDGED_BY + ": " + CrdGuideRules.laterReasonMeaning(later) + ")";
            }
        }
        return value;
    }

    /** True when covered, pa-needed or doc-needed is a recognised conditional, which is when {@link #facets} spells the answer out element by element. */
    private static boolean partlyConditional(CoverageInformationView view) {
        return conditional(view.covered()) || conditional(view.paNeeded()) || conditional(view.docNeeded());
    }

    private static boolean conditional(CodedValue value) {
        return value.code() != null && value.recognised() && value.is("conditional");
    }

    /** Puts one element of an answer under definite or conditional. An element not returned, or not recognised, goes under neither. */
    private static void sort(CodedValue value, String name, List<String> definite, List<String> conditional) {
        if (value.code() == null || !value.recognised()) {
            return;
        }
        if (value.is("conditional")) {
            conditional.add(name);
        } else {
            definite.add(name + ": " + lower(value.display()));
        }
    }

    /** What the documentation an answer asks for is for, in words, from its doc-purpose codes. */
    static String purpose(CoverageInformationView view) {
        List<String> purposes = new ArrayList<>();
        for (CodedValue value : view.docPurpose()) {
            if (value.code() == null) {
                continue;
            }
            String words = switch (value.code()) {
                case "withpa" -> "to go with the prior authorization request";
                case "withclaim" -> "to go with the claim";
                case "withorder" -> "to go with the order";
                case "retain-doc" -> "to keep in the record as evidence of medical necessity";
                case "OTH" -> "for a purpose the payer did not code";
                default -> "for a purpose this tool does not recognise (" + value.code() + ")";
            };
            if (!purposes.contains(words)) {
                purposes.add(words);
            }
        }
        return purposes.isEmpty() ? "and the response does not say what it is for (no doc-purpose)" : String.join(" and ", purposes);
    }

    /**
     * True when every difference between the answers is a difference between coverages: within each
     * coverage the answers agree and no order went unanswered.
     */
    private static boolean differByCoverageOnly(CrdReading reading) {
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        if (!reading.unanswered().isEmpty()) {
            return false;
        }
        Map<String, Set<CrdOutcome>> byCoverage = new LinkedHashMap<>();
        for (CoverageInformationView view : coverage) {
            byCoverage.computeIfAbsent(Texts.orElse(view.coverage().orNull(), ""), key -> new LinkedHashSet<>()).add(view.outcome());
        }
        return byCoverage.size() > 1 && byCoverage.values().stream().allMatch(outcomes -> outcomes.size() == 1);
    }

    /**
     * What the tester should do next, tied to what the response said. The chain uses this to offer
     * the next step, and to say when the journey ends, which is itself a finding worth recording.
     */
    public static NextStepView nextStep(CrdReading reading, VendorExchange exchange) {
        return nextStep(reading, exchange, null);
    }

    /**
     * The next step, worded with the id of the run it belongs to where the tester is asked to
     * record or report that run. Null when there is no run, as for a preview.
     */
    public static NextStepView nextStep(CrdReading reading, VendorExchange exchange, String runId) {
        return nextStep(reading, exchange, runId, null);
    }

    /**
     * The next step, with the name of the environment the call went to, so a refusal that a setting
     * of that environment answers can name where to change it. Null when no environment is active.
     */
    public static NextStepView nextStep(CrdReading reading, VendorExchange exchange, String runId, String environmentName) {
        return nextStep(reading, exchange, runId, environmentName, false);
    }

    /**
     * As above, knowing whether the vendor's sample request the call was sent from states a
     * fhirServer, which the advice after a refusal that names fhirServer then says (D-308).
     */
    public static NextStepView nextStep(CrdReading reading, VendorExchange exchange, String runId, String environmentName,
                                        boolean sampleStatesFhirServer) {
        // A fault a scenario played is a rehearsal on every branch, the readable answer of the wrong kind included.
        return PlayedFaults.rehearsed(exchange, reading.outcome(), asIfReal(reading, exchange, runId, environmentName, sampleStatesFhirServer),
                PlayedFaults.aboutTheEnvironment("the member or the code"));
    }

    /** The next step as it would be after a real service gave this answer. */
    private static NextStepView asIfReal(CrdReading reading, VendorExchange exchange, String runId, String environmentName,
                                         boolean sampleStatesFhirServer) {
        CrdOutcome outcome = reading.outcome();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();

        // Everything the next step starts from. Nothing is reduced to its first value here: a
        // response can name several questionnaires, and which one to open is the next step's choice.
        List<String> questionnaires = new ArrayList<>();
        List<String> assertionIds = new ArrayList<>();
        List<String> orders = new ArrayList<>();
        List<String> vendors = new ArrayList<>();
        List<String> purposes = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        List<String> information = new ArrayList<>();
        boolean performerOrLocation = false;
        for (CoverageInformationView view : coverage) {
            view.questionnaires().stream().filter(Shown::hasValue).map(Shown::value).forEach(q -> addOnce(questionnaires, q));
            if (wantsDocumentation(view) || view.questionnaires().stream().anyMatch(Shown::hasValue)) {
                // Only from the answers DTR is for, so the first value is the one DTR needs.
                if (view.coverageAssertionId().hasValue()) {
                    addOnce(assertionIds, view.coverageAssertionId().value());
                }
                if (view.appliesTo().hasValue()) {
                    addOnce(orders, view.appliesTo().value());
                }
                view.docPurpose().stream().filter(p -> p.code() != null).forEach(p -> addOnce(purposes, p.code()));
                view.responses().stream().filter(Shown::hasValue).forEach(r -> addOnce(responses, r.value()));
            }
            for (CodedValue info : view.infoNeeded()) {
                if (info.code() != null) {
                    addOnce(information, lower(info.display()));
                    performerOrLocation |= info.is("performer") || info.is("location");
                }
            }
            if (Texts.present(view.delegatedVendor())) {
                addOnce(vendors, view.delegatedVendor());
            }
        }
        CardLinkView smartLink = reading.detail().cards().stream().map(CardView::links).flatMap(List::stream)
                .filter(link -> "smart".equalsIgnoreCase(link.type().orNull())).findFirst().orElse(null);
        Map<String, List<String>> carry = new LinkedHashMap<>();
        carry.put(NextStepView.CARRY_PROCEDURE_CODES, reading.requestedCodes());
        carry.put(NextStepView.CARRY_QUESTIONNAIRE, questionnaires);
        carry.put(NextStepView.CARRY_COVERAGE_ASSERTION_ID, assertionIds);
        carry.put(NextStepView.CARRY_ORDER, orders);
        carry.put(NextStepView.CARRY_DOC_PURPOSE, purposes);
        carry.put(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE_REF, responses);
        if (smartLink != null) {
            // Carried as what it is, a SMART link, and only the first, so the url and the appContext
            // belong to one link. It does not decide whether DTR is next: doc-needed does.
            carry.put(NextStepView.CARRY_DTR_LINK_URL, smartLink.url().hasValue() ? List.of(smartLink.url().value()) : List.of());
            carry.put(NextStepView.CARRY_DTR_APP_CONTEXT,
                    smartLink.appContext().hasValue() ? List.of(smartLink.appContext().value()) : List.of());
        }
        carry.put(NextStepView.CARRY_DELEGATED_VENDOR, vendors);
        carry.put(NextStepView.CARRY_COVERAGE_INFORMATION, coverageInformation(coverage, exchange));

        String vendor = vendors.isEmpty() ? null : String.join(" and ", vendors);
        String asked = information.isEmpty() ? null : "The payer also asks for: " + String.join("; ", information) + "."
                + (performerOrLocation ? " " + OWN_REQUEST : "");

        if (DTR_FOLLOWS.contains(outcome)) {
            List<CoverageInformationView> documentation = coverage.stream().filter(CrdVerdicts::wantsDocumentation).toList();
            if (!documentation.isEmpty()) {
                StringBuilder reason = new StringBuilder("The response asks for documentation");
                List<String> forWhat = documentation.stream().map(CrdVerdicts::purpose).distinct().toList();
                reason.append(", ").append(String.join("; ", forWhat)).append(". In ").append(CrdCodes.JUDGED_BY)
                        .append(" a doc-needed answer is itself what says DTR is to be used, whatever pa-needed says.");
                if (!responses.isEmpty()) {
                    reason.append(" A partly completed questionnaire response is named for DTR to start from.");
                } else if (questionnaires.isEmpty()) {
                    reason.append(" No questionnaire is named, so DTR asks the payer for its package by the order and the coverage.");
                }
                if (asked != null) {
                    reason.append(" ").append(asked);
                }
                return new NextStepView(NextAction.START_DTR, "Next: complete the payer's documentation in DTR.", reason.toString(), carry);
            }
            if (!information.isEmpty()) {
                return new NextStepView(NextAction.SUPPLY_INFORMATION, "Next: supply what the payer asked for and send CRD again.",
                        "The payer cannot give a firm answer from what it was sent. " + asked, carry);
            }
        }

        // A readable answer of the wrong kind came from a service that was reached and answered, so it
        // is an answer to report, as "no determination" is, and not a finding about the environment.
        CrdResponseKind kind = reading.detail().responseKind();
        if (outcome == CrdOutcome.MALFORMED_RESPONSE && exchange.ok()
                && (kind == CrdResponseKind.OTHER_JSON || kind == CrdResponseKind.OPERATION_OUTCOME)) {
            String which = Texts.present(runId) ? "run " + runId : "this run";
            return new NextStepView(NextAction.REPORT_NO_DETERMINATION, "Next: record " + which + " as \"no determination returned\" "
                    + "and report it to the people who look after the service.",
                    "The service was reached and answered, but not with a CDS Hooks response, so the answer does not say whether "
                            + "prior authorization is needed. What came back is in the response detail below.", carry);
        }
        return switch (outcome) {
            case DOCUMENTATION_REQUIRED -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    "The response asks for documentation in a way this tool cannot act on.", carry);
            case AUTH_REQUIRED -> new NextStepView(NextAction.START_PAS, "Next: choose Continue in PAS, then Send PAS request.",
                    authRequiredReason(coverage, vendor), carry);
            case NO_AUTH_REQUIRED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here: nothing further to send.",
                    "The payer answered that prior authorization is not required, so there is nothing to submit.", carry);
            case AUTH_SATISFIED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here: nothing further to send.",
                    "The payer answered that the requirements prior authorization would check are already met, so prior "
                            + "authorization can be bypassed and there is nothing to submit.", carry);
            case NOT_COVERED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here: nothing further to send.",
                    "The service is not a covered benefit, so there is no authorization to request.", carry);
            case COVERAGE_INACTIVE -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here: nothing further to send.",
                    "The member has no active coverage on the date of service, so there is no authorization to request.", carry);
            case PERFORMER_AUTH -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here for the ordering provider: nothing further to send.",
                    "The payer answered that the performing provider, not the ordering provider, must obtain the authorization.", carry);
            case INFORMATION_NEEDED, CONDITIONAL -> new NextStepView(NextAction.SUPPLY_INFORMATION,
                    "Next: supply what the payer asked for and send CRD again.",
                    "The payer cannot give a firm answer from what it was sent.", carry);
            case MEMBER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the member ID against the test data.",
                    "The service does not recognise the member. The environment answered normally.", carry);
            case PROVIDER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the NPI against the test data.",
                    "The service does not recognise the provider. The environment answered normally.", carry);
            case REJECTED -> notAccepted(exchange, carry, environmentName, sampleStatesFhirServer);
            case SERVICE_ERROR -> refusedForFhirServer(exchange)
                    ? new NextStepView(NextAction.OPEN_CONFIG, setFhirServer(sampleStatesFhirServer), FHIR_SERVER_REASON, carry)
                    : new NextStepView(NextAction.CHECK_ENVIRONMENT, "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the member or the code.", carry);
            case TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> new NextStepView(NextAction.CHECK_ENVIRONMENT,
                    "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the member or the code.", carry);
            case NO_DETERMINATION -> noDetermination(runId, carry);
            case AUTH_NOT_STATED -> recordAndReport(runId, "authorization not stated", "The response does not say whether prior "
                    + "authorization is needed, and this tool does not guess. What came back is in the response detail below.", carry);
            case UNRECOGNISED_CODE -> recordAndReport(runId, "authorization code not recognised", "The response carries an authorization "
                    + "code this tool does not know the meaning of, and this tool does not guess what it means. The code is in the response "
                    + "detail below.", carry);
            // A lawful answer, not a defect to report: each code already has its own answer to act on.
            case MIXED -> new NextStepView(NextAction.REVIEW, "Next: read the answer for each code in Determination below; each code needs "
                    + "the next step its own answer gives.", "Not every answer in the response is the same, so one next step cannot cover "
                    + "them all.", carry);
        };
    }

    /**
     * The next step when the service answered with nothing this tool can act on, in the words PAS
     * uses for the same situation. It judges nothing about the test: it says what to record the run
     * as and who should hear about it. The action stays REVIEW, so no button or carry changes.
     *
     * @param recordAs what the run is recorded as, in a few plain words
     */
    private static NextStepView recordAndReport(String runId, String recordAs, String reason, Map<String, List<String>> carry) {
        String which = Texts.present(runId) ? "run " + runId : "this run";
        return new NextStepView(NextAction.REVIEW, "Next: record " + which + " as \"" + recordAs + "\" and report it to the people who "
                + "look after the service.", reason, carry);
    }

    /**
     * The next step when the service answered and returned no determination. The headline is for
     * the business tester, who can act on the result without reading the response; the reason
     * keeps the way to the detail for whoever looks into it. Neither says whose fault it is or
     * whether authorization is needed, because the answer says neither.
     */
    private static NextStepView noDetermination(String runId, Map<String, List<String>> carry) {
        String which = Texts.present(runId) ? "run " + runId : "this run";
        return new NextStepView(NextAction.REPORT_NO_DETERMINATION, "Next: record " + which + " as \"no determination returned\" and "
                + "report it to the people who look after the service.",
                "The answer does not say whether prior authorization is needed, and this tool does not guess. What came back is in "
                        + "the response detail below.", carry);
    }

    /**
     * Why PAS is next when prior authorization is required. A doc-needed of conditional is not a
     * "no": CRD 2.0.1 defines it as a decision that cannot be made without more information, so
     * the reason says documentation may still be asked for.
     */
    private static String authRequiredReason(List<CoverageInformationView> coverage, String vendor) {
        boolean documentationConditional = coverage.stream().anyMatch(view -> conditional(view.docNeeded()));
        String base = vendor != null ? "Prior authorization is required and routes to " + vendor + "." : null;
        if (documentationConditional) {
            return (base != null ? base : "Prior authorization is required.") + " Documentation may be needed once the payer has more "
                    + "information: doc-needed is conditional.";
        }
        return base != null ? base : "Prior authorization is required and no documentation is asked for first.";
    }

    /**
     * The next step when the service answered with a 4xx status. The body is not always at fault:
     * the same outcome covers credentials that were not accepted and an address that does not
     * exist, and sending the tester to correct the request for either would waste their time.
     */
    private static NextStepView notAccepted(VendorExchange exchange, Map<String, List<String>> carry, String environmentName,
                                            boolean sampleStatesFhirServer) {
        int status = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        if (status == 400 && refusedDraftOrdersType(exchange)) {
            return new NextStepView(NextAction.OPEN_CONFIG, leaveOutDraftOrdersType(environmentName), DRAFT_ORDERS_TYPE_REASON, carry);
        }
        if (status != 401 && status != 403 && refusedForFhirServer(exchange)) {
            return new NextStepView(NextAction.OPEN_CONFIG, setFhirServer(sampleStatesFhirServer), FHIR_SERVER_REASON, carry);
        }
        if (status == 401 || status == 403) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials in Configuration > Environments > Sign-in.",
                    RunSupport.credentialsReason(status, exchange), carry);
        }
        if (status == 404) {
            return new NextStepView(NextAction.OPEN_CONFIG,
                    "Next: check the CRD url and the service id in Configuration > Environments > Addresses. Discovery lists the services this environment offers.",
                    "The service answered HTTP 404 and did not say the member or the provider is unknown, so the address is the "
                            + "likely cause.", carry);
        }
        return new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                "The service did not accept the request as it was sent.", carry);
    }

    /** Why the next step after a refusal that names fhirServer points at the environment's value (D-308). */
    static final String FHIR_SERVER_REASON = "The service refused the check and its answer names fhirServer, which this request did not "
            + "send. PAVE sends fhirServer only when the environment's value FHIR server is set.";

    /** The next step's headline after a refusal that names fhirServer, in the words of D-308. */
    static String setFhirServer(boolean sampleStatesOne) {
        return "Set FHIR server in Configuration > Environments > Edit > Values." + (sampleStatesOne
                ? " The vendor's sample request states one." : "");
    }

    /**
     * True when the service's answer names fhirServer and the request as sent carried none. A
     * request that sent one is not pointed at the value: the refusal is then about something
     * else, and the issues below say what. Read as the draftOrders refusal is read, by the text of
     * the answer, since a vendor may say it in a card's detail or in an issue.
     */
    static boolean refusedForFhirServer(VendorExchange exchange) {
        String answer = exchange.responseBody();
        if (answer == null || !answer.contains("fhirServer")) {
            return false;
        }
        String sent = exchange.request() == null ? null : exchange.request().body();
        if (sent == null) {
            return false;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(sent);
        return parsed.ok() && JsonSafe.isObject(parsed.node()) && JsonSafe.presentText(parsed.node(), "fhirServer") == null;
    }

    /**
     * Whether the saved body of the collection's request a call was sent from states a fhirServer,
     * so the advice after a refusal for one can say the vendor's sample has it (D-308). It reads a
     * sample the tester stored, never an answer.
     */
    static boolean statesFhirServer(String body) {
        if (!Texts.present(body)) {
            return false;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(VendorSamples.placeholdersAsNull(body));
        return parsed.ok() && JsonSafe.presentText(parsed.node(), "fhirServer") != null;
    }

    /** Why the next step after a 400 that names draftOrders.type points at the environment's setting (R-37). */
    static final String DRAFT_ORDERS_TYPE_REASON = "The service answered HTTP 400 and its answer names draftOrders.type, which this "
            + "request sent. FHIR requires Bundle.type, and the setting draftOrders type of the environment leaves it out for a vendor "
            + "whose sandbox refuses it.";

    /** The next step's headline after a 400 that names draftOrders.type, in the words of R-37. */
    static String leaveOutDraftOrdersType(String environmentName) {
        String name = Texts.present(environmentName) ? environmentName.strip() : "the active environment";
        return "Set draftOrders type to Left out for " + name + " in Configuration > Environments, then run the check again.";
    }

    /**
     * True when the service's answer names draftOrders.type and the request as sent carried it.
     * A request that already left it out is not sent to the setting that leaves it out: the 400 is
     * then about something else, and the issues below say what.
     */
    static boolean refusedDraftOrdersType(VendorExchange exchange) {
        String answer = exchange.responseBody();
        if (answer == null || !answer.contains("draftOrders.type")) {
            return false;
        }
        String sent = exchange.request() == null ? null : exchange.request().body();
        return RequestShape.SENT.equals(VendorSamples.fromBody(RequestShape.CRD_DRAFT_ORDERS_TYPE, sent));
    }

    /** True when the answer asks for documentation: doc-needed clinical, admin or both, or the 2.2.0 ballot's patient. */
    static boolean wantsDocumentation(CoverageInformationView view) {
        return view.docNeeded().code() != null && CrdCodes.DOCUMENTATION_WANTED.contains(view.docNeeded().code());
    }

    private static String prefix(CoverageInformationView view, boolean named) {
        return named ? view.subject() + ": " : "";
    }

    /**
     * The coverage-information extensions to hand on to DTR, one value per answer tied to an order:
     * {@code {"order": "<reference>", "extension": {...}}} with the extension exactly as received,
     * so DTR puts it back on the order it sends without asserting anything the payer did not say.
     * An answer that names no order, or whose place in the response is not a FHIR extension
     * object, is not handed on: rebuilding it would be this tool's own statement of coverage.
     */
    static List<String> coverageInformation(List<CoverageInformationView> coverage, VendorExchange exchange) {
        List<String> out = new ArrayList<>();
        // Read through the masks, as the view is: a secret the service repeats is handed on as its mask, which the next step refuses.
        JsonNode root = exchange == null ? null : exchange.withReturnedSecretsMasked().json();
        if (root == null) {
            return out;
        }
        for (CoverageInformationView view : coverage) {
            if (!view.appliesTo().hasValue()) {
                continue;
            }
            JsonNode extension = at(root, view.foundAt());
            if (extension == null || !extension.isObject()
                    || !CrdCodes.COVERAGE_INFORMATION.equals(JsonSafe.text(extension, "url"))) {
                continue;
            }
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("order", view.appliesTo().value());
            value.set("extension", extension.deepCopy());
            addOnce(out, value.toString());
        }
        return out;
    }

    /**
     * The node at a place in the response as the mapper names it, such as
     * {@code systemActions[0].resource.extension[1]}, or null when the path does not lead to one.
     * Read one character at a time, so its cost is the length of the path.
     */
    static JsonNode at(JsonNode root, String path) {
        if (Texts.blank(path)) {
            return null;
        }
        JsonNode node = root;
        int i = 0;
        int length = path.length();
        while (i < length && node != null) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                int index = 0;
                for (int d = i + 1; d < close; d++) {
                    char digit = path.charAt(d);
                    if (digit < '0' || digit > '9' || index > 100_000) {
                        return null;
                    }
                    index = index * 10 + (digit - '0');
                }
                node = node.isArray() && index < node.size() ? node.get(index) : null;
                i = close + 1;
            } else {
                int end = i;
                while (end < length && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                node = node.isObject() ? node.get(path.substring(i, end)) : null;
                i = end;
            }
        }
        return node;
    }

    private static void addOnce(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    /**
     * What came, for an answer that is not what the hook returns. A Patient reads perfectly well,
     * so it is not said to be a body that could not be read: it is said to be what it says it is.
     */
    private static String malformedHeadline(CrdReading reading, VendorExchange exchange) {
        CrdResponseKind kind = reading.detail().responseKind();
        if (kind == CrdResponseKind.NO_BODY) {
            return "The service answered with no body" + status(exchange) + ".";
        }
        if (kind == CrdResponseKind.OPERATION_OUTCOME) {
            // Kept short: a headline over 120 characters is announced by the outcome's label instead.
            return "The service answered with a FHIR OperationOutcome, not a CDS Hooks response" + status(exchange) + ".";
        }
        if (kind != CrdResponseKind.OTHER_JSON) {
            return "The service answered with a body that could not be read" + status(exchange) + ".";
        }
        String type = exchange.json() == null ? null : JsonSafe.presentText(exchange.json(), "resourceType");
        // Only a name shaped like a FHIR resource type goes into the headline; any other text stays among the returned fields.
        return type != null && RESOURCE_TYPE.matcher(type).matches()
                ? "The service answered with a FHIR " + type + " resource, not a CDS Hooks response" + status(exchange) + "."
                : "The service answered with JSON that is not a CDS Hooks response" + status(exchange) + ".";
    }

    private static final Pattern RESOURCE_TYPE = Pattern.compile("[A-Z][A-Za-z]{1,63}");

    private static String status(VendorExchange exchange) {
        return exchange.httpStatus() == null ? "" : " (HTTP " + exchange.httpStatus() + ")";
    }

    private static String lower(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static List<String> dedupe(List<String> lines) {
        Set<String> unique = new LinkedHashSet<>(lines);
        return List.copyOf(unique);
    }
}
