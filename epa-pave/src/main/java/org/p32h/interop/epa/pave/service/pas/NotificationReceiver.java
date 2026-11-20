package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.MockNotifications;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.NotificationKind;
import org.p32h.interop.epa.pave.model.pas.NotificationList;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The notification receiver: what the service calls back when a subscription has something to say.
 *
 * <p><b>It accepts anything and drops nothing.</b> Whatever is posted is read as far as it can be
 * read, given a line in the ledger, matched to the runs it belongs to and kept for the screen. A
 * body that is not JSON, one that is not a notification and one for a subscription nobody here
 * created are each recorded as exactly that. Nothing in this class throws for what a sender sent.
 *
 * <p><b>Where the payload lives.</b> A notification can carry a ClaimResponse, which is member
 * data. The payload is held in memory only, in a bounded list, for the screen. It is never written
 * to disk and never logged. The ledger line holds ids and codes and nothing else. After a restart
 * the ledger lines remain and the payloads are gone.
 *
 * <p><b>One instance.</b> The list of what has arrived is the state of this object, so an
 * application has exactly one receiver.
 */
@Service
public class NotificationReceiver {

    /** The most bytes of one body that are held. A longer body is cut here, and the record says so. */
    public static final int MAX_KEPT_BYTES = 131_072;

    /** Reading a body stops here. What lies beyond is not read at all, and the record says the size is not known. */
    public static final long MAX_READ_BYTES = 8L * 1024 * 1024;

    /** The most notifications held at once. The oldest is let go when one more arrives. Its ledger line remains. */
    public static final int CAPACITY = 200;

    private static final Logger log = LoggerFactory.getLogger(NotificationReceiver.class);

    private static final Pattern SUBSCRIPTION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private static final String HELD_NOTE = "Notifications are held in memory only: at most " + CAPACITY + " at once, each cut at "
            + MAX_KEPT_BYTES + " bytes, and none of them after a restart. The ledger line of every notification remains.";

    /**
     * The body of one request to the receiver, as far as it was read.
     *
     * @param kept          the bytes that are held: the start of the body, up to {@link #MAX_KEPT_BYTES}
     * @param bytesReceived how many bytes were read off the connection
     * @param complete      false when reading stopped before the end of the body, so its true size is not known
     */
    public record Inbound(byte[] kept, long bytesReceived, boolean complete) {

        /**
         * Reads a body without ever holding more of it than the receiver keeps. The rest is read
         * and counted, up to a limit, and thrown away. A connection that ends early, or a stream
         * that fails, yields what arrived until then: the receiver records that too.
         */
        public static Inbound read(InputStream in) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            long received = 0;
            boolean complete = false;
            if (in == null) {
                return new Inbound(new byte[0], 0, true);
            }
            byte[] chunk = new byte[8192];
            try {
                while (received < MAX_READ_BYTES) {
                    int read = in.read(chunk, 0, (int) Math.min(chunk.length, MAX_READ_BYTES - received));
                    if (read < 0) {
                        complete = true;
                        break;
                    }
                    int room = MAX_KEPT_BYTES - kept.size();
                    if (room > 0) {
                        kept.write(chunk, 0, Math.min(room, read));
                    }
                    received += read;
                }
                // A body that is exactly as long as the limit is complete, and one byte more says it is not.
                if (!complete && in.read() < 0) {
                    complete = true;
                }
            } catch (IOException | RuntimeException e) {
                // The sender went away part way through. What arrived is what there is.
                complete = false;
            }
            return new Inbound(kept.toByteArray(), received, complete);
        }

        /** A body that is already text in this process, as a sample is. */
        public static Inbound of(String text) {
            byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            byte[] kept = bytes.length > MAX_KEPT_BYTES ? java.util.Arrays.copyOf(bytes, MAX_KEPT_BYTES) : bytes;
            return new Inbound(kept, bytes.length, true);
        }

        /** True when what is held is not all that was sent. */
        public boolean cut() {
            return !complete || bytesReceived > kept.length;
        }

        /** Redacted, because the bytes are a payload. */
        @Override
        public String toString() {
            return "Inbound[bytesReceived=" + bytesReceived + ", kept=" + kept.length + ", complete=" + complete + "]";
        }
    }

    /** One notification that is held, with the instant it was stamped. */
    private record Held(Instant stamp, NotificationView view) {
    }

    /**
     * The two links of a notification to earlier runs, and how each was or was not established.
     *
     * @param unmatched why no run was found to have created the subscription the notification
     *                  names. Null when one was found, and for a body that is not a notification
     * @param notes     how each link was established, or why it was not, in the order they were worked out
     * @param unasked   those of the notes that say a link could not be looked up, because there is
     *                  no ledger or it could not be read. Empty when everything that needed asking
     *                  was asked and answered, whatever the answers were
     */
    private record Links(String subscriptionRunId, NotificationVerdicts.Unmatched unmatched, String submissionRunId, String submissionClaimId,
                         List<String> notes, List<String> unasked) {
    }

    /** Everything worked out from one body, before anything is written. */
    private record Interpreted(OutcomeCode outcome, NotificationReading reading, Links links, List<Finding> findings, VerdictView verdict,
                               NextStepView nextStep, List<String> codes, Map<String, String> correlation, String claimId) {

        /** The correlation holds identifiers read from the payload, an authorization number among them. Only the outcome is printed. */
        @Override
        public String toString() {
            return "Interpreted[" + outcome + "]";
        }
    }

    private final OperationGateway gateway;
    private final RunSupport support;
    private final ObjectProvider<RunLedger> ledger;
    private final ScenarioRepository scenarios;
    private final Function<String, NotificationReading> reader;

    private final Object lock = new Object();
    private final Deque<Held> held = new ArrayDeque<>();
    private Instant lastStamp;
    private long received;
    private long evicted;

    /**
     * @param gateway   asked which environment is active when a notification arrives, for its ledger line
     * @param ledger    asked which run created a subscription and which run submitted a claim. A
     *                  notification's own line is written through {@code support}
     * @param scenarios read for the notification a sample is built from, on the mock profile
     */
    @org.springframework.beans.factory.annotation.Autowired
    public NotificationReceiver(OperationGateway gateway, RunSupport support, ObjectProvider<RunLedger> ledger, ScenarioRepository scenarios) {
        this(gateway, support, ledger, scenarios, new NotificationReader()::read);
    }

    /** For a test of what the receiver does when its own reading of a body fails. */
    NotificationReceiver(OperationGateway gateway, RunSupport support, ObjectProvider<RunLedger> ledger, ScenarioRepository scenarios,
                         Function<String, NotificationReading> reader) {
        this.gateway = gateway;
        this.support = support;
        this.ledger = ledger;
        this.scenarios = scenarios;
        this.reader = reader;
    }

    /** The address this tool gives a service to call back, as configured, or null when it is not known here. */
    private volatile String callbackTemplate;

    /**
     * Tells the receiver the address it is reached at, so that a notification repeating a
     * credential of that address (a token in its query, a secret variable in it) is shown with that
     * credential masked, as a response repeating a secret of its request is. Given by setter, so
     * that a receiver built in a test without it still works and masks nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void callbackAddress(org.p32h.interop.epa.pave.config.OnyxProperties onyx) {
        this.callbackTemplate = onyx == null || onyx.subscription() == null ? null : onyx.subscription().callbackUrl();
    }

    /**
     * The credentials of the address to call back: each secret variable in it and each query
     * parameter whose name reads as a credential. A notification is not an answer to a request of
     * this tool's, so the access token and the other secrets of a request are not known here; the
     * service has no reason to hold them, while it holds this address by design.
     */
    private org.p32h.interop.epa.pave.model.SentSecrets callbackSecrets() {
        String template = callbackTemplate;
        if (Texts.blank(template) || gateway == null) {
            return org.p32h.interop.epa.pave.model.SentSecrets.none();
        }
        org.p32h.interop.epa.pave.model.SentSecrets.Builder found = org.p32h.interop.epa.pave.model.SentSecrets.builder();
        try {
            ResolvedText resolved = gateway.resolve(template.strip(), Map.of());
            if (resolved == null || !resolved.resolved() || resolved.value() == null) {
                return org.p32h.interop.epa.pave.model.SentSecrets.none();
            }
            for (org.p32h.interop.epa.pave.model.VariableUse use : resolved.variables()) {
                if (use.secret()) {
                    ResolvedText one = gateway.resolve("{{" + use.name() + "}}", Map.of());
                    if (one != null && one.resolved()) {
                        found.variable(use.name(), one.value());
                    }
                }
            }
            String url = resolved.value();
            int query = url.indexOf('?');
            if (query >= 0) {
                int end = url.indexOf('#', query);
                for (String parameter : url.substring(query + 1, end < 0 ? url.length() : end).split("&")) {
                    int equals = parameter.indexOf('=');
                    if (equals <= 0) {
                        continue;
                    }
                    String name = java.net.URLDecoder.decode(parameter.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8);
                    if (org.p32h.interop.epa.pave.model.Secrets.looksSecret(name)) {
                        String value = parameter.substring(equals + 1);
                        found.variable(name, value);
                        found.variable(name, java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (RuntimeException e) {
            // A configuration that cannot be read now leaves the payload as it arrived; the run's
            // own call would report the configuration. The exception type only, never its message.
            log.warn("The address to call back could not be read to mask a notification ({})", e.getClass().getSimpleName());
            return org.p32h.interop.epa.pave.model.SentSecrets.none();
        }
        return found.build();
    }

    /** Records whatever arrived at the receiver. Never throws for anything a sender sent. */
    public NotificationView receive(Inbound inbound) {
        return accept(inbound == null ? new Inbound(new byte[0], 0, true) : inbound, null, List.of());
    }

    /**
     * Delivers the notification a mock scenario holds to the same code a real one reaches, made to
     * be about the submission the tester names. On the mock profile only: on live a notification
     * comes from the service, and one this tool made up would be a result that did not.
     *
     * @throws ValidationException when the request cannot be used, naming the field
     * @throws NotFoundException   when the run that is named is not in the ledger
     */
    public NotificationView deliverSample(NotificationSampleRequest request) {
        if (!support.epa().mock()) {
            throw ValidationException.of("scenarioId", "A sample notification can be delivered on the mock profile only. On live a "
                    + "notification comes from the service.");
        }
        String runId = request == null ? null : Texts.trimToNull(request.runId());
        String scenarioId = request == null ? null : Texts.trimToNull(request.scenarioId());
        String subscriptionId = request == null ? null : Texts.trimToNull(request.subscriptionId());
        if (subscriptionId != null && !SUBSCRIPTION_ID.matcher(subscriptionId).matches()) {
            throw ValidationException.of("subscriptionId", "A subscription id is one word of up to 64 letters, digits, dots, hyphens, "
                    + "underscores and colons.");
        }
        RunLedger target = available();
        RunRecord run = null;
        if (runId != null) {
            if (!PasService.isRunId(runId)) {
                throw ValidationException.of("runId", "The run must be a run id this tool returned.");
            }
            run = target == null ? null : target.find(runId).orElse(null);
            if (run == null) {
                // The id is not repeated: a 404 answers with the tool's own words only, never with what the request held.
                throw new NotFoundException(target == null ? "No ledger is available, so that run cannot be looked up."
                        : "There is no run with that id in the ledger.");
            }
            if (run.operation() != EpaOperation.PAS_SUBMIT) {
                throw ValidationException.of("runId", "Name a PAS submission. The run " + runId + " is a " + run.operation().label() + " run.");
            }
        }
        if (scenarioId == null && run != null) {
            // The notification that belongs to the answer the submission was given.
            scenarioId = Texts.trimToNull(run.scenarioId());
        }
        if (scenarioId == null) {
            throw ValidationException.of("scenarioId", "Choose the scenario whose notification to deliver.");
        }
        List<Scenario> all = scenarios.list(MockOnyxPasClient.SCENARIOS);
        String wanted = scenarioId;
        Scenario scenario = all.stream().filter(s -> s.id().equals(wanted)).findFirst()
                .orElseThrow(() -> ValidationException.of("scenarioId", "There is no scenario with id \"" + wanted + "\"."));
        ScenarioStep notify = MockNotifications.step(scenario);
        if (notify == null || notify.body() == null) {
            throw ValidationException.of("scenarioId", "Scenario " + scenario.id() + " holds no notification. Choose one of: "
                    + String.join(", ", all.stream().filter(s -> MockNotifications.step(s) != null).map(Scenario::id).toList()) + ".");
        }
        String claimId = null;
        String claimResponseId = null;
        if (MockNotifications.aboutASubmission(notify)) {
            if (run == null) {
                throw ValidationException.of("runId", "The notification of scenario " + scenario.id() + " is about a submission. Name the run "
                        + "it should be about.");
            }
            claimId = run.correlation().get(RunRecord.CORR_CLAIM_ID);
            if (Texts.blank(claimId)) {
                throw ValidationException.of("runId", "The run " + runId + " recorded no claim id, so a notification cannot be made about it.");
            }
            claimResponseId = Texts.orElse(run.correlation().get(RunRecord.CORR_CLAIM_RESPONSE_ID), MockNotifications.claimResponseIdFor(claimId));
        }
        List<String> notes = new ArrayList<>();
        if (subscriptionId == null) {
            boolean[] lookupFailed = new boolean[1];
            subscriptionId = latestCreatedSubscription(target, lookupFailed);
            if (subscriptionId == null) {
                notes.add(target == null || lookupFailed[0]
                        ? "The ledger could not be asked which subscription this tool created last, so this sample names one nobody created."
                        : "No run in the ledger has created a subscription, so this sample names one nobody created and is shown as "
                                + "unmatched. Create a subscription first to see a notification that is matched to it.");
            }
        }
        return accept(Inbound.of(MockNotifications.body(notify, subscriptionId, claimId, claimResponseId)), scenario.id(), notes);
    }

    /**
     * What the receiver holds, newest first.
     *
     * @param since the stamp of the newest notification the caller already has, or blank for all
     *              that are held. Only notifications stamped after it are returned
     * @throws ValidationException when {@code since} is not a timestamp
     */
    public NotificationList list(String since) {
        Instant after = null;
        if (Texts.present(since)) {
            try {
                after = OffsetDateTime.parse(since.strip()).toInstant();
            } catch (DateTimeParseException e) {
                throw ValidationException.of("since", "Give since as the stamp of a notification this endpoint returned, for example "
                        + "2026-01-31T14:05:00.123Z.");
            }
        }
        synchronized (lock) {
            // The list is in the order of its stamps, so a poll stops at the first one the caller has seen.
            List<NotificationView> out = new ArrayList<>();
            Iterator<Held> newestFirst = held.descendingIterator();
            while (newestFirst.hasNext()) {
                Held one = newestFirst.next();
                if (after != null && !one.stamp().isAfter(after)) {
                    break;
                }
                out.add(one.view());
            }
            return new NotificationList(out, held.size(), received, evicted, CAPACITY, held.isEmpty() ? null : held.peekLast().view().receivedAt(),
                    HELD_NOTE);
        }
    }

    private NotificationView accept(Inbound inbound, String sampleScenarioId, List<String> sampleNotes) {
        List<String> payloadNotes = new ArrayList<>(sampleNotes);
        String text = decode(inbound.kept(), inbound.cut(), payloadNotes);
        org.p32h.interop.epa.pave.model.SentSecrets.Masked masked = callbackSecrets().mask(text);
        if (masked.places() > 0) {
            // Masked before anything reads it, so the designed view, the payload and the ledger line see the mask alone.
            text = masked.text();
            payloadNotes.add(masked.places() == 1
                    ? "1 place in this payload is masked because it repeats a credential of the address this tool gives the service to "
                            + "call back. What is shown is therefore not exactly what was received."
                    : masked.places() + " places in this payload are masked because they repeat a credential of the address this tool gives "
                            + "the service to call back. What is shown is therefore not exactly what was received.");
        }
        String cut = null;
        if (inbound.cut()) {
            cut = (inbound.complete() ? "The body was " + inbound.bytesReceived() + " bytes long."
                    : "The body was longer than the " + inbound.bytesReceived() + " bytes that were read, and reading stopped there.")
                    + " Only its first " + inbound.kept().length + " bytes are held and shown, so what is read here is not all that was sent.";
            payloadNotes.add(cut);
        }

        RunRecord.Builder draft = support.localDraft(EpaOperation.SUBSCRIPTION_NOTIFICATION, environment(), RunOrigin.manual())
                .scenarioId(sampleScenarioId).result(TestResult.UNVERIFIED).unrecordedInputs(List.of(RunRecord.INPUT_INBOUND));
        NotificationView.Builder view = NotificationView.builder().sample(sampleScenarioId != null).scenarioId(sampleScenarioId)
                .payload(text.isEmpty() ? null : text).bytesReceived(inbound.bytesReceived()).bytesKept(inbound.kept().length)
                .truncated(inbound.cut()).payloadNotes(payloadNotes);
        OutcomeCode outcome;
        try {
            // Only the reading, the matching and the wording are inside the try. The ledger write is
            // outside it, so a failure after the write cannot produce a second line for one notification.
            Interpreted read = interpret(text, inbound.cut(), sampleScenarioId, cut);
            outcome = read.outcome();
            draft.outcome(outcome).coverageCodes(read.codes()).correlation(read.correlation()).nextAction(read.nextStep().action())
                    .findings(read.findings().stream().map(f -> f.kind().name()).distinct().toList());
            NotificationReading reading = read.reading();
            view.verdict(read.verdict()).nextStep(read.nextStep()).kind(reading.kind()).type(reading.status().type())
                    .subscriptionStatus(reading.status().subscriptionStatus()).subscription(reading.status().subscription())
                    .subscriptionId(reading.status().subscriptionId()).topic(reading.status().topic())
                    .eventsSinceSubscriptionStart(reading.status().eventsSinceSubscriptionStart()).events(reading.status().events())
                    .errors(reading.status().errors()).payloadContent(reading.payloadContent()).bundleType(reading.carried().bundleType())
                    .bundleId(reading.carried().bundleId()).bundleTimestamp(reading.carried().bundleTimestamp())
                    .claimResponses(reading.carried().claimResponses()).otherResources(reading.carried().otherResources())
                    .issues(reading.carried().issues()).subscriptionRunId(read.links().subscriptionRunId())
                    .correlatedRunId(read.links().submissionRunId()).claimId(read.claimId())
                    .claimResponseId(reading.identifiers().claimResponseId()).correlationNotes(read.links().notes()).findings(read.findings())
                    .additionalFields(reading.additionalFields());
        } catch (RuntimeException e) {
            // This tool failed to read what arrived. It still arrived: it gets its line, and the
            // payload is still kept for the screen. The exception types and frames only are logged,
            // never its message, which can quote the payload.
            String reference = UUID.randomUUID().toString();
            log.error("The receiver failed while reading a notification, reference {}: {}", reference, Faults.describe(e));
            outcome = RunOutcome.TOOL_ERROR;
            draft.outcome(outcome).coverageCodes(List.of()).correlation(Map.of()).findings(List.of()).nextAction(NextAction.REVIEW);
            view.verdict(VerdictView.of(outcome, "Something arrived at the receiver, and this tool then failed while reading it.",
                            List.of("This is a defect in this tool, not a finding about what was sent. Report the reference " + reference
                                    + ". The server log holds the detail under the same reference.", "The payload is shown below as it arrived.")))
                    .nextStep(NextStepView.of(NextAction.REVIEW, "Next: read the payload below as it arrived, and report the reference.",
                            "This tool failed before it could say what the notification is."))
                    .kind(Texts.blank(text) ? NotificationKind.NO_BODY : JsonSafe.parse(text).ok() ? NotificationKind.OTHER_JSON : NotificationKind.NOT_JSON);
        }

        RunRecord record = draft.build();
        LedgerReceipt receipt = support.record(record);
        NotificationView stored;
        synchronized (lock) {
            // Stamped and added in one step, so the list is always in the order of its stamps and
            // no two notifications share one.
            Instant now = support.clock().instant().truncatedTo(ChronoUnit.MILLIS);
            lastStamp = lastStamp != null && !now.isAfter(lastStamp) ? lastStamp.plusMillis(1) : now;
            stored = view.id(record.runId()).receivedAt(DateTimeFormatter.ISO_INSTANT.format(lastStamp)).ledger(receipt).build();
            held.addLast(new Held(lastStamp, stored));
            received++;
            while (held.size() > CAPACITY) {
                held.removeFirst();
                evicted++;
            }
        }
        // The outcome and the sizes only. Never the payload, and never an identifier read from it.
        log.info("Notification received: outcome {}, {} bytes read, {} held, ledger line written: {}", outcome.name(), inbound.bytesReceived(),
                inbound.kept().length, receipt.recorded());
        return stored;
    }

    private Interpreted interpret(String text, boolean wasCut, String sampleScenarioId, String cut) {
        NotificationReading reading = reader.apply(text);
        Links links = correlate(reading);
        NotificationOutcome outcome = NotificationVerdicts.outcome(reading, links.unmatched());
        NotificationReading.Status status = reading.status();
        NotificationReading.Identifiers ids = reading.identifiers();
        String claimId = Texts.firstPresent(ids.claimId(), links.submissionClaimId());

        List<Finding> findings = new ArrayList<>();
        // Raised only when it is known that nobody here created the subscription. When the ledger
        // could not be asked that is not known, and the finding after this one says so in its place.
        if (outcome == NotificationOutcome.UNMATCHED_NOTIFICATION) {
            findings.add(new Finding(FindingKind.UNMATCHED_NOTIFICATION, "This notification matches no subscription this tool created",
                    (status.subscriptionId() == null ? "It names no subscription, so there is none to look up."
                            : "It names the subscription " + status.subscriptionId() + ", and no run in the ledger created a subscription "
                            + "with that id. Another client may have created it, or it was created before this ledger began, or by "
                            + "another installation of this tool.")
                            + " It is recorded and shown, never dropped.", status.subscriptionId(), null, null));
        }
        // Raised whenever a link was to be looked up and could not be: the subscription, the
        // submission, or both. The kind goes on the ledger line, where a line with no link would
        // otherwise read as a notification that was checked and matched nothing. The finding names
        // the subscription only when the subscription is what could not be looked up. A body that
        // is not a notification can still carry a ClaimResponse to look up, so it is not called one.
        if (!links.unasked().isEmpty()) {
            boolean notification = reading.notification();
            findings.add(new Finding(FindingKind.UNCHECKED_NOTIFICATION,
                    (notification ? "This notification" : "What arrived") + " could not be checked against the ledger",
                    String.join(" ", links.unasked()) + " A run that could not be looked up may exist all the same. "
                            + (notification ? "The notification" : "What arrived") + " is recorded and shown, never dropped.",
                    links.unmatched() == NotificationVerdicts.Unmatched.NOT_ASKED ? status.subscriptionId() : null, null, null));
        }
        unrecognised(findings, "notification type", PasCodes.NOTIFICATION_TYPE, status.type());
        unrecognised(findings, "subscription status", PasCodes.SUBSCRIPTION_STATUS, status.subscriptionStatus());

        List<String> codes = new ArrayList<>();
        codes.add("notification-kind=" + reading.kind().name());
        if (status.type().code() != null) {
            codes.add(PasCodes.NOTIFICATION_TYPE + "=" + status.type().code());
        }
        if (status.subscriptionStatus().code() != null) {
            codes.add(PasCodes.SUBSCRIPTION_STATUS + "=" + status.subscriptionStatus().code());
        }
        if (reading.payloadContent() != null) {
            codes.add(PasCodes.PAYLOAD_CONTENT + "=" + reading.payloadContent());
        }
        codes.addAll(PasReading.ledgerCodes(reading.carried().claimResponses()));
        if (wasCut) {
            codes.add("body=truncated");
        }

        Map<String, String> correlation = new LinkedHashMap<>();
        put(correlation, RunRecord.CORR_SUBSCRIPTION_ID, status.subscriptionId());
        put(correlation, RunRecord.CORR_SUBSCRIPTION_RUN_ID, links.subscriptionRunId());
        put(correlation, RunRecord.CORR_RUN_ID, links.submissionRunId());
        put(correlation, RunRecord.CORR_CLAIM_ID, claimId);
        put(correlation, RunRecord.CORR_CLAIM_RESPONSE_ID, ids.claimResponseId());
        put(correlation, RunRecord.CORR_AUTHORIZATION_REF, ids.authorizationRef());

        VerdictView verdict = NotificationVerdicts.verdict(outcome, reading, links.notes(), sampleScenarioId, cut, links.unmatched());
        NextStepView nextStep = NotificationVerdicts.nextStep(outcome, reading, claimId, links.submissionRunId(), status.subscriptionId(),
                links.unmatched(), sampleScenarioId != null);
        return new Interpreted(outcome, reading, links, findings, verdict, nextStep, codes, correlation, claimId);
    }

    /**
     * Finds the two runs a notification belongs to. The run that created the subscription it
     * names, and the submission it is about, found by the claim id, the ClaimResponse id or the
     * authorization number it carries, tried in that order: the claim id is the one identifier
     * this tool minted itself.
     *
     * <p>Every comparison with a stored value is the ledger's own. A stored authorization number
     * may be a token, and only the ledger can say whether a token is a given number.
     */
    private Links correlate(NotificationReading reading) {
        List<String> notes = new ArrayList<>();
        // What could not be looked up at all. Kept apart from what was looked up and not found: the
        // first leaves a link unknown, the second says there is none.
        List<String> unasked = new ArrayList<>();
        RunLedger target = available();
        NotificationReading.Identifiers ids = reading.identifiers();

        // "No run created it" and "the ledger could not be asked" are kept apart from here on: the
        // first is a fact about the subscription, the second leaves it unknown.
        String subscriptionRunId = null;
        NotificationVerdicts.Unmatched unmatched = null;
        if (reading.notification()) {
            String subscriptionId = reading.status().subscriptionId();
            if (subscriptionId == null) {
                unmatched = NotificationVerdicts.Unmatched.NAMES_NONE;
                notes.add("It names no subscription, so it cannot be matched to one this tool created.");
            } else if (target == null) {
                unmatched = NotificationVerdicts.Unmatched.NOT_ASKED;
                notLookedUp(notes, unasked, "No ledger is available, so the subscription it names could not be looked up.");
            } else if (!RunRecord.keepsIdentifier(subscriptionId)) {
                // The ledger writes such an id as its length, so no line can be found by it, and
                // "no run created it" would not be known to be true.
                unmatched = NotificationVerdicts.Unmatched.NOT_KEPT;
                notLookedUp(notes, unasked, NOT_KEPT_SUBSCRIPTION);
            } else {
                boolean[] lookupFailed = new boolean[1];
                RunRecord created = find(target, EpaOperation.SUBSCRIPTION_CREATE, RunRecord.CORR_SUBSCRIPTION_ID, subscriptionId, lookupFailed);
                if (created != null) {
                    subscriptionRunId = created.runId();
                    notes.add("The subscription it names was created by the run " + created.runId() + ".");
                } else if (lookupFailed[0]) {
                    unmatched = NotificationVerdicts.Unmatched.NOT_ASKED;
                    notLookedUp(notes, unasked, "The ledger could not be read, so the subscription it names could not be looked up.");
                } else {
                    unmatched = NotificationVerdicts.Unmatched.NOT_IN_LEDGER;
                    notes.add("No run in the ledger created the subscription it names.");
                }
            }
        }

        String submissionRunId = null;
        String submissionClaimId = null;
        // A flag of its own, so a failure to look the subscription up is not reported as a failure to look the submission up.
        boolean[] failed = new boolean[1];
        if (ids.any() && target != null) {
            // Each identifier is asked about on its own, so one that points somewhere else is seen and said.
            Map<String, RunRecord> found = new LinkedHashMap<>();
            // An id the ledger does not keep cannot be found in it, so it is not asked about, and that is said.
            List<String> notKept = new ArrayList<>();
            if (ids.claimId() != null && !RunRecord.keepsIdentifier(ids.claimId())) {
                notKept.add("the claim id");
            } else if (ids.claimId() != null) {
                found.put("the claim id", find(target, EpaOperation.PAS_SUBMIT, RunRecord.CORR_CLAIM_ID, ids.claimId(), failed));
            }
            if (ids.claimResponseId() != null && !RunRecord.keepsIdentifier(ids.claimResponseId())) {
                notKept.add("the ClaimResponse id");
            } else if (ids.claimResponseId() != null) {
                found.put("the ClaimResponse id", submission(target, RunRecord.CORR_CLAIM_RESPONSE_ID, ids.claimResponseId(), failed));
            }
            if (ids.authorizationRef() != null) {
                found.put("the authorization number", submission(target, RunRecord.CORR_AUTHORIZATION_REF, ids.authorizationRef(), failed));
            }
            String by = null;
            RunRecord chosen = null;
            for (Map.Entry<String, RunRecord> entry : found.entrySet()) {
                if (chosen == null && entry.getValue() != null) {
                    by = entry.getKey();
                    chosen = entry.getValue();
                }
            }
            if (chosen != null) {
                submissionRunId = chosen.runId();
                submissionClaimId = chosen.correlation().get(RunRecord.CORR_CLAIM_ID);
                notes.add("It is about the submission " + chosen.runId() + ", matched by " + by + " it carries.");
                for (Map.Entry<String, RunRecord> entry : found.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().runId().equals(chosen.runId())) {
                        notes.add("By " + entry.getKey() + " it carries it would be about another run, " + entry.getValue().runId()
                                + ". The match by " + by + " was kept.");
                    }
                }
                differs(notes, target, chosen, RunRecord.CORR_CLAIM_RESPONSE_ID, ids.claimResponseId(), "The ClaimResponse id it carries");
                differs(notes, target, chosen, RunRecord.CORR_AUTHORIZATION_REF, ids.authorizationRef(), "The authorization number it carries");
            } else if (failed[0]) {
                notLookedUp(notes, unasked, "The ledger could not be read, so the submission it is about could not be looked up.");
            } else if (!found.isEmpty()) {
                notes.add("No submission in the ledger carries " + String.join(" or ", found.keySet()) + " this notification carries.");
            }
            if (!notKept.isEmpty()) {
                notLookedUp(notes, unasked, "The submission it is about could not be looked up by " + String.join(" or ", notKept)
                        + " it carries: " + (notKept.size() == 1 ? "it reads" : "they read") + " like a member identifier or a date, "
                        + "which the ledger does not keep, so no run can be found by " + (notKept.size() == 1 ? "it" : "them") + ".");
            }
        } else if (ids.any()) {
            notLookedUp(notes, unasked, "No ledger is available, so the submission it is about could not be looked up.");
        } else if (reading.notification() && !reading.status().events().isEmpty()) {
            notes.add("It reports an event and names no claim, ClaimResponse or authorization number, so it cannot be tied to a submission.");
        }
        return new Links(subscriptionRunId, unmatched, submissionRunId, submissionClaimId, notes, List.copyOf(unasked));
    }

    /** Why a subscription named by an id the ledger does not keep is not looked up. */
    static final String NOT_KEPT_SUBSCRIPTION = "The subscription id it names reads like a member identifier or a date, which the ledger "
            + "does not keep, so the run that created the subscription cannot be found by it and was not looked up.";

    /** Says that a link could not be looked up, in the notes a reader sees and in the list the finding is raised from. */
    private static void notLookedUp(List<String> notes, List<String> unasked, String why) {
        notes.add(why);
        unasked.add(why);
    }

    /**
     * The submission that recorded an identifier: the submission's own line, or, when only an
     * inquiry saw the identifier, the submission that inquiry says it follows from.
     */
    private RunRecord submission(RunLedger target, String key, String value, boolean[] failed) {
        RunRecord submitted = find(target, EpaOperation.PAS_SUBMIT, key, value, failed);
        if (submitted != null) {
            return submitted;
        }
        RunRecord inquiry = find(target, EpaOperation.PAS_INQUIRE, key, value, failed);
        String follows = inquiry == null ? null : inquiry.correlation().get(RunRecord.CORR_RUN_ID);
        if (follows == null) {
            return null;
        }
        try {
            return target.find(follows).filter(run -> run.operation() == EpaOperation.PAS_SUBMIT).orElse(null);
        } catch (RuntimeException e) {
            failed[0] = true;
            return null;
        }
    }

    /** Says so when the submission recorded a different value than the notification carries. The ledger does the comparing. */
    private static void differs(List<String> notes, RunLedger target, RunRecord submission, String key, String carried, String what) {
        String stored = submission.correlation().get(key);
        // A value the record wrote as its length cannot be compared, and is not said to differ.
        if (carried == null || stored == null || stored.matches("\\(not recorded, \\d+ characters\\)")) {
            return;
        }
        boolean same;
        try {
            same = target.matches(stored, carried);
        } catch (RuntimeException e) {
            return;
        }
        if (!same) {
            notes.add(what + " is not the one that submission recorded.");
        }
    }

    /**
     * The newest run of one operation that carries an identifier and was sent, or null. A run the
     * guardrail refused names what it was aimed at and sent nothing, so a notification is never
     * about it. A ledger that fails is reported and never fatal.
     */
    private static RunRecord find(RunLedger target, EpaOperation operation, String key, String value, boolean[] failed) {
        try {
            return SentRuns.newest(target, operation, key, value);
        } catch (RuntimeException e) {
            failed[0] = true;
            return null;
        }
    }

    /**
     * The id of the subscription this tool created most recently, or null when the ledger holds
     * none or could not be asked.
     *
     * @param failed set when the ledger could not be read, so "none" is not said when it is not known
     */
    private static String latestCreatedSubscription(RunLedger target, boolean[] failed) {
        if (target == null) {
            return null;
        }
        try {
            for (RunRecord run : target.query(RunQuery.builder().operation(EpaOperation.SUBSCRIPTION_CREATE)
                    .outcome(SubscriptionOutcome.CREATED.name()).limit(20).build())) {
                String id = run.correlation().get(RunRecord.CORR_SUBSCRIPTION_ID);
                if (Texts.present(id)) {
                    return id;
                }
            }
        } catch (RuntimeException e) {
            failed[0] = true;
            return null;
        }
        return null;
    }

    private RunLedger available() {
        try {
            return ledger == null ? null : ledger.getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The environment that is active as the notification arrives. Which environment sent it cannot be known. */
    private EnvironmentRef environment() {
        try {
            EnvironmentRef active = gateway == null ? null : gateway.activeEnvironment();
            return active == null ? EnvironmentRef.none() : active;
        } catch (RuntimeException e) {
            return EnvironmentRef.none();
        }
    }

    /**
     * The kept bytes as text. A notification is JSON, which is UTF-8. Bytes that are not are shown
     * with a replacement mark and a note, so text that changed on the way to the screen does not
     * pass for what was sent.
     *
     * @param cut true when the bytes are only the start of the body. A cut can fall inside one
     *            character. That is the doing of this tool and not of the sender, so the body is
     *            not called invalid for it: the incomplete character is left out, with a note
     */
    private static String decode(byte[] kept, boolean cut, List<String> notes) {
        if (kept.length == 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(kept);
        // No byte of UTF-8 becomes more than one char, so the text never needs more room than this.
        CharBuffer out = CharBuffer.allocate(kept.length);
        // Told that more input follows, the decoder leaves an incomplete last character unread and does not report it.
        CoderResult result = decoder.decode(in, out, !cut);
        if (result.isError()) {
            notes.add("The body is not valid UTF-8. What could not be read is shown as a replacement mark, so the text below is not exactly "
                    + "what was sent.");
            return new String(kept, StandardCharsets.UTF_8);
        }
        if (in.hasRemaining()) {
            notes.add("What is held ends inside one character. That character is left out of the text below.");
        }
        return out.flip().toString();
    }

    private static void unrecognised(List<Finding> findings, String what, String element, CodedValue value) {
        if (value.code() != null && !value.recognised()) {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "The " + what + " is not a code this tool knows",
                    "It is expected to be one of " + String.join(", ", PasCodes.codes(element)) + ". The code that came is shown as it came "
                            + "and is not read as any of them.", element, null, value.code()));
        }
    }

    private static void put(Map<String, String> correlation, String key, String value) {
        if (Texts.present(value)) {
            correlation.put(key, value);
        }
    }
}
