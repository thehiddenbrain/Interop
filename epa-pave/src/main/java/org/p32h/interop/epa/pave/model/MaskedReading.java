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
     * The readings that draw a conclusion from an answer, each with the names it goes by: a name
     * whose value it decides by (a code, a system, a status, an identifier it compares, a reference
     * it follows, a yes or a no written in a questionnaire) or a name it finds its way by (a masked
     * "entry" reads as a bundle with no entries). They were found where each reading reads them,
     * not by what they are called, and {@code MaskedReadingNamesTest} reads the source of every
     * reading and fails when one goes by a name its list does not hold. A name that is only put on
     * screen is in no list: the mask in the field is its own notice.
     *
     * <p>Besides the names their code reads, each list holds the values it puts on screen and then
     * compares, carries or judges: the code of a status or a type held against its value set, the
     * outcome of a ClaimResponse (an error outcome counts as errors reported), the severity of an
     * issue, the type of a card link (a SMART link is offered as the next step), the appContext of
     * that link (carried into the DTR launch), the id of a subscription (held against the id asked
     * about, and a created one carried to the next call), the address it calls back (held against
     * the address sent), a ClaimResponse's preAuthRef and the start and end of its period (held
     * against its items' own, and the number kept to match a notification to its submission), and
     * whether a question is read only. A value shown through shown() is not found by reading the
     * code for a name, so {@code MaskedReadingNamesTest} lists every name each reading shows, either
     * as one of these, with the reason, or as only shown, and fails on a name it has not placed.
     */
    public enum Reading {
        /** A CRD answer: its cards, its system actions and the coverage information on them. */
        CRD(true, "resourceType", "url", "appContext", "code", "system", "display", "text", "id", "reference", "type", "topic", "context",
                "cards", "systemActions", "suggestions", "actions", "links", "overrideReasons", "source", "extension", "resource",
                "entry", "coding", "issue", "details", "location", "expression"),
        /** A CDS Hooks discovery answer: which services it lists. Each service's fields are only shown. */
        DISCOVERY(false, "services", "prefetch"),
        /** A DTR package or next question: the questionnaires, the response, its status and the rules of its items. */
        DTR(true, "resourceType", "url", "version", "derivedFrom", "profile", "questionnaire", "status", "type", "name", "id",
                "fullUrl", "reference", "linkId", "question", "operator", "enableBehavior", "required", "repeats", "code", "system",
                "display", "text", "value", "title", "readOnly", "subject", "subjectType", "parameter", "part", "resource", "entry", "meta",
                "extension", "item", "answer", "answerOption", "initial", "initialSelected", "enableWhen", "contained", "author",
                "compose", "include", "concept", "expansion", "contains", "content", "data", "unit", "coding", "issue", "details",
                "location", "expression"),
        /** A PAS submission or inquiry: the ClaimResponses, their review actions, items, errors and requests for documentation. */
        PAS(true, "resourceType", "type", "status", "outcome", "preAuthRef", "start", "end", "id", "fullUrl", "reference", "identifier", "system", "value", "code",
                "display", "text", "url", "itemSequence", "currency", "entry", "resource", "item", "addItem", "adjudication",
                "category", "error", "processNote", "noteNumber", "communicationRequest", "payload", "contentReference",
                "contentString", "contained", "extension", "meta", "insurer", "patient", "request", "requestor", "reason", "amount",
                "preAuthPeriod", "coding", "details", "issue", "location", "expression"),
        /** A subscription call: the Subscription or the bundle of them, its status, its channel and any issue reported. */
        SUBSCRIPTION(true, "resourceType", "id", "status", "type", "endpoint", "severity", "code", "system", "display", "text", "url", "reference",
                "profile", "entry", "resource", "channel", "header", "search", "_criteria", "_payload", "extension", "meta", "coding",
                "details", "issue", "location", "expression"),
        /** A notification: its status, type and subscription, and the ClaimResponses it carries, read as PAS reads them. */
        NOTIFICATION(true, "resourceType", "type", "status", "subscription", "focus", "name", "id", "fullUrl", "reference",
                "identifier", "system", "value", "code", "display", "text", "url", "parameter", "part", "notificationEvent",
                "additionalContext", "entry", "resource", "request", "error", "coding", "details", "issue", "location", "expression",
                "outcome", "preAuthRef", "start", "end", "itemSequence", "item", "addItem", "adjudication", "category", "processNote", "noteNumber",
                "communicationRequest", "payload", "contentReference", "contentString", "contained", "extension", "meta", "insurer",
                "patient", "requestor", "reason", "amount", "currency", "preAuthPeriod");

        private final boolean choices;
        private final Set<String> names;

        Reading(boolean choices, String... names) {
            this.choices = choices;
            this.names = Set.of(names);
        }

        /**
         * True for a name this reading goes by. A FHIR choice element (valueCode, answerBoolean,
         * initialString and the like) is read by its kind of value, so a reading of FHIR resources
         * goes by a name made of value, answer or initial and the name of a type as well.
         */
        public boolean goesBy(String name) {
            return names.contains(name) || (choices && (choice(name, "value") || choice(name, "answer") || choice(name, "initial")));
        }
    }

    public MaskedReading {
        readings = readings == null ? List.of() : List.copyOf(readings);
    }

    /**
     * Where a mask fell on a value this reading goes by, or on the name of one, in an answer as
     * received, before it is masked; or null when none did.
     */
    public static MaskedReading of(VendorExchange received, Reading reading) {
        if (received == null || received.request() == null) {
            return null;
        }
        return find(received.json(), received.request().secrets(), reading::goesBy, reading::goesBy);
    }

    /** The same for a body parsed apart from any exchange, such as a notification, searched with the secrets it is masked with. */
    public static MaskedReading of(JsonNode json, SentSecrets secrets, Reading reading) {
        return find(json, secrets, reading::goesBy, reading::goesBy);
    }

    /**
     * The readings found in the body with one more for a value this tool took from a header of the
     * answer, such as the id of a new subscription in its Location header, when the mask fell on it.
     * The walk of the body does not see a header, and a conclusion read from a masked header is as
     * much about the mask as one read from the body. Null when neither holds a mask.
     *
     * @param found      the readings of the body, or null when it had none
     * @param name       what the header gave, as the line names it, such as "id, from the Location header,"
     * @param fromHeader that value as it was read, masked with the secrets the body is masked with
     */
    public static MaskedReading withHeader(MaskedReading found, String name, SentSecrets.Masked fromHeader) {
        if (fromHeader == null || fromHeader.places() == 0) {
            return found;
        }
        List<String> readings = new ArrayList<>(found == null ? List.of() : found.readings());
        readings.add(name + " was read as " + masksIn(fromHeader.text()));
        return new MaskedReading(readings);
    }

    /**
     * Walks the answer once, from a stack of its own, so an answer as deep as the parser allows takes
     * no frame of the thread's stack per level.
     *
     * @param valueReadBy true for a name whose value a conclusion is read by
     * @param nameReadBy  true for a name a conclusion is read by, as a value or as the way to one.
     *                    It holds every name valueReadBy holds
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
                    if (maskedName.places() > 0 && nameReadBy.test(name)) {
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
                + "names holds a value that the answers of this service use as an ordinary word, so what is read from it may be wrong. "
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
     * said twice, and what follows the colon goes on the sentence, so its first word loses its
     * capital unless the word is written in capitals throughout, as PAS or DTR is.
     */
    public NextStepView on(NextStepView step) {
        if (step == null) {
            return null;
        }
        String said = step.headline().startsWith(NEXT) ? step.headline().substring(NEXT.length()) : step.headline();
        return step.withHeadline(STEP_FIRST + continued(said));
    }

    private static String continued(String sentence) {
        if (sentence.length() < 2 || !Character.isUpperCase(sentence.charAt(0)) || !Character.isLowerCase(sentence.charAt(1))) {
            return sentence;
        }
        return Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
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
