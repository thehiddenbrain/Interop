package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.PostalAddress;
import org.p32h.interop.epa.pave.model.Texts;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;

/**
 * Whether the free text a run carries to the ledger holds member data the same request carries:
 * the note, the tester reference and the expectation, which the ledger keeps as typed.
 *
 * <p>Only what the request itself holds can be looked for: its member ID in any spelling (with or
 * without spaces or punctuation, in any letter case, inside a longer word, and its run of digits
 * alone when that has six or more), the first and the last name as words (alone when a name has
 * four letters or more, a shorter one only beside the other name, and one part of a hyphenated or
 * two-word surname of four letters or more), the date of birth in the usual ways of writing a date,
 * month names included, the subscriber ID, the address lines, and the city with the postal code.
 * Text is brought to one form first (Unicode compatibility normalisation), so a digit or a letter
 * typed in its fullwidth form is the same digit or letter. A name or a date of somebody the request does not carry cannot be told from an
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
    /** A name shorter than this is looked for only beside the other name, since it is an ordinary word as often as a name. */
    static final int SHORTEST_NAME_ALONE = 4;
    /** An address line shorter than this is not looked for. */
    private static final int SHORTEST_ADDRESS_SOUGHT = 5;

    private static final Map<String, String> LABELS = Map.of("note", "The note", "testerRef", "The tester reference", "expected",
            "The expectation");

    /**
     * Every outcome code this tool defines. An expectation that is exactly one of them was written
     * by this tool, not by a person about a member, so it is never refused for member data: a
     * member whose surname holds a word such as Timeout would otherwise have the right expectation
     * TIMEOUT refused as a name. The comparison is with this list only, and exact.
     */
    private static final Set<String> OWN_OUTCOME_CODES = Stream.of(CrdOutcome.values(), DtrOutcome.values(), PasOutcome.values(),
                    SubscriptionOutcome.values(), NotificationOutcome.values(), RawOutcome.values(), RunOutcome.values())
            .flatMap(Stream::of).map(OutcomeCode::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private MemberInText() {
    }

    /** True when the expectation, without the spaces around it, is exactly one of this tool's own outcome codes. */
    static boolean ownOutcomeCode(String expected) {
        return expected != null && OWN_OUTCOME_CODES.contains(expected.strip());
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
            if (field.equals("expected") && ownOutcomeCode(text)) {
                return;
            }
            List<String> kinds = kinds(text, member);
            if (!kinds.isEmpty()) {
                problems.put(field, message(field, kinds, ending));
            }
        });
        return problems;
    }

    /**
     * The same, for a run that names no member of its own and sends a body as written: its free
     * text is checked against each identifier the body states ({@link MembersInBody}). Empty when
     * the body states none, since there is then nothing to compare with.
     */
    public static Map<String, String> problemsInBody(String note, String testerRef, String expected, List<MemberDetail> inBody,
                                                     String ending) {
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("note", note);
        fields.put("testerRef", testerRef);
        fields.put("expected", expected);
        fields.forEach((field, text) -> {
            if (Texts.blank(text) || field.equals("expected") && ownOutcomeCode(text)) {
                return;
            }
            List<String> kinds = new ArrayList<>();
            if (inBody.stream().anyMatch(member -> holdsId(text, member.memberId()))) {
                kinds.add("a member ID the request body carries");
            }
            if (inBody.stream().anyMatch(member -> holdsId(text, member.subscriberId()))) {
                kinds.add("a subscriber ID the request body carries");
            }
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
    static List<String> kinds(String typed, MemberDetail member) {
        List<String> kinds = new ArrayList<>();
        if (Texts.blank(typed)) {
            return kinds;
        }
        String text = oneForm(typed);
        if (holdsId(text, member.memberId())) {
            kinds.add("this run's member ID");
        }
        boolean together = holdsBoth(text, member.firstName(), member.lastName());
        if (together || holdsName(text, member.firstName())) {
            kinds.add("the member's first name");
        }
        if (together || holdsName(text, member.lastName()) || holdsSurnamePart(text, member.lastName())) {
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
        if (sought.length() < SHORTEST_ID_SOUGHT) {
            return false;
        }
        String squeezed = alphanumerics(text);
        if (squeezed.contains(sought)) {
            return true;
        }
        // The ID's digits are often written without the letters in front of them, and they alone
        // say who the member is once there are six or more of them.
        String digits = digitRun(sought);
        return digits != null && squeezed.contains(digits);
    }

    /**
     * The longest run of digits in an identifier, when it has six or more and is not the whole
     * identifier, or null.
     */
    static String digitRun(String id) {
        String longest = null;
        Matcher run = Pattern.compile("\\d+").matcher(id == null ? "" : id);
        while (run.find()) {
            if (run.group().length() >= SHORTEST_ID_SOUGHT && (longest == null || run.group().length() > longest.length())) {
                longest = run.group();
            }
        }
        return longest == null || longest.equals(id) ? null : longest;
    }

    /**
     * Text brought to one form before it is compared: Unicode compatibility normalisation, which
     * turns a fullwidth or otherwise styled digit or letter into the plain one.
     */
    public static String oneForm(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFKC);
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
    public static boolean holdsId(String typed, String lastFour, Predicate<String> is) {
        if (Texts.blank(typed) || Texts.blank(lastFour)) {
            return false;
        }
        String text = oneForm(typed);
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
                    String candidate = form.substring(start, stop);
                    if (is.test(candidate) || digitsOfAnId(candidate, is)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * True when a run of digits is the digits of an identifier that has one or two letters in front
     * of them, as most member IDs do: the letters are what the text left out, so each is tried. An
     * ID with more letters in front is not found this way.
     */
    private static boolean digitsOfAnId(String candidate, Predicate<String> is) {
        if (candidate.length() < SHORTEST_ID_SOUGHT || !candidate.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        for (String letters : List.of(LETTERS, LETTERS.toLowerCase(Locale.ROOT))) {
            for (int first = 0; first < letters.length(); first++) {
                if (is.test(letters.charAt(first) + candidate)) {
                    return true;
                }
                for (int second = 0; second < letters.length(); second++) {
                    if (is.test("" + letters.charAt(first) + letters.charAt(second) + candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * True when a text holds a name as a word, in any case, and the name has {@link #SHORTEST_NAME_ALONE}
     * letters or more. A shorter one (May, Will, An, Do, Le) is an ordinary word in an honest note,
     * and is looked for only beside the other name ({@link #holdsBoth}).
     */
    private static boolean holdsName(String text, String name) {
        String sought = Texts.trimToNull(oneForm(name));
        return sought != null && letters(sought) >= SHORTEST_NAME_ALONE && holdsWord(text, sought);
    }

    /** True when a text holds the first and the last name standing together, in either order: May An, An May, An, May. */
    private static boolean holdsBoth(String text, String first, String last) {
        String given = Texts.trimToNull(oneForm(first));
        String family = Texts.trimToNull(oneForm(last));
        if (given == null || family == null || letters(given) < SHORTEST_NAME_SOUGHT || letters(family) < SHORTEST_NAME_SOUGHT) {
            return false;
        }
        String between = "[\\s,]+";
        return holdsWord(text, given, between, family) || holdsWord(text, family, between, given);
    }

    /**
     * True when a text holds one part of a hyphenated or two-word surname, of {@link #SHORTEST_NAME_ALONE}
     * letters or more, as a word: a member is often called by one half of such a name.
     */
    private static boolean holdsSurnamePart(String text, String last) {
        String family = Texts.trimToNull(oneForm(last));
        if (family == null) {
            return false;
        }
        String[] parts = family.split("[\\s\\-]+");
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (letters(part) >= SHORTEST_NAME_ALONE && holdsWord(text, part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsWord(String text, String sought) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(sought) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static boolean holdsWord(String text, String one, String between, String other) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(one) + between + Pattern.quote(other) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static int letters(String value) {
        return (int) value.codePoints().filter(Character::isLetter).count();
    }

    /**
     * True when a text holds a date written in one of the usual ways: 1961-07-23, 19610723,
     * 07231961, 23071961, 07/23/1961, 7/23/61, 23.07.1961, and with the month's name: July 23,
     * 1961, 23 Jul 1961, 23-July-61, 23Jul1961, Jul 23rd 1961, Jul. 23rd, 1961, 23 July '61.
     */
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
        // The compact forms with the month or the day first, as some forms and systems write it.
        forms.add(months.get(0) + days.get(0) + year);
        forms.add(days.get(0) + months.get(0) + year);
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
        return holdsNamedMonthDate(text, date);
    }

    private static final String MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?"
            + "|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String ORDINAL = "(?:st|nd|rd|th)?";
    /** A two-digit year may follow an apostrophe, straight or curly: 23 July '61. */
    private static final String YEAR = "['\\u2019]?(\\d{2}|\\d{4})(?!\\d)";
    /** Day, month name, year: 23 Jul 1961, 23-July-61, 23Jul1961, 23rd of July 1961, 23 July '61. */
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])(\\d{1,2})" + ORDINAL + "[-/. ]?(?:of )?" + MONTH
            + "[-/. ,]{0,2}" + YEAR, Pattern.CASE_INSENSITIVE);
    /** Month name, day, year: July 23, 1961, Jul 23 1961, Jul-23-61, July 23rd, 1961, Jul. 23rd, 1961. */
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])" + MONTH + "[-/. ,]{0,2}(\\d{1,2})" + ORDINAL
            + "[-/. ,]{1,2}" + YEAR, Pattern.CASE_INSENSITIVE);

    private static boolean holdsNamedMonthDate(String text, LocalDate date) {
        Matcher dayFirst = DAY_MONTH_YEAR.matcher(text);
        while (dayFirst.find()) {
            if (same(date, dayFirst.group(1), dayFirst.group(2), dayFirst.group(3))) {
                return true;
            }
        }
        Matcher monthFirst = MONTH_DAY_YEAR.matcher(text);
        while (monthFirst.find()) {
            if (same(date, monthFirst.group(2), monthFirst.group(1), monthFirst.group(3))) {
                return true;
            }
        }
        return false;
    }

    /** True when a day, a month's name and a year of two or four digits are the date. */
    private static boolean same(LocalDate date, String day, String monthName, String year) {
        String month = monthName.substring(0, 3).toLowerCase(Locale.ROOT);
        int number = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(month) + 1;
        String fullYear = String.format(Locale.ROOT, "%04d", date.getYear());
        boolean yearMatches = year.length() == 4 ? year.equals(fullYear) : year.equals(fullYear.substring(2));
        return number == date.getMonthValue() && Integer.parseInt(day) == date.getDayOfMonth() && yearMatches;
    }

    private static boolean holdsAddress(String text, PostalAddress address) {
        if (address == null) {
            return false;
        }
        if (holdsCityWithPostalCode(text, address)) {
            return true;
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

    /**
     * True when a text holds the city of the address and its postal code, both as words. Either
     * alone says little (a city is named in honest notes, and five digits are a code as often as a
     * postal code); the two together place the member. A postal code is compared by its first five
     * characters, so 00077 is found for 00077-1234 and the other way round.
     */
    private static boolean holdsCityWithPostalCode(String text, PostalAddress address) {
        String city = Texts.trimToNull(oneForm(address.city()));
        String postal = Texts.trimToNull(oneForm(address.postalCode()));
        if (city == null || postal == null || letters(city) < SHORTEST_NAME_SOUGHT) {
            return false;
        }
        String start = postal.replaceAll("[^A-Za-z0-9]", "");
        start = start.length() > 5 ? start.substring(0, 5) : start;
        if (start.length() < 3) {
            return false;
        }
        String squeezedCity = city.replaceAll("\\s+", " ");
        return holdsWord(text.replaceAll("\\s+", " "), squeezedCity)
                && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(start), Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static String alphanumerics(String value) {
        return value == null ? "" : oneForm(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
