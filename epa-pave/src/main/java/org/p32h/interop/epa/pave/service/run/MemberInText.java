package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.Texts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Whether the free text a run carries to the ledger holds member data the same request carries:
 * the note, the tester reference and the expectation, which the ledger keeps as typed.
 *
 * <p>Only what the request itself holds can be looked for: its member ID in any spelling (with or
 * without spaces or punctuation, in any letter case, inside a longer word), the first and the last
 * name as words, the date of birth in the usual ways of writing a date, the subscriber ID and the
 * address lines. A name or a date of somebody the request does not carry cannot be told from an
 * honest remark by any rule, so such text is not refused here. The note is free text, and member
 * data must not be typed into it.
 *
 * <p>Every message names the field and the kind of value found, never the value.
 */
public final class MemberInText {

    /** A member ID or a subscriber ID shorter than this, once punctuation is gone, is not looked for: too short to say anything. */
    static final int SHORTEST_ID_SOUGHT = 6;
    /** A name shorter than this is not looked for: one letter is an initial in any remark. */
    private static final int SHORTEST_NAME_SOUGHT = 2;
    /** An address line shorter than this is not looked for. */
    private static final int SHORTEST_ADDRESS_SOUGHT = 5;

    private static final Map<String, String> LABELS = Map.of("note", "The note", "testerRef", "The tester reference", "expected",
            "The expectation");

    private MemberInText() {
    }

    /**
     * What is wrong, by field, with free text that holds member data the request carries. Empty
     * when nothing is, or when the request carries no member to compare with.
     *
     * @param ending what the message says happened, for example "Nothing was sent."
     */
    public static Map<String, String> problems(String note, String testerRef, String expected, MemberDetail member, String ending) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (member == null) {
            return problems;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("note", note);
        fields.put("testerRef", testerRef);
        fields.put("expected", expected);
        fields.forEach((field, text) -> {
            List<String> kinds = kinds(text, member);
            if (!kinds.isEmpty()) {
                problems.put(field, message(field, kinds, ending));
            }
        });
        return problems;
    }

    /** The sentence for a field whose text holds the kinds of member data named. It never quotes the text. */
    public static String message(String field, List<String> kinds, String ending) {
        return LABELS.getOrDefault(field, "This text") + " holds " + String.join(" and ", kinds) + ". The ledger keeps it as written and "
                + "may not hold member data, so leave " + (kinds.size() == 1 ? "it" : "them") + " out. " + ending;
    }

    /** The kinds of member data a text holds, in words, or an empty list. */
    static List<String> kinds(String text, MemberDetail member) {
        List<String> kinds = new ArrayList<>();
        if (Texts.blank(text)) {
            return kinds;
        }
        if (holdsId(text, member.memberId())) {
            kinds.add("this run's member ID");
        }
        if (holdsWord(text, member.firstName())) {
            kinds.add("the member's first name");
        }
        if (holdsWord(text, member.lastName())) {
            kinds.add("the member's last name");
        }
        if (holdsDate(text, member.dateOfBirth())) {
            kinds.add("the member's date of birth");
        }
        if (holdsId(text, member.subscriberId())) {
            kinds.add("the member's subscriber ID");
        }
        if (holdsAddress(text, member.address())) {
            kinds.add("the member's address");
        }
        return kinds;
    }

    /**
     * True when a text holds an identifier however it is spaced, punctuated or cased, also inside a
     * longer word: both are compared with everything but letters and digits removed.
     */
    public static boolean holdsId(String text, String id) {
        String sought = alphanumerics(id);
        return sought.length() >= SHORTEST_ID_SOUGHT && alphanumerics(text).contains(sought);
    }

    /**
     * True when a text holds an identifier that only a test can recognise, such as one a ledger
     * line holds as a token. Each run of characters that ends in the last four characters the line
     * kept is tried, in the case it was typed and in upper and lower case, with spaces and then with
     * all punctuation removed. Nothing has to be turned back into the identifier for this.
     *
     * @param lastFour the last four characters of the identifier as the line holds them
     * @param is       true for a candidate that is the identifier
     */
    public static boolean holdsId(String text, String lastFour, Predicate<String> is) {
        if (Texts.blank(text) || Texts.blank(lastFour)) {
            return false;
        }
        Set<String> forms = new LinkedHashSet<>();
        for (String squeezed : List.of(MemberIds.noSpace(text), text.replaceAll("[^A-Za-z0-9]", ""))) {
            forms.add(squeezed);
            forms.add(squeezed.toUpperCase(Locale.ROOT));
            forms.add(squeezed.toLowerCase(Locale.ROOT));
        }
        String end = lastFour.toLowerCase(Locale.ROOT);
        for (String form : forms) {
            String lower = form.toLowerCase(Locale.ROOT);
            for (int at = lower.indexOf(end); at >= 0; at = lower.indexOf(end, at + 1)) {
                int stop = at + end.length();
                for (int start = Math.max(0, stop - 64); start <= stop - Math.max(SHORTEST_ID_SOUGHT, end.length()); start++) {
                    if (is.test(form.substring(start, stop))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean holdsWord(String text, String name) {
        String sought = Texts.trimToNull(name);
        if (sought == null || sought.length() < SHORTEST_NAME_SOUGHT) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(sought) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    /** True when a text holds a date written in one of the usual ways: 1961-07-23, 19610723, 07/23/1961, 7/23/61, 23.07.1961. */
    private static boolean holdsDate(String text, String isoDate) {
        LocalDate date = Texts.present(isoDate) ? IsoDates.parse(isoDate) : null;
        if (date == null) {
            return false;
        }
        String year = String.format(Locale.ROOT, "%04d", date.getYear());
        String shortYear = year.substring(2);
        List<String> months = List.of(String.format(Locale.ROOT, "%02d", date.getMonthValue()), Integer.toString(date.getMonthValue()));
        List<String> days = List.of(String.format(Locale.ROOT, "%02d", date.getDayOfMonth()), Integer.toString(date.getDayOfMonth()));
        Set<String> forms = new LinkedHashSet<>();
        forms.add(year + months.get(0) + days.get(0));
        for (String separator : List.of("-", "/", ".", " ")) {
            for (String month : months) {
                for (String day : days) {
                    forms.add(year + separator + month + separator + day);
                    for (String y : List.of(year, shortYear)) {
                        forms.add(month + separator + day + separator + y);
                        forms.add(day + separator + month + separator + y);
                    }
                }
            }
        }
        for (String form : forms) {
            if (Pattern.compile("(?<!\\d)" + Pattern.quote(form) + "(?!\\d)").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsAddress(String text, PostalAddress address) {
        if (address == null) {
            return false;
        }
        String squeezed = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (String line : new String[]{address.line1(), address.line2()}) {
            String sought = line == null ? "" : line.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (sought.length() >= SHORTEST_ADDRESS_SOUGHT && squeezed.contains(sought)) {
                return true;
            }
        }
        return false;
    }

    private static String alphanumerics(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
