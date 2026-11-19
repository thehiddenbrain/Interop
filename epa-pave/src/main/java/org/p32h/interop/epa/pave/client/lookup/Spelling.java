package org.p32h.interop.epa.pave.client.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place a field may sit in a lookup response, written as a path of names from the record:
 * {@code dob}, {@code name.first}, {@code name.given[0]}.
 *
 * <p>Names are compared ignoring letter case, underscores, hyphens and spaces, so one entry stands
 * for memberId, member_id, MemberID and MEMBER-ID. The contracts of the lookup APIs are not final,
 * and which of those a service writes is the kind of thing that changes between a draft and a
 * release. What a name means is never loosened: subscriberId is not a way of writing memberId.
 *
 * @param written the spelling as it is listed in a mapping, for messages and tests
 * @param steps   the path, one step per name
 */
record Spelling(String written, List<Step> steps) {

    /**
     * One name on the path.
     *
     * @param name  the name in its compared form
     * @param index the position to take when the name holds a list, or -1 when it is not read as one
     */
    record Step(String name, int index) {
    }

    private static final Pattern STEP = Pattern.compile("([^.\\[\\]]+)(?:\\[(\\d+)])?");

    static Spelling of(String written) {
        List<Step> steps = new ArrayList<>();
        for (String part : written.split("\\.", -1)) {
            Matcher matcher = STEP.matcher(part);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Not a field spelling: " + written);
            }
            steps.add(new Step(normalise(matcher.group(1)), matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2))));
        }
        return new Spelling(written, List.copyOf(steps));
    }

    /** Spellings in order of preference. */
    static List<Spelling> list(String... written) {
        List<Spelling> out = new ArrayList<>();
        for (String one : written) {
            out.add(of(one));
        }
        return List.copyOf(out);
    }

    /** Every part under every container, container by container: {@code address.city}, {@code address.town}, and so on. */
    static List<Spelling> under(List<String> containers, String... parts) {
        List<Spelling> out = new ArrayList<>();
        for (String container : containers) {
            for (String part : parts) {
                out.add(of(container + "." + part));
            }
        }
        return List.copyOf(out);
    }

    static List<Spelling> join(List<Spelling> first, List<Spelling> second) {
        List<Spelling> out = new ArrayList<>(first);
        out.addAll(second);
        return List.copyOf(out);
    }

    /**
     * True for a path of more than one name. Such a path is specific enough to be looked for beside
     * the record as well as inside it. A single name such as {@code id} or {@code state} is not: in
     * the object that wraps a record it usually describes the response, not the member.
     */
    boolean nested() {
        return steps.size() > 1;
    }

    /** The form in which two names are compared: lower case, with underscores, hyphens and spaces removed. */
    static String normalise(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '_' && c != '-' && !Character.isWhitespace(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
