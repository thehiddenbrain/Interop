package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.OutcomeOption;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.ScenarioList;
import org.p32h.interop.epa.pave.model.ScenarioSummary;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.pas.NotificationList;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.pas.NotificationReceiver;
import org.p32h.interop.epa.pave.service.pas.NotificationSampleRequest;
import org.p32h.interop.epa.pave.service.pas.PasCodes;
import org.p32h.interop.epa.pave.service.pas.PasInquireRequest;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.pas.SubscriptionRequest;
import org.p32h.interop.epa.pave.service.pas.SubscriptionService;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PAS endpoints: submit and inquire, the five subscription calls, and the notification receiver.
 *
 * <p>A completed call answers 200 even when the service returned a fault, because the tester needs
 * to see the request that caused it: the fault is described in the body. A write the production
 * guardrail refused answers 403 with the whole result as its body.
 *
 * <p>The session's production unlock is read here and handed to the service. It is never a request
 * parameter or part of a posted body, so nothing a browser sends can stand in for it.
 *
 * <p><b>The receiver is the one endpoint that is not for the browser.</b> The service posts to
 * {@code POST /api/pas/notifications}. Whatever arrives there is answered 200 with no body and
 * recorded, including something that is not JSON: the sender is a machine and is never handed an
 * error shape.
 *
 * <p>A read, a list and a delete of a subscription carry no body, so what a body would carry
 * arrives as request parameters: {@code scenarioId}, {@code expected}, {@code testerRef},
 * {@code note}, {@code chainId}, {@code configEpoch}, {@code collectionId} with {@code requestId},
 * a value typed for one placeholder as {@code var.<name>}, and something an earlier step handed on
 * as {@code carry.<key>}. A parameter outside these is refused by name, exactly as an unknown
 * field of a body is.
 */
@RestController
@RequestMapping("/api/pas")
public class PasController {

    private static final Logger log = LoggerFactory.getLogger(PasController.class);

    private static final String VARIABLE_PREFIX = "var.";
    private static final String CARRY_PREFIX = "carry.";

    /** Which call a scenario list is wanted for. A scenario answers only the calls its file defines. */
    private enum Use {
        SUBMIT, INQUIRE, SUBSCRIPTION, NOTIFICATION;

        static Use parse(String text) {
            for (Use use : values()) {
                if (use.name().equalsIgnoreCase(text)) {
                    return use;
                }
            }
            throw new IllegalArgumentException("Not a use.");
        }
    }

    private static final List<String> SUBSCRIPTION_STEPS = List.of(MockSubscriptionClient.STEP_CREATE, MockSubscriptionClient.STEP_READ,
            MockSubscriptionClient.STEP_LIST, MockSubscriptionClient.STEP_UPDATE, MockSubscriptionClient.STEP_DELETE);

    private final PasService pas;
    private final SubscriptionService subscriptions;
    private final NotificationReceiver receiver;
    private final ScenarioRepository scenarios;
    private final EpaProperties epa;

    public PasController(PasService pas, SubscriptionService subscriptions, NotificationReceiver receiver, ScenarioRepository scenarios,
                         EpaProperties epa) {
        this.pas = pas;
        this.subscriptions = subscriptions;
        this.receiver = receiver;
        this.scenarios = scenarios;
        this.epa = epa;
    }

    // ---- submit and inquire ----

    @PostMapping("/submit")
    public ResponseEntity<RunEnvelope<PasDetail>> submit(@RequestBody PasSubmitRequest request, HttpServletRequest http) {
        return RunResponses.of(pas.runSubmit(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/submit/preview")
    public RequestPreview previewSubmit(@RequestBody PasSubmitRequest request, HttpServletRequest http) {
        return pas.previewSubmit(request, SessionUnlock.from(http));
    }

    @PostMapping("/inquire")
    public ResponseEntity<RunEnvelope<PasDetail>> inquire(@RequestBody PasInquireRequest request, HttpServletRequest http) {
        return RunResponses.of(pas.runInquire(request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/inquire/preview")
    public RequestPreview previewInquire(@RequestBody PasInquireRequest request, HttpServletRequest http) {
        return pas.previewInquire(request, SessionUnlock.from(http));
    }

    // ---- subscriptions ----

    @PostMapping("/subscriptions")
    public ResponseEntity<RunEnvelope<SubscriptionDetail>> createSubscription(@RequestBody SubscriptionRequest request, HttpServletRequest http) {
        return RunResponses.of(subscriptions.run(EpaOperation.SUBSCRIPTION_CREATE, request, RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/subscriptions/preview")
    public RequestPreview previewCreateSubscription(@RequestBody SubscriptionRequest request, HttpServletRequest http) {
        return subscriptions.preview(EpaOperation.SUBSCRIPTION_CREATE, request, SessionUnlock.from(http));
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<RunEnvelope<SubscriptionDetail>> listSubscriptions(@RequestParam Map<String, String> parameters, HttpServletRequest http) {
        return RunResponses.of(subscriptions.run(EpaOperation.SUBSCRIPTION_GET, fromParameters(null, parameters), RunOrigin.manual(),
                SessionUnlock.from(http)));
    }

    @GetMapping("/subscriptions/{id}")
    public ResponseEntity<RunEnvelope<SubscriptionDetail>> readSubscription(@PathVariable String id, @RequestParam Map<String, String> parameters,
                                                                           HttpServletRequest http) {
        return RunResponses.of(subscriptions.run(EpaOperation.SUBSCRIPTION_GET, fromParameters(id, parameters), RunOrigin.manual(),
                SessionUnlock.from(http)));
    }

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<RunEnvelope<SubscriptionDetail>> updateSubscription(@PathVariable String id, @RequestBody SubscriptionRequest request,
                                                                             HttpServletRequest http) {
        return RunResponses.of(subscriptions.run(EpaOperation.SUBSCRIPTION_UPDATE, about(id, request), RunOrigin.manual(), SessionUnlock.from(http)));
    }

    @PostMapping("/subscriptions/{id}/preview")
    public RequestPreview previewUpdateSubscription(@PathVariable String id, @RequestBody SubscriptionRequest request, HttpServletRequest http) {
        return subscriptions.preview(EpaOperation.SUBSCRIPTION_UPDATE, about(id, request), SessionUnlock.from(http));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<RunEnvelope<SubscriptionDetail>> deleteSubscription(@PathVariable String id, @RequestParam Map<String, String> parameters,
                                                                             HttpServletRequest http) {
        return RunResponses.of(subscriptions.run(EpaOperation.SUBSCRIPTION_DELETE, fromParameters(id, parameters), RunOrigin.manual(),
                SessionUnlock.from(http)));
    }

    /**
     * The request for an update, with the id the address names. The address is what the tester
     * sees and what the call goes to, so a body that names another subscription is refused: sending
     * to one while the body claims another would be a call nobody asked for.
     */
    private static SubscriptionRequest about(String id, SubscriptionRequest request) {
        String inBody = Texts.trimToNull(request.subscriptionId());
        if (inBody != null && !inBody.equals(id.strip())) {
            throw ValidationException.of("subscriptionId", "The address names one subscription and the body names another. Leave "
                    + "subscriptionId out of the body, or make the two the same.");
        }
        return request.toBuilder().subscriptionId(id).build();
    }

    /** The request for a read, a list or a delete, from the parameters that stand in for a body. */
    private static SubscriptionRequest fromParameters(String id, Map<String, String> parameters) {
        SubscriptionRequest.Builder builder = SubscriptionRequest.builder().subscriptionId(id);
        Map<String, String> variables = new LinkedHashMap<>();
        Map<String, List<String>> carry = new LinkedHashMap<>();
        Map<String, Object> unknown = new LinkedHashMap<>();
        String collectionId = null;
        String requestId = null;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            String value = parameter.getValue();
            switch (name) {
                case "scenarioId" -> builder.scenarioId(value);
                case "expected" -> builder.expected(value);
                case "testerRef" -> builder.testerRef(value);
                case "note" -> builder.note(value);
                case "chainId" -> builder.chainId(value);
                case "configEpoch" -> builder.configEpoch(Params.optional(name, value, Long::valueOf, "a whole number"));
                case "collectionId" -> collectionId = Texts.trimToNull(value);
                case "requestId" -> requestId = Texts.trimToNull(value);
                default -> {
                    if (name.startsWith(VARIABLE_PREFIX) && name.length() > VARIABLE_PREFIX.length()) {
                        variables.put(name.substring(VARIABLE_PREFIX.length()), value);
                    } else if (name.startsWith(CARRY_PREFIX) && name.length() > CARRY_PREFIX.length()) {
                        carry.put(name.substring(CARRY_PREFIX.length()), List.of(value));
                    } else {
                        // Refused by the service, by name. A misspelt parameter must not be dropped in silence.
                        unknown.put(name, value);
                    }
                }
            }
        }
        if (collectionId != null && requestId != null) {
            builder.request(new RequestRef(collectionId, requestId));
        } else if (collectionId != null || requestId != null) {
            throw ValidationException.of(collectionId == null ? "collectionId" : "requestId",
                    "A collection request is named by collectionId and requestId together. One was given without the other.");
        }
        return builder.variables(variables).carry(carry).unrecognised(unknown).build();
    }

    // ---- the notification receiver ----

    /**
     * What the service calls back. Answered 200 with no body whatever was posted: a notification,
     * something that is not JSON, nothing at all. Every one of them is recorded.
     */
    @PostMapping("/notifications")
    public ResponseEntity<Void> receiveNotification(HttpServletRequest http) {
        try {
            InputStream body;
            try {
                body = http.getInputStream();
            } catch (IOException e) {
                // The body cannot be opened. What arrived is a request with nothing readable in it, and that is recorded.
                body = null;
            }
            receiver.receive(NotificationReceiver.Inbound.read(body));
        } catch (RuntimeException e) {
            // The receiver already records its own failures to read. Reaching this is a defect, and
            // the sender is still not given an error shape. The exception types and frames only are
            // logged, never a message, which could quote the payload.
            log.error("The notification endpoint failed, reference {}: {}", UUID.randomUUID(), Faults.describe(e));
        }
        return ResponseEntity.ok().build();
    }

    /**
     * What has arrived, newest first. The screen polls this with the stamp of the newest
     * notification it already shows, and gets only what arrived since.
     */
    @GetMapping("/notifications")
    public NotificationList notifications(@RequestParam(required = false) String since) {
        return receiver.list(since);
    }

    /** Delivers a mock scenario's notification to the receiver, on the mock profile only. */
    @PostMapping("/notifications/sample")
    public NotificationView sampleNotification(@RequestBody NotificationSampleRequest request) {
        return receiver.deliverSample(request);
    }

    // ---- scenarios and vocabulary ----

    /**
     * On mock, the scenarios a tester can choose from. On live the list is empty and says why. On
     * both, the outcomes and element names a tester can declare as an expectation.
     *
     * @param use {@code submit}, {@code inquire}, {@code subscription} or {@code notification}, to
     *            get only the scenarios that answer that call and only the vocabulary that applies
     *            to it. Left out, everything is returned
     */
    @GetMapping("/scenarios")
    public ScenarioList scenarios(@RequestParam(name = "for", required = false) String use) {
        Use wanted = Params.optional("for", use, Use::parse, "one of submit, inquire, subscription or notification");
        List<OutcomeOption> outcomes = OutcomeOption.listOf(vocabularies(wanted)).stream().distinct().toList();
        List<String> elements = elements(wanted);
        if (!epa.mock()) {
            return new ScenarioList(false, "Scenarios exist on the mock profile only. On live every answer comes from the service.",
                    List.of(), List.of(), outcomes, elements);
        }
        ScenarioRepository.Loaded loaded = scenarios.load(MockOnyxPasClient.SCENARIOS);
        List<ScenarioSummary> summaries = new ArrayList<>();
        for (Scenario scenario : loaded.scenarios()) {
            ScenarioSummary summary = summary(scenario, wanted);
            if (summary != null) {
                summaries.add(summary);
            }
        }
        return new ScenarioList(true, null, summaries, loaded.problems(), outcomes, elements);
    }

    private static OutcomeCode[][] vocabularies(Use use) {
        if (use == null) {
            return new OutcomeCode[][] {PasOutcome.values(), SubscriptionOutcome.values(), NotificationOutcome.values(), RunOutcome.values()};
        }
        return switch (use) {
            case SUBMIT, INQUIRE -> new OutcomeCode[][] {PasOutcome.values(), RunOutcome.values()};
            case SUBSCRIPTION -> new OutcomeCode[][] {SubscriptionOutcome.values(), RunOutcome.values()};
            case NOTIFICATION -> new OutcomeCode[][] {NotificationOutcome.values(), RunOutcome.values()};
        };
    }

    private static List<String> elements(Use use) {
        if (use == null) {
            List<String> all = new ArrayList<>(PasCodes.EXPECTATION_ELEMENTS);
            PasCodes.SUBSCRIPTION_ELEMENTS.stream().filter(element -> !all.contains(element)).forEach(all::add);
            return all;
        }
        return switch (use) {
            case SUBMIT, INQUIRE -> PasCodes.EXPECTATION_ELEMENTS;
            case SUBSCRIPTION -> PasCodes.SUBSCRIPTION_ELEMENTS;
            // A notification arrives. Nobody declares what they expect of it beforehand.
            case NOTIFICATION -> List.of();
        };
    }

    /**
     * One scenario as the selector for a call lists it, or null when the scenario does not answer
     * that call. Offering it anyway would only lead to a refusal when it is chosen.
     */
    private static ScenarioSummary summary(Scenario scenario, Use use) {
        if (use == null) {
            return scenario.summary();
        }
        return switch (use) {
            case SUBMIT -> scenario.main() == null ? null : scenario.summary();
            case INQUIRE -> stepSummary(scenario, scenario.step(MockOnyxPasClient.STEP_INQUIRE), true);
            case NOTIFICATION -> stepSummary(scenario, scenario.step(MockOnyxPasClient.STEP_NOTIFY), false);
            case SUBSCRIPTION -> SUBSCRIPTION_STEPS.stream().noneMatch(step -> scenario.step(step) != null) ? null
                    // The five calls each have their own answer, so no one status or outcome stands for the scenario.
                    : new ScenarioSummary(scenario.id(), scenario.number(), scenario.label(), scenario.description(), null, null, null, null, null);
        };
    }

    /** @param call true when the step answers a call, so its behavior and status mean something to the tester */
    private static ScenarioSummary stepSummary(Scenario scenario, ScenarioStep step, boolean call) {
        if (step == null) {
            return null;
        }
        return new ScenarioSummary(scenario.id(), scenario.number(), scenario.label(), scenario.description(), scenario.sentinelMemberId(),
                scenario.sentinelNpi(), call ? step.behavior() : null, call ? step.httpStatus() : null,
                step.expect() == null ? null : step.expect().outcome());
    }
}
