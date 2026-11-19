package org.p32h.interop.epa.pave.service.crd;

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

    private CrdVerdicts() {
    }

    public static VerdictView verdict(CrdReading reading, VendorExchange exchange) {
        CrdOutcome outcome = reading.outcome();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        List<String> supporting = new ArrayList<>();
        // A switch expression with no default: adding an outcome without wording it here does not compile.
        String headline = switch (outcome) {
            case AUTH_REQUIRED -> "Prior authorization is required.";
            case NO_AUTH_REQUIRED -> "Prior authorization is not required.";
            case AUTH_SATISFIED -> "Prior authorization is already satisfied.";
            case PERFORMER_AUTH -> "The performing provider must obtain prior authorization.";
            case DOCUMENTATION_REQUIRED -> {
                for (CoverageInformationView view : coverage) {
                    supporting.add("Prior authorization: " + (view.paNeeded().code() == null ? "not stated" : lower(view.paNeeded().display())) + ".");
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
            case AUTH_NOT_STATED -> "The service says the service is covered but does not say whether prior authorization is required.";
            case UNRECOGNISED_CODE -> {
                for (CoverageInformationView view : coverage) {
                    for (CodedValue value : List.of(view.covered(), view.paNeeded(), view.docNeeded())) {
                        if (value.code() != null && !value.recognised()) {
                            supporting.add(value.display() + ".");
                        }
                    }
                }
                yield "The service returned a code this tool does not recognise.";
            }
            case MIXED -> {
                for (CoverageInformationView view : coverage) {
                    supporting.add(Texts.orElse(view.appliesTo().orNull(), view.foundAt()) + ": " + lower(view.outcome().label()) + ".");
                }
                yield "The services in this order received different answers.";
            }
            case NO_DETERMINATION -> {
                supporting.add("This is not the same as an answer that prior authorization is not required. The service has said nothing either way.");
                yield "The service answered but returned no determination.";
            }
            case REJECTED -> "The service rejected the request" + status(exchange) + ".";
            case SERVICE_ERROR -> "The service failed" + status(exchange) + ".";
            case TIMEOUT -> "The service did not answer in time.";
            case UNREACHABLE -> "The service could not be reached.";
            case MALFORMED_RESPONSE -> "The service answered with a body that could not be read" + status(exchange) + ".";
        };
        if (outcome.fault() && Texts.present(exchange.failureDetail())) {
            supporting.add(exchange.failureDetail());
        }
        if (!outcome.fault() && outcome != CrdOutcome.MIXED) {
            supporting.addAll(facets(coverage));
        }
        return VerdictView.of(outcome, headline, dedupe(supporting));
    }

    /** What the codes say beyond the headline: the vendor, the documentation, the information wanted. */
    private static List<String> facets(List<CoverageInformationView> coverage) {
        List<String> out = new ArrayList<>();
        for (CoverageInformationView view : coverage) {
            if (Texts.present(view.delegatedVendor())) {
                out.add("It routes to the delegated vendor " + view.delegatedVendor() + ".");
            }
            if (view.satisfiedPaId().hasValue()) {
                out.add("Existing authorization: " + view.satisfiedPaId().value() + ".");
            }
            if (view.docNeeded().code() != null && !view.docNeeded().is("no-doc")) {
                StringBuilder sentence = new StringBuilder(view.docNeeded().display());
                List<String> purposes = view.docPurpose().stream().filter(p -> p.code() != null).map(p -> lower(p.display())).toList();
                if (!purposes.isEmpty()) {
                    sentence.append(": ").append(String.join(", ", purposes));
                }
                out.add(sentence + ".");
            }
            List<String> wanted = view.infoNeeded().stream().filter(i -> i.code() != null).map(i -> lower(i.display())).toList();
            if (!wanted.isEmpty()) {
                out.add("The payer needs to know: " + String.join("; ", wanted) + ".");
            }
            if (view.questionnaires().stream().anyMatch(Shown::hasValue)) {
                out.add("A payer questionnaire is named for this service.");
            }
        }
        return out;
    }

    /**
     * What the tester should do next, tied to what the response said. The chain uses this to offer
     * the next step, and to say when the journey ends, which is itself a finding worth recording.
     */
    public static NextStepView nextStep(CrdReading reading) {
        CrdOutcome outcome = reading.outcome();
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        Map<String, String> carry = new LinkedHashMap<>();
        String questionnaire = coverage.stream().flatMap(v -> v.questionnaires().stream()).filter(Shown::hasValue).map(Shown::value)
                .findFirst().orElse(null);
        CardLinkView dtrLink = reading.detail().cards().stream().map(CardView::links).flatMap(List::stream)
                .filter(link -> "smart".equalsIgnoreCase(link.type().orNull())).findFirst().orElse(null);
        if (questionnaire != null) {
            carry.put("questionnaire", questionnaire);
        }
        if (dtrLink != null) {
            if (dtrLink.url().hasValue()) {
                carry.put("dtrLinkUrl", dtrLink.url().value());
            }
            if (dtrLink.appContext().hasValue()) {
                carry.put("dtrAppContext", dtrLink.appContext().value());
            }
        }
        if (Texts.present(reading.delegatedVendor())) {
            carry.put("delegatedVendor", reading.delegatedVendor());
        }
        boolean documentationWanted = coverage.stream().anyMatch(v -> v.docNeeded().code() != null && !v.docNeeded().is("no-doc"));
        boolean dtrOffered = questionnaire != null || dtrLink != null;

        return switch (outcome) {
            case DOCUMENTATION_REQUIRED -> dtrOffered
                    ? new NextStepView(NextAction.START_DTR, "Next: answer the payer's questionnaire in DTR.",
                    "The response asks for documentation and names a questionnaire or a DTR link.", carry)
                    : new NextStepView(NextAction.START_PAS, "Next: submit the prior authorization request in PAS, with the documentation.",
                    "The response asks for documentation but names no questionnaire and carries no DTR link.", carry);
            case AUTH_REQUIRED -> dtrOffered && documentationWanted
                    ? new NextStepView(NextAction.START_DTR, "Next: answer the payer's questionnaire in DTR.",
                    "Prior authorization is required and the response names a questionnaire or a DTR link.", carry)
                    : new NextStepView(NextAction.START_PAS, "Next: submit the prior authorization request in PAS.",
                    Texts.present(reading.delegatedVendor())
                            ? "Prior authorization is required and routes to " + reading.delegatedVendor() + "."
                            : "Prior authorization is required and no documentation is asked for first.", carry);
            case NO_AUTH_REQUIRED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "The payer answered that prior authorization is not required, so there is nothing to submit.", carry);
            case AUTH_SATISFIED -> new NextStepView(NextAction.FLOW_ENDS, "The flow ends here. Record this result.",
                    "An authorization already on file covers this service, so there is nothing to submit.", carry);
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
            case REJECTED -> new NextStepView(NextAction.FIX_REQUEST, "Next: read the issues below and correct the request.",
                    "The service refused the request as it was sent.", carry);
            case SERVICE_ERROR, TIMEOUT, UNREACHABLE, MALFORMED_RESPONSE -> new NextStepView(NextAction.CHECK_ENVIRONMENT,
                    "Next: check the environment, then send again.",
                    "No usable answer came back. This is a finding about the environment, not about the member or the code.", carry);
            case NO_DETERMINATION, AUTH_NOT_STATED, UNRECOGNISED_CODE, MIXED -> new NextStepView(NextAction.REVIEW,
                    "Next: read the response detail below. This tool cannot say what should happen next.",
                    outcome == CrdOutcome.MIXED ? "The services in the order got different answers, so each needs its own next step."
                            : "The response does not carry an authorization answer this tool can act on.", carry);
        };
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
