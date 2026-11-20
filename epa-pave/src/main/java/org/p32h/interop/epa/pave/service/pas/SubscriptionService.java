package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SubscriptionClient;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * One subscription call, end to end, in the order every protocol follows: validate the form, ask
 * the gateway where the call goes and whether it may go, build the body, call the service, read
 * the answer, write the ledger record, return the result.
 *
 * <p>Create, update and delete are writes. On a production environment the gateway refuses each
 * unless the session holds an unlock, and that refusal is returned as a result that says nothing
 * was sent. A read and a list are reads and are allowed on production.
 *
 * <p>Every call is a run with its own ledger line. The line of a create carries the id the service
 * gave the subscription, and that is how a notification is later matched to the run that created
 * the subscription it arrived on.
 */
@Service
public class SubscriptionService {

    private static final Set<EpaOperation> OPERATIONS = Set.of(EpaOperation.SUBSCRIPTION_CREATE, EpaOperation.SUBSCRIPTION_GET,
            EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE);

    /** What a subscription id may look like. It is written to the ledger as it stands, so it is one token and never free text. */
    private static final Pattern SUBSCRIPTION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    /** The name of the single-brace token the gateway fills with the id. */
    private static final String ID_TOKEN = "id";

    private final OperationGateway gateway;
    private final SubscriptionClient client;
    private final RunSupport support;
    private final SubscriptionRequestBuilder builder;
    private final SubscriptionResponseMapper mapper = new SubscriptionResponseMapper();

    /** @param onyx read for the url of this tool's receiver, which a subscription names as its endpoint */
    public SubscriptionService(OnyxProperties onyx, OperationGateway gateway, SubscriptionClient client, RunSupport support) {
        this.gateway = gateway;
        this.client = client;
        this.support = support;
        this.builder = new SubscriptionRequestBuilder(onyx.subscription().callbackUrl());
    }

    /**
     * Runs one subscription call.
     *
     * @param operation create, get, update or delete. A get with no subscription id on the request lists
     * @param unlock    the session's production unlock, or null. A suite run or a replay always passes null
     */
    public RunEnvelope<SubscriptionDetail> run(EpaOperation operation, SubscriptionRequest request, RunOrigin origin, ProdUnlock unlock) {
        validate(operation, request);
        String id = Texts.trimToNull(request.subscriptionId());
        boolean sendsBody = sendsBody(operation);
        boolean ownBody = sendsBody && RunSupport.ownBody(request);

        // What the request itself says the run is about: the subscription it names and the run it
        // follows from. Settled before the gateway is asked, so that a run the guardrail refuses
        // names them on its ledger line exactly as a run that was sent does.
        String carriedRunId = first(request, NextStepView.CARRY_RUN_ID);
        Map<String, String> known = new LinkedHashMap<>();
        if (id != null) {
            known.put(RunRecord.CORR_SUBSCRIPTION_ID, id);
        }
        if (carriedRunId != null) {
            known.put(RunRecord.CORR_RUN_ID, carriedRunId);
        }

        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, id, unlock, origin));
        } catch (ProdWriteBlockedException refusal) {
            return support.blocked(operation, refusal, request, origin, known);
        }

        ResolvedText body = null;
        List<String> requestNotes = new ArrayList<>();
        String callbackUrl = null;
        String callbackProblem = null;
        List<String> leftOut = List.of();
        if (ownBody) {
            body = SubscriptionRequestBuilder.ownBody(call.resolve(request.rawBody()));
            JsonSafe.Parsed parsed = JsonSafe.parse(body.value());
            if (body.resolved() && !parsed.ok()) {
                // The tester's body is sent as written, valid or not. They are told, so it cannot happen unnoticed.
                requestNotes.add("The body you supplied is not valid JSON once its placeholders are filled in. It was sent exactly as "
                        + "written. " + parsed.error());
            } else if (body.resolved()) {
                // As it may be shown: the receiver's address can carry a secret, and this goes on the result.
                callbackUrl = SubscriptionRequestBuilder.shownEndpoint(SubscriptionRequestBuilder.endpointOf(body.display()));
                callbackProblem = callbackUrl != null ? null : "The body you supplied names no channel.endpoint. Add the address at which "
                        + "the service can reach this tool's receiver, which ends in /api/pas/notifications, and send again.";
            }
        } else if (sendsBody) {
            SubscriptionRequestBuilder.Built built = builder.build(id, call::resolve);
            body = built.body();
            requestNotes.addAll(built.notes());
            callbackUrl = built.callbackUrl();
            callbackProblem = built.callbackProblem();
            leftOut = built.leftOut();
        }

        MockSelection selection = new MockSelection(request.scenarioId(), null, null,
                id == null ? Map.of() : Map.of(MockSubscriptionClient.HINT_SUBSCRIPTION_ID, id));
        VendorExchange exchange = switch (operation) {
            case SUBSCRIPTION_CREATE -> client.create(call, body, selection);
            case SUBSCRIPTION_UPDATE -> client.update(call, body, selection);
            case SUBSCRIPTION_DELETE -> client.delete(call, selection);
            default -> client.get(call, selection);
        };

        // From here on the call has been made, so whatever happens next the run is recorded. A later
        // step is linked to a create by its run id, so the id is settled now.
        String runId = RunStamps.newRunId();
        RunRecord.Builder draft = support.fill(support.draft(operation, call, exchange, origin), request).runId(runId).correlation(known);
        String followsRunId = operation == EpaOperation.SUBSCRIPTION_CREATE ? runId : carriedRunId;
        Interpreted read;
        try {
            // Only the reading and wording of the response is inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one run.
            read = interpret(request, operation, call, exchange, id, known, followsRunId, sendsBody, ownBody, callbackProblem, leftOut,
                    callbackUrl);
        } catch (RuntimeException e) {
            // The service was called and this tool then failed to read its answer. The run still
            // happened: it gets its line, and the tester still gets the request and the response.
            return support.toolError(operation, call, exchange, draft, request, e);
        }

        RunRecord record = draft
                .outcome(read.reading().outcome())
                .coverageCodes(read.reading().ledgerCodes())
                .result(read.expectation().result())
                .nextAction(read.nextStep().action())
                .correlation(read.correlation())
                .findings(read.findings().stream().map(f -> f.kind().name()).distinct().toList())
                .build();
        LedgerReceipt receipt = support.record(record);

        return RunEnvelope.<SubscriptionDetail>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(Protocol.SUBSCRIPTION)
                .operation(operation)
                .environment(call.environment())
                .profile(support.epa().mode())
                .verdict(read.verdict())
                .expectation(read.expectation())
                .nextStep(read.nextStep())
                .determination(SubscriptionVerdicts.determination(read.reading()))
                .detail(read.reading().detail().withRequest(read.subscriptionId(), read.idSource(), callbackUrl, requestNotes, !ownBody))
                .findings(read.findings())
                .additionalFields(read.reading().additionalFields())
                .wire(exchange.wire(null))
                .error(read.error())
                .ledger(receipt)
                .timing(read.timing())
                .build();
    }

    /** Everything worked out from one response, before anything is written. */
    private record Interpreted(SubscriptionReading reading, String subscriptionId, String idSource, List<Finding> findings, VerdictView verdict,
                               ExpectationView expectation, NextStepView nextStep, ErrorView error, Timing timing,
                               Map<String, String> correlation) {
    }

    private Interpreted interpret(SubscriptionRequest request, EpaOperation operation, PreparedCall call, VendorExchange exchange, String askedId,
                                  Map<String, String> known, String followsRunId, boolean sendsBody, boolean ownBody, String callbackProblem,
                                  List<String> leftOut, String callbackUrl) {
        SubscriptionReading reading = mapper.read(exchange, operation, askedId);
        SubscriptionOutcome outcome = reading.outcome();
        Timing timing = support.timing(exchange);

        // The id the run is about. For a create it is whatever the service gave the new subscription.
        String subscriptionId = askedId;
        String idSource = null;
        if (operation == EpaOperation.SUBSCRIPTION_CREATE && outcome == SubscriptionOutcome.CREATED) {
            SubscriptionView created = reading.detail().responseKind() == SubscriptionResponseKind.SUBSCRIPTION
                    ? reading.detail().subscriptions().get(0) : null;
            if (created != null && created.id().hasValue()) {
                subscriptionId = created.id().value();
                idSource = "the id of the Subscription the service returned";
            } else {
                subscriptionId = OnyxPasConventions.createdSubscriptionId(exchange);
                idSource = subscriptionId == null ? null : "the Location header of the response";
            }
        }

        List<Finding> findings = new ArrayList<>();
        if (!leftOut.isEmpty()) {
            // Said first, so that a refusal by the service is not read as a fault of the service.
            findings.add(new Finding(FindingKind.REQUEST_INCOMPLETE, "The subscription is not filtered by the requesting organization",
                    "PAS 2.0.1 says the subscription SHALL be filtered by the requesting provider organization, through the topic's filter "
                            + OnyxPasConventions.ORG_IDENTIFIER_FILTER + ". Nobody supplied that organization's identifier: set the variable "
                            + SubscriptionRequestBuilder.VAR_ORG_IDENTIFIER + ", and " + SubscriptionRequestBuilder.VAR_ORG_IDENTIFIER_SYSTEM
                            + " for its system. A refusal by the service may be about this and not a fault of the service.",
                    String.join("; ", leftOut), null, null));
        }
        if (sendsBody && callbackProblem != null) {
            // Its own kind: this is about what was sent, and no element is missing from the response.
            findings.add(new Finding(FindingKind.NO_CALLBACK_URL, "The subscription names no url to call back",
                    "The service has nowhere to send a notification, so none can arrive at this tool for this subscription. "
                            + callbackProblem + " Until then, follow a request with an inquiry: that path needs no callback.",
                    "channel.endpoint", null, null));
        }
        for (SubscriptionView view : reading.detail().subscriptions()) {
            unrecognised(findings, view, "subscription status", PasCodes.SUBSCRIPTION_STATUS, view.status());
            unrecognised(findings, view, "notification content", PasCodes.PAYLOAD_CONTENT, view.payloadContent());
        }
        addIfPresent(findings, support.scriptFinding(call));
        addIfPresent(findings, support.slowFinding(timing));

        // A body of the tester's own that could not be read names no address this tool knows, so only a readable one is set beside the answer.
        VerdictView verdict = SubscriptionVerdicts.verdict(reading, exchange, operation, subscriptionId, sendsBody && (!ownBody || callbackUrl != null),
                callbackUrl);
        ExpectationView expectation = Expectations.assess(request.expected(), outcome, List.of(), reading.answers(),
                SubscriptionOutcome.values(), PasCodes.SUBSCRIPTION_ELEMENTS);
        NextStepView nextStep = SubscriptionVerdicts.nextStep(reading, exchange, operation, subscriptionId, followsRunId,
                sendsBody ? callbackProblem : null, ownBody);
        // The error panel is for a fault. An id the service does not know is a finding about the
        // id and is presented as an answer, even though it arrived with an error status.
        ErrorView error = outcome.fault() ? support.faultView(exchange, reading.detail().issues()) : null;

        Map<String, String> correlation = new LinkedHashMap<>(known);
        if (Texts.present(subscriptionId)) {
            correlation.put(RunRecord.CORR_SUBSCRIPTION_ID, subscriptionId);
        }
        return new Interpreted(reading, subscriptionId, idSource, findings, verdict, expectation, nextStep, error, timing, correlation);
    }

    /** What a create or an update would send, without sending it. Nothing is recorded. */
    public RequestPreview preview(EpaOperation operation, SubscriptionRequest request, ProdUnlock unlock) {
        if (!sendsBody(operation)) {
            throw new IllegalArgumentException("Only a create and an update send a body, so only they have a preview.");
        }
        validate(operation, request);
        String id = Texts.trimToNull(request.subscriptionId());
        PreparedCall call;
        try {
            call = gateway.prepare(intent(operation, request, id, unlock, null));
        } catch (ProdWriteBlockedException refusal) {
            return RequestPreview.blocked(operation, refusal, null);
        }
        if (RunSupport.ownBody(request)) {
            ResolvedText resolved = SubscriptionRequestBuilder.ownBody(call.resolve(request.rawBody()));
            return RequestPreview.of(operation, call, resolved.resolved() ? resolved.display() : request.rawBody(), resolved.resolved() ? List.of()
                    : List.of("The body still contains placeholders with no value: " + String.join(", ", resolved.unresolved()) + "."), null);
        }
        SubscriptionRequestBuilder.Built built = builder.build(id, call::resolve);
        return RequestPreview.of(operation, call, built.shownJson(), PasNotes.asPreview(built.notes()), null);
    }

    private static boolean sendsBody(EpaOperation operation) {
        return operation == EpaOperation.SUBSCRIPTION_CREATE || operation == EpaOperation.SUBSCRIPTION_UPDATE;
    }

    /** @param origin where the run came from, so the gateway can word a refusal for a replay or a suite. Null for a preview */
    private static CallIntent intent(EpaOperation operation, SubscriptionRequest request, String id, ProdUnlock unlock, RunOrigin origin) {
        CallIntent intent = CallIntent.of(operation).withRequest(request.request()).withRunValues(request.variables()).withUnlock(unlock)
                .withExpectedEpoch(request.configEpoch()).withOrigin(origin);
        // The id reaches the url through the gateway, which encodes it as one path segment. With
        // no id a get lists, and a create addresses the collection.
        return id == null ? intent : intent.withUrlValue(ID_TOKEN, id);
    }

    private void validate(EpaOperation operation, SubscriptionRequest request) {
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(operation + " is not a subscription call.");
        }
        RunInputs inputs = new RunInputs(support.clock()).common(request);
        String id = Texts.trimToNull(request.subscriptionId());
        if (operation == EpaOperation.SUBSCRIPTION_CREATE) {
            inputs.require(id == null, "subscriptionId", "A create takes no id: the service assigns one. To change a subscription that "
                    + "exists, update it.");
        } else if (operation != EpaOperation.SUBSCRIPTION_GET) {
            inputs.require(id != null, "subscriptionId", "Name the subscription by its id.");
        }
        inputs.require(id == null || SUBSCRIPTION_ID.matcher(id).matches(), "subscriptionId",
                "A subscription id is one word of up to 64 letters, digits, dots, hyphens, underscores and colons. It is written to the "
                        + "ledger as it stands, so it cannot be free text.");
        inputs.require(sendsBody(operation) || !RunSupport.ownBody(request), "rawBody",
                "A read, a list and a delete send no body, so one cannot be supplied for them.");
        String runId = first(request, NextStepView.CARRY_RUN_ID);
        inputs.require(runId == null || PasService.isRunId(runId), "carry." + NextStepView.CARRY_RUN_ID,
                "The run this step follows from must be a run id this tool returned.");
        inputs.expectation(request.expected(), SubscriptionOutcome.values(), PasCodes.SUBSCRIPTION_ELEMENTS)
                .scenario(request.scenarioId(), support.epa())
                .throwIfInvalid();
    }

    /** The first present value an earlier step handed on under a key, or null. */
    private static String first(SubscriptionRequest request, String key) {
        List<String> values = request.carry().get(key);
        return values == null ? null : values.stream().filter(Texts::present).map(String::strip).findFirst().orElse(null);
    }

    private static void unrecognised(List<Finding> findings, SubscriptionView view, String what, String element, CodedValue value) {
        if (value.code() != null && !value.recognised()) {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "The " + what + " is not a code this tool knows",
                    "It is expected to be one of " + String.join(", ", PasCodes.codes(element)) + ". The code that came back is shown as it "
                            + "came and is not read as any of them.", view.foundAt(), null, value.code()));
        }
    }

    private static void addIfPresent(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }
}
