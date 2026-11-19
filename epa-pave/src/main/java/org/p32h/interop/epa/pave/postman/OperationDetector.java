package org.p32h.interop.epa.pave.postman;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * Works out which operation a collection request is, from its name and its url.
 *
 * <p>It is used in two places and they ask different questions.
 * <ul>
 *   <li><b>At upload</b>, {@link #detect} proposes one operation for each request and says in
 *       words what it went on, so an operator can check the proposal and correct it. The name is
 *       followed first, because the vendor names its requests after the operations and a request
 *       that exists to show an error may carry a url that is wrong on purpose. When the url reads
 *       differently, the proposal says so.</li>
 *   <li><b>At every call</b>, {@link #signals} gives the production guardrail every operation the
 *       request's own name or its url reads as, the proposal and the disagreement alike. A call is
 *       a write if any of them is, so a correction can never turn a detected write into a read.</li>
 * </ul>
 * Detection never looks inside a body and never guesses between two readings: when nothing says
 * which operation a request is, it is {@code UNMAPPED}, which is not an error.
 */
public final class OperationDetector {

    private OperationDetector() {
    }

    /**
     * What detection made of one request.
     *
     * @param operation the operation proposed, or {@code UNMAPPED} when nothing says
     * @param basis     what the proposal rests on, in words, for the operator who checks it
     * @param conflict  true when the name and the url read as different operations. The name was
     *                  followed, and the mapping deserves a look
     */
    public record Detection(EpaOperation operation, String basis, boolean conflict) {
    }

    private record Match(EpaOperation operation, String phrase) {
    }

    /**
     * @param candidates every operation the url could be. One when the url says, several when it
     *                   does not say which, none when it says nothing
     * @param words      what in the url was read, completing the sentence "The url ..."
     */
    private record UrlReading(Set<EpaOperation> candidates, String words) {

        EpaOperation single() {
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        }
    }

    /** The proposal for a request, from its name and url alone. */
    public static Detection detect(String name, String url) {
        return detect(name, null, url, List.of());
    }

    /**
     * The proposal for a request.
     *
     * @param method     the request's HTTP method, or null. It tells the four subscription
     *                   operations apart when the name does not
     * @param folderPath the folders the request sits in. Consulted last, and only when neither the
     *                   name nor the url says anything
     */
    public static Detection detect(String name, String method, String url, List<String> folderPath) {
        List<Match> byName = nameMatches(words(name));
        UrlReading byUrl = readUrl(url, method);
        EpaOperation fromUrl = byUrl.single();

        if (!byName.isEmpty()) {
            Match match = byName.get(0);
            String named = "The name contains \"" + match.phrase() + "\"";
            if (byUrl.candidates().isEmpty()) {
                return new Detection(match.operation(), named + ".", false);
            }
            if (byUrl.candidates().contains(match.operation())) {
                return new Detection(match.operation(), named + ", and the url " + byUrl.words() + ".", false);
            }
            return new Detection(match.operation(), named + ", which reads as " + match.operation().label() + ", but the url "
                    + byUrl.words() + (fromUrl == null ? "" : ", which reads as " + fromUrl.label())
                    + ". The name was followed. Check this mapping and correct it if the url is the one to believe.", true);
        }
        if (fromUrl != null) {
            return new Detection(fromUrl, "The name does not say, and the url " + byUrl.words() + ".", false);
        }

        List<String> context = new ArrayList<>(folderPath == null ? List.of() : folderPath);
        context.add(name == null ? "" : name);
        List<Match> byFolder = nameMatches(words(String.join(" ", context)));
        if (!byFolder.isEmpty() && (byUrl.candidates().isEmpty() || byUrl.candidates().contains(byFolder.get(0).operation()))) {
            Match match = byFolder.get(0);
            return new Detection(match.operation(), "Neither the name nor the url says on its own. Read together with the folder it "
                    + "sits in, the name contains \"" + match.phrase() + "\".", false);
        }
        if (!byUrl.candidates().isEmpty()) {
            return new Detection(EpaOperation.UNMAPPED, "The url " + byUrl.words() + ", and nothing in the name says which operation "
                    + "that is. Choose one.", false);
        }
        return new Detection(EpaOperation.UNMAPPED, "Neither the name nor the url matches an operation this tool knows.", false);
    }

    /**
     * Every operation the request's own name or its url reads as. Empty when neither says
     * anything. The folder is not consulted: a folder called "submit and inquiry" must not make an
     * inquiry a write.
     *
     * @param url the url as a template or as resolved. The guardrail asks about both
     */
    public static Set<EpaOperation> signals(String name, String method, String url) {
        Set<EpaOperation> signals = new LinkedHashSet<>();
        nameMatches(words(name)).forEach(match -> signals.add(match.operation()));
        signals.addAll(readUrl(url, method).candidates());
        return signals;
    }

    // ------------------------------------------------------------------------------------------
    // The name
    // ------------------------------------------------------------------------------------------

    /** Every operation the words name, most specific first. The first is the proposal. */
    private static List<Match> nameMatches(List<String> words) {
        List<Match> matches = new ArrayList<>();
        if (phrase(words, "questionnaire", "package")) {
            matches.add(new Match(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "questionnaire-package"));
        }
        if (phrase(words, "next", "question")) {
            matches.add(new Match(EpaOperation.DTR_NEXT_QUESTION, "next-question"));
        }
        if (phrase(words, "order", "select")) {
            matches.add(new Match(EpaOperation.CRD_ORDER_SELECT, "order-select"));
        }
        if (phrase(words, "order", "sign")) {
            matches.add(new Match(EpaOperation.CRD_ORDER_SIGN, "order-sign"));
        }
        if (any(words, word -> word.equals("subscription") || word.equals("subscriptions"))) {
            // A request named for a notification is a notification even if it also says "get".
            addIf(matches, words, EpaOperation.SUBSCRIPTION_NOTIFICATION, "notification", "notifications", "notify");
            addIf(matches, words, EpaOperation.SUBSCRIPTION_CREATE, "create", "creates", "creation");
            addIf(matches, words, EpaOperation.SUBSCRIPTION_UPDATE, "update", "updates");
            addIf(matches, words, EpaOperation.SUBSCRIPTION_DELETE, "delete", "deletes", "deletion", "remove");
            addIf(matches, words, EpaOperation.SUBSCRIPTION_GET, "get", "read", "fetch", "list", "search");
        }
        // A name that says both is proposed as the submit, which is the reading that is safer if wrong.
        words.stream().filter(word -> word.startsWith("submi")).findFirst()
                .ifPresent(word -> matches.add(new Match(EpaOperation.PAS_SUBMIT, word)));
        words.stream().filter(word -> word.startsWith("inquir") || word.startsWith("enquir")).findFirst()
                .ifPresent(word -> matches.add(new Match(EpaOperation.PAS_INQUIRE, word)));
        if (phrase(words, "cds", "services")) {
            matches.add(new Match(EpaOperation.CRD_DISCOVERY, "cds-services"));
        } else {
            words.stream().filter(word -> word.equals("discovery") || word.equals("discover")).findFirst()
                    .ifPresent(word -> matches.add(new Match(EpaOperation.CRD_DISCOVERY, word)));
        }
        return matches;
    }

    private static void addIf(List<Match> matches, List<String> words, EpaOperation operation, String... verbs) {
        for (String verb : verbs) {
            if (words.contains(verb)) {
                matches.add(new Match(operation, "subscription\" and \"" + verb));
                return;
            }
        }
    }

    private static boolean any(List<String> words, Predicate<String> test) {
        return words.stream().anyMatch(test);
    }

    /** True when the two words stand side by side, or are written as one. */
    private static boolean phrase(List<String> words, String first, String second) {
        if (words.contains(first + second)) {
            return true;
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals(first) && words.get(i + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The words of a name, lower case. Anything that is not a letter or a digit separates words,
     * and so does a capital after a lower case letter, so {@code $questionnaire-package},
     * {@code order_sign} and {@code orderSign} all read the same.
     */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (Texts.blank(text)) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        char previous = 0;
        for (char c : text.toCharArray()) {
            boolean boundary = !Character.isLetterOrDigit(c) || (Character.isUpperCase(c) && Character.isLowerCase(previous));
            if (boundary && current.length() > 0) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            }
            previous = c;
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    // ------------------------------------------------------------------------------------------
    // The url
    // ------------------------------------------------------------------------------------------

    private static UrlReading readUrl(String url, String method) {
        Set<EpaOperation> candidates = new LinkedHashSet<>();
        List<String> read = new ArrayList<>();
        List<String> segments = pathSegments(url);
        String verb = method == null ? null : method.strip().toUpperCase(Locale.ROOT);

        for (String segment : segments) {
            switch (segment) {
                case "$submit" -> note(candidates, read, EpaOperation.PAS_SUBMIT, "has a $submit segment");
                case "$inquire" -> note(candidates, read, EpaOperation.PAS_INQUIRE, "has a $inquire segment");
                case "$questionnaire-package" ->
                        note(candidates, read, EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "has a $questionnaire-package segment");
                case "$next-question", "next-question" -> note(candidates, read, EpaOperation.DTR_NEXT_QUESTION, "has a next-question segment");
                default -> {
                    // A FHIR operation this tool does not know may do anything, so it is read as
                    // unknown, not as nothing. Left unread, a name that says "inquiry" over a url
                    // ending in an unknown operation would pass as a read.
                    if (segment.startsWith("$") && segment.length() > 1) {
                        note(candidates, read, EpaOperation.UNMAPPED, "ends in a FHIR operation this tool does not know");
                    }
                }
            }
        }

        int hooks = segments.indexOf("cds-services");
        if (hooks >= 0 && hooks == segments.size() - 1) {
            note(candidates, read, EpaOperation.CRD_DISCOVERY, "ends at cds-services");
        } else if (hooks >= 0 && hooks == segments.size() - 2) {
            String service = segments.get(hooks + 1).replaceAll("[^a-z0-9]", "");
            if (service.contains("orderselect")) {
                note(candidates, read, EpaOperation.CRD_ORDER_SELECT, "addresses a CDS Hooks service whose id reads as order-select");
            } else if (service.contains("ordersign")) {
                note(candidates, read, EpaOperation.CRD_ORDER_SIGN, "addresses a CDS Hooks service whose id reads as order-sign");
            } else {
                candidates.add(EpaOperation.CRD_ORDER_SIGN);
                candidates.add(EpaOperation.CRD_ORDER_SELECT);
                read.add("addresses a CDS Hooks service whose id does not say which hook it is");
            }
        }

        int subscription = segments.indexOf("subscription");
        if (subscription >= 0) {
            boolean collection = subscription == segments.size() - 1;
            if (verb == null) {
                candidates.add(EpaOperation.SUBSCRIPTION_GET);
                candidates.add(EpaOperation.SUBSCRIPTION_CREATE);
                candidates.add(EpaOperation.SUBSCRIPTION_UPDATE);
                candidates.add(EpaOperation.SUBSCRIPTION_DELETE);
                read.add("addresses Subscription, and without a method that could be any of the four subscription operations");
            } else {
                switch (verb) {
                    case "GET", "HEAD" -> note(candidates, read, EpaOperation.SUBSCRIPTION_GET, "is a " + verb + " on Subscription");
                    case "PUT", "PATCH" -> note(candidates, read, EpaOperation.SUBSCRIPTION_UPDATE, "is a " + verb + " on Subscription");
                    case "DELETE" -> note(candidates, read, EpaOperation.SUBSCRIPTION_DELETE, "is a DELETE on Subscription");
                    case "POST" -> {
                        if (collection) {
                            note(candidates, read, EpaOperation.SUBSCRIPTION_CREATE, "is a POST to Subscription");
                        } else {
                            note(candidates, read, EpaOperation.UNMAPPED, "is a POST below Subscription, which is not one of the four "
                                    + "subscription operations");
                        }
                    }
                    default -> note(candidates, read, EpaOperation.UNMAPPED, "addresses Subscription with the method " + verb
                            + ", which is not one of the four subscription operations");
                }
            }
        }
        return new UrlReading(candidates, String.join(" and ", read));
    }

    private static void note(Set<EpaOperation> candidates, List<String> read, EpaOperation operation, String words) {
        if (candidates.add(operation)) {
            read.add(words);
        }
    }

    /**
     * The segments of the url's path, lower case and percent-decoded, without the scheme, the host,
     * the query and the fragment. Decoded, because {@code Claim/%24submit} reaches the same
     * operation as {@code Claim/$submit} and must be read the same.
     */
    private static List<String> pathSegments(String url) {
        List<String> segments = new ArrayList<>();
        if (Texts.blank(url)) {
            return segments;
        }
        String path = url.strip();
        int end = path.length();
        for (int i = 0; i < path.length(); i++) {
            // A question mark inside a placeholder is part of its name, not the start of a query.
            if (path.startsWith("{{", i)) {
                int close = path.indexOf("}}", i);
                if (close > i) {
                    i = close + 1;
                    continue;
                }
            }
            if (path.charAt(i) == '?' || path.charAt(i) == '#') {
                end = i;
                break;
            }
        }
        String[] raw = path.substring(0, end).split("/", -1);
        int from = raw.length >= 3 && raw[0].endsWith(":") && raw[1].isEmpty() ? 3 : 0;
        for (int i = from; i < raw.length; i++) {
            String segment = raw[i];
            int parameters = segment.indexOf(';');
            if (parameters >= 0) {
                segment = segment.substring(0, parameters);
            }
            segments.add(decode(segment).strip().toLowerCase(Locale.ROOT));
        }
        while (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty()) {
            segments.remove(segments.size() - 1);
        }
        return segments;
    }

    /** Percent-decoding that never fails: a sequence that is not valid is left as written. */
    private static String decode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                out.append((char) Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
