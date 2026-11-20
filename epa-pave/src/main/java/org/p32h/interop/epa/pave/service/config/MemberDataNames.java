package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.postman.PostmanVariable;

/**
 * Names of variables that read as member data: a member, patient or subscriber identifier, a
 * person's name, a birth date. Decided by the name alone, never by the value, as a secret is.
 *
 * <p>The configuration store keeps what is uploaded as it is, because it is what the tool sends
 * from, so a variable of this kind sits on disk with whatever value it was exported with. The
 * upload says so. A name that describes where such data is kept rather than the data, such as
 * the system of a member identifier or a url, is not caught.
 */
final class MemberDataNames {

    private static final Set<String> PERSON = Set.of("member", "patient", "subscriber", "beneficiary", "insured", "enrollee");
    private static final Set<String> ABOUT_A_PERSON = Set.of("id", "identifier", "number", "no", "num", "name", "first", "last", "given",
            "family", "surname", "birth", "dob", "birthdate");
    private static final Set<String> WHERE_NOT_WHAT = Set.of("system", "url", "uri", "type", "path", "endpoint", "field", "key", "column",
            "format", "label", "header");

    private MemberDataNames() {
    }

    /** True when the name reads as member data. */
    static boolean readsAsMemberData(String name) {
        if (name == null) {
            return false;
        }
        List<String> words = new ArrayList<>();
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        if (words.stream().anyMatch(WHERE_NOT_WHAT::contains)) {
            return false;
        }
        String joined = String.join("", words);
        boolean birth = words.contains("dob") || joined.contains("birthdate") || joined.contains("dateofbirth") || words.contains("birthday");
        boolean personName = joined.contains("firstname") || joined.contains("lastname") || joined.contains("givenname")
                || joined.contains("familyname") || words.contains("surname");
        boolean aboutAPerson = words.stream().anyMatch(PERSON::contains) && words.stream().anyMatch(ABOUT_A_PERSON::contains);
        return birth || personName || aboutAPerson;
    }

    /** The names, in file order and each once, of the variables that read as member data. Disabled ones too: they are in the file. */
    static List<String> in(List<PostmanVariable> variables) {
        Set<String> names = new LinkedHashSet<>();
        for (PostmanVariable variable : variables) {
            if (variable.hasValue() && readsAsMemberData(variable.key())) {
                names.add(variable.key());
            }
        }
        return new ArrayList<>(names);
    }
}
