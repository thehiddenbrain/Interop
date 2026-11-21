package org.p32h.interop.epa.pave.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Where a mask fell on a value a conclusion of this tool is read by, or on the name of one.
 *
 * <p>Every mapper reads an answer with its masks in place, so that nothing it copies into a view
 * can carry a secret. When a secret this tool holds is also an ordinary word of the answers (the
 * word Bundle, say, typed as the value of a secret variable), the mapper reads the mask in place of
 * that word, and what the tool concludes is then about an answer the service did not send. The
 * conclusion is still drawn by the same rules, and nothing is guessed in its place; what changes is
 * that it says, where it is read, that it rests on a mask. A value that is only shown needs none of
 * this: the mask in the field is its own notice.
 *
 * <p>Only the masks are named, never what they stand for. A masked name is not given either,
 * because the name is the secret.
 *
 * @param readings one phrase per place, such as "resourceType was read as [secret tenantWord]", in
 *                 the order the answer holds them, each said once
 */
public record MaskedReading(List<String> readings) {

    /**
     * The names whose values the readings of CRD, CRD discovery, DTR, PAS, the subscription calls
     * and a notification decide by. Each was found where the code reads it, not by what it is
     * called:
     * <ul>
     *   <li>what a body or an entry is: resourceType, type (of a bundle, a card link, a questionnaire
     *       item), use, name (of a parameter)</li>
     *   <li>a state or a decision: status, outcome (an error outcome counts as errors reported),
     *       severity (an error issue), disposition, indicator</li>
     *   <li>a code and where it comes from: code, system, display and text (a delegated vendor is
     *       matched on a concept's text or display)</li>
     *   <li>an identifier that is compared or a reference that is followed: id, reference, fullUrl,
     *       url (which extension, which questionnaire), version, derivedFrom, profile, questionnaire,
     *       subscription, focus, topic, linkId, question, sequence, itemSequence, value (of an
     *       identifier or a detail)</li>
     *   <li>a yes or a no, or a rule, written in a questionnaire: required, repeats, enableBehavior,
     *       operator</li>
     * </ul>
     * A FHIR choice element (valueCode, answerBoolean, initialString and the like) is read by its
     * kind of value, so a name made of value, answer or initial and the name of a type counts as
     * well. {@code MaskedReadingNamesTest} reads the source of the readings and fails when one of
     * them reads a name by a value it does not list here.
     */
    static final Set<String> VALUES = Set.of("resourceType", "type", "use", "name", "status", "outcome", "severity", "disposition",
            "indicator", "code", "system", "display", "text", "id", "reference", "fullUrl", "url", "version", "derivedFrom", "profile",
            "questionnaire", "subscription", "focus", "topic", "linkId", "question", "sequence", "itemSequence", "value", "required",
            "repeats", "enableBehavior", "operator");

    /**
     * The names the readings find their way by, besides those of {@link #VALUES}. A mask on one of
     * them hides everything beneath it from the reading: a masked "entry" reads as a bundle with
     * no entries. {@code MaskedReadingNamesTest} fails when a reading finds its way by a name that is
     * in neither list.
     */
    static final Set<String> NAMES = Set.of("entry", "resource", "cards", "systemActions", "suggestions", "actions", "links",
            "overrideReasons", "source", "extension", "item", "answer", "answerOption", "initial", "initialSelected", "enableWhen",
            "contained", "parameter", "part", "coding", "issue", "details", "location", "expression", "services", "prefetch",
            "adjudication", "addItem", "error", "processNote", "noteNumber", "communicationRequest", "payload", "contentReference",
            "contentString", "channel", "header", "search", "_criteria", "_payload", "notificationEvent", "additionalContext",
            "meta", "identifier", "preAuthPeriod", "category", "reason", "request", "requestor", "insurer", "patient", "amount",
            "currency", "unit", "compose", "include", "concept", "expansion", "contains", "content", "data", "author", "subject",
            "subjectType", "context", "title");

    public MaskedReading {
        readings = readings == null ? List.of() : List.copyOf(readings);
    }

    /**
     * Where a mask fell on a value the readings of the protocols decide by, in an answer as
     * received, before it is masked; or null when none did.
     */
    public static MaskedReading of(VendorExchange received) {
        if (received == null || received.request() == null) {
            return null;
        }
        return find(received.json(), received.request().secrets(), MaskedReading::readsValueOf, MaskedReading::findsWayBy);
    }

    /** The same for a body parsed apart from any exchange, such as a notification, searched with the secrets it is masked with. */
    public static MaskedReading of(JsonNode json, SentSecrets secrets) {
        return find(json, secrets, MaskedReading::readsValueOf, MaskedReading::findsWayBy);
    }

    /**
     * Walks the answer once, from a stack of its own, so an answer as deep as the parser allows takes
     * no frame of the thread's stack per level.
     *
     * @param valueReadBy true for a name whose value a conclusion is read by
     * @param nameReadBy  true for a name a conclusion is read by, as a value or as the way to one
     * @return null when no mask falls on any of them
     */
    public static MaskedReading find(JsonNode json, SentSecrets secrets, Predicate<String> valueReadBy, Predicate<String> nameReadBy) {
        if (json == null || secrets == null || secrets.searched() == 0 || !json.isContainer()) {
            return null;
        }
        LinkedHashSet<String> readings = new LinkedHashSet<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(json);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    String name = property.getKey();
                    JsonNode value = property.getValue();
                    SentSecrets.Masked maskedName = secrets.mask(name);
                    if (maskedName.places() > 0 && (nameReadBy.test(name) || valueReadBy.test(name))) {
                        readings.add("the name of a value this tool reads by was read as " + masksIn(maskedName.text()));
                    } else if (valueReadBy.test(name)) {
                        // A list of plain values, such as the profiles a resource claims, is read value by value.
                        if (value.isArray()) {
                            for (JsonNode element : value) {
                                maskedValue(name, element, secrets, readings);
                            }
                        } else {
                            maskedValue(name, value, secrets, readings);
                        }
                    }
                    if (value.isContainer()) {
                        pending.push(value);
                    }
                }
            } else {
                for (JsonNode element : node) {
                    if (element.isContainer()) {
                        pending.push(element);
                    }
                }
            }
        }
        return readings.isEmpty() ? null : new MaskedReading(new ArrayList<>(readings));
    }

    private static void maskedValue(String name, JsonNode value, SentSecrets secrets, Set<String> readings) {
        if (!value.isString() && !value.isNumber()) {
            return;
        }
        SentSecrets.Masked read = secrets.mask(value.isString() ? value.stringValue() : value.toString());
        if (read.places() > 0) {
            readings.add(name + " was read as " + masksIn(read.text()));
        }
    }

    static boolean readsValueOf(String name) {
        return VALUES.contains(name) || choice(name, "value") || choice(name, "answer") || choice(name, "initial");
    }

    static boolean findsWayBy(String name) {
        return NAMES.contains(name) || readsValueOf(name);
    }

    /** True for a FHIR choice element: the stem followed by the name of a type, which starts with a capital. */
    private static boolean choice(String name, String stem) {
        return name.length() > stem.length() && name.startsWith(stem) && Character.isUpperCase(name.charAt(stem.length()));
    }

    /** The masks a masked text holds, by themselves, so that nothing of the text around them is repeated. */
    private static String masksIn(String masked) {
        List<String> masks = new ArrayList<>();
        int at = masked.indexOf("[secret ");
        while (at >= 0) {
            int end = masked.indexOf(']', at);
            if (end < 0) {
                break;
            }
            masks.add(masked.substring(at, end + 1));
            at = masked.indexOf("[secret ", end + 1);
        }
        return String.join(" and ", masks);
    }

    /** The sentence for the response note, below the verdict, as it has been worded since it was first given. */
    public String note() {
        return "This answer was read with its masks in place, and a value this tool reads it by repeats a secret this tool holds: "
                + String.join("; ", readings) + ". What is said about it here was read from the mask, not from what the service sent.";
    }

    /**
     * The line that stands in the verdict, under its headline: that the verdict was read from a mask
     * and not from what the service sent, which value and which mask, and what to do about it.
     */
    public String line() {
        return "Read from a mask, not from what the service sent: " + String.join("; ", readings) + ". The variable or setting the mask "
                + "names holds a value that the answers of this service use as an ordinary word, so the reading above may be wrong. "
                + "Look at that value in the Config tab.";
    }

    /** The words put before the next step, so that it is not taken as resting on what the service sent. */
    public static final String STEP_FIRST = "First settle the masked value the verdict names, in the Config tab. Until then, this next "
            + "step rests on the masked reading: ";

    /** The verdict with this line in it. */
    public VerdictView on(VerdictView verdict) {
        return verdict == null ? null : verdict.withMaskedReading(line());
    }

    /**
     * The next step, unchanged in what it offers, marked as resting on the masked reading. The
     * marking already says it is the next step, so the word that starts most instructions is not
     * said twice.
     */
    public NextStepView on(NextStepView step) {
        if (step == null) {
            return null;
        }
        String said = step.headline().startsWith(NEXT) ? step.headline().substring(NEXT.length()) : step.headline();
        return step.withHeadline(STEP_FIRST + said);
    }

    private static final String NEXT = "Next: ";

    /**
     * The finding a suite row and History show for it: its kind is all the ledger line keeps, and
     * the finding holds the masks' names alone, never a value they stand for.
     */
    public Finding finding() {
        return new Finding(FindingKind.MASKED_READING, "A value this tool reads the answer by was read from a mask",
                String.join("; ", readings) + ". What this tool concluded from it is about the mask, not about what the service sent. "
                        + "The variable or setting the mask names holds a value that the answers of this service use as an ordinary word.",
                null, null, null);
    }

    /** The masks' names are already on screen, but nothing of an answer goes into a log line: the count alone. */
    @Override
    public String toString() {
        return "MaskedReading[" + readings.size() + " places]";
    }
}
