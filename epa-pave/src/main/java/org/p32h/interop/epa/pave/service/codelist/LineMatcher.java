package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * The one rule by which a line of business is matched, as the code list directory documents it.
 *
 * <p>A requested line matches a row when it equals a line the row names, compared the way headers
 * are: case, spaces, underscores, hyphens and dots ignored. A cell that names several lines,
 * separated by a comma, a semicolon, a slash or a bar, belongs to each. An alias maps a name as
 * the member API gives it to the names the list uses.
 *
 * <p>Nothing here is fuzzy. "Medicare" is not "Medicare Advantage", and a name is never matched by
 * its beginning or by a part of it: THP Medicare and Medicaid and HPHC Commercial have different
 * lists and different logic, and a row offered under the wrong one is a wrong answer that looks
 * like a right one.
 */
final class LineMatcher {

    private static final Pattern SEPARATORS = Pattern.compile("[,;/|]");

    private final Map<String, Set<String>> aliases = new HashMap<>();

    /** @param configured {@code codelist.line-of-business-aliases}: a name the member API uses, and the names the list uses for it */
    LineMatcher(Map<String, List<String>> configured) {
        if (configured != null) {
            configured.forEach((name, listNames) -> {
                String key = CodeListText.key(name);
                if (!key.isEmpty() && listNames != null) {
                    Set<String> keys = aliases.computeIfAbsent(key, k -> new LinkedHashSet<>());
                    listNames.forEach(listName -> names(listName).forEach(part -> keys.add(CodeListText.key(part))));
                }
            });
        }
    }

    /**
     * The lines a cell names, each as written. Empty when the cell is blank or holds nothing that
     * is a name, such as a lone dash: such a row states no line.
     */
    static List<String> names(String cell) {
        List<String> out = new ArrayList<>();
        if (Texts.blank(cell)) {
            return out;
        }
        for (String part : SEPARATORS.split(cell)) {
            String name = CodeListText.clean(part);
            if (name != null && !CodeListText.key(name).isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /** The compared forms of the lines a cell names. */
    static Set<String> keys(String cell) {
        Set<String> out = new LinkedHashSet<>();
        names(cell).forEach(name -> out.add(CodeListText.key(name)));
        return out;
    }

    /**
     * Every compared form a requested line stands for: itself, and what its aliases map it to. An
     * alias works in one direction only, from the member API's name to the list's, and is not
     * followed a second time. Empty when the request names nothing.
     *
     * <p>The requested line is one name and is never split. A member's line written
     * "Medicaid/CHIP" is that whole name, and reaches the list's rows through an alias or not at all.
     */
    Set<String> wanted(String requested) {
        Set<String> out = new LinkedHashSet<>();
        String key = CodeListText.key(requested);
        if (!key.isEmpty()) {
            out.add(key);
            out.addAll(aliases.getOrDefault(key, Set.of()));
        }
        return out;
    }
}
