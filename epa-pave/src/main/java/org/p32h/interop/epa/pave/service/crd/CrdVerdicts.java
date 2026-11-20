package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.OnyxCrdConventions;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
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
            case MALFORMED_RESPONSE -> reading.detail().responseKind() == CrdResponseKind.NO_BODY
                    ? "The service answered with no body" + status(exchange) + "."
                    : "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        if (outcome.fault() && Texts.present(exchange.failureDetail())) {
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

        return switch (outcome) {
            case DOCUMENTATION_REQUIRED -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    "The response asks for documentation in a way this tool cannot act on.", carry);
            case AUTH_REQUIRED -> new NextStepView(NextAction.START_PAS, "Next: submit the prior authorization request in PAS.",
                    authRequiredReason(coverage, vendor), carry);
            case NO_AUTH_REQUIRED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer answered that prior authorization is not required, so there is nothing to submit.", carry);
            case AUTH_SATISFIED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer answered that the requirements prior authorization would check are already met, so prior "
                            + "authorization can be bypassed and there is nothing to submit.", carry);
            case NOT_COVERED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The service is not a covered benefit, so there is no authorization to request.", carry);
            case COVERAGE_INACTIVE -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The member has no active coverage on the date of service, so there is no authorization to request.", carry);
            case PERFORMER_AUTH -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here for the ordering provider. Record this result.",
                    "The payer answered that the performing provider, not the ordering provider, must obtain the authorization.", carry);
            case INFORMATION_NEEDED, CONDITIONAL -> new NextStepView(NextAction.SUPPLY_INFORMATION,
                    "Next: supply what the payer asked for and send CRD again.",
                    "The payer cannot give a firm answer from what it was sent.", carry);
            case MEMBER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the member ID against the test data.",
                    "The service does not recognise the member. The environment answered normally.", carry);
            case PROVIDER_NOT_FOUND -> new NextStepView(NextAction.FIX_TEST_DATA, "Next: check the NPI against the test data.",
                    "The service does not recognise the provider. The environment answered normally.", carry);
            case REJECTED -> notAccepted(exchange, carry);
            case SERVICE_ERROR, TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> new NextStepView(NextAction.CHECK_ENVIRONMENT,
                    "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the member or the code.", carry);
            case NO_DETERMINATION, AUTH_NOT_STATED, UNRECOGNISED_CODE, MIXED -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    outcome == CrdOutcome.MIXED ? "Not every answer in the response is the same, so each needs its own next step."
                            : "The response does not carry an authorization answer this tool can act on.", carry);
        };
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
    private static NextStepView notAccepted(VendorExchange exchange, Map<String, List<String>> carry) {
        int status = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        if (status == 401 || status == 403) {
            return new NextStepView(NextAction.OPEN_CONFIG, "Next: check the credentials on the Config tab.",
                    "The service answered HTTP " + status + ", which means it did not accept the credentials this tool sent, or none "
                            + "were sent. The request itself may be fine.", carry);
        }
        if (status == 404) {
            return new NextStepView(NextAction.OPEN_CONFIG,
                    "Next: check the CRD url and the service id on the Config tab. Discovery lists the services this environment offers.",
                    "The service answered HTTP 404 and did not say the member or the provider is unknown, so the address is the "
                            + "likely cause.", carry);
        }
        return new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                "The service did not accept the request as it was sent.", carry);
    }

    /** True when the answer asks for documentation: doc-needed clinical, admin or both, or the 2.2.0 ballot's patient. */
    static boolean wantsDocumentation(CoverageInformationView view) {
        return view.docNeeded().code() != null && CrdCodes.DOCUMENTATION_WANTED.contains(view.docNeeded().code());
    }

    private static String prefix(CoverageInformationView view, boolean named) {
        return named ? view.subject() + ": " : "";
    }

    private static void addOnce(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

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
