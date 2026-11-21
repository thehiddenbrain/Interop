package org.p32h.interop.epa.pave.postman;

import java.util.List;

/**
 * A name an uploaded file supplies, as a sentence of this tool quotes it back.
 *
 * <p>A Postman file can give a request, a folder, an example, a variable, a header or the
 * collection itself a name of any length, and a sentence that quoted it whole grew with it: a
 * request name of a million characters made one upload warning a million characters long and the
 * upload answer two megabytes. So every sentence quotes a long name by its start and its length.
 * A name of ordinary length is quoted whole, exactly as before.
 *
 * <p>Only sentences go through here. Where a screen shows a name as the tester's own label, in a
 * list or a heading, the API gives it whole: it is what the tester typed and what tells two
 * requests apart.
 */
public final class Names {

    /** How many characters of a long name a sentence quotes before it gives the length instead. */
    public static final int SHOWN = 60;

    private Names() {
    }

    /**
     * A name as a sentence quotes it: its first {@value #SHOWN} characters and its length, when that
     * is shorter than the name itself; otherwise the name whole. A name of up to 89 characters is
     * therefore never shortened, because the start and the length would be as long and would lose
     * its end. Characters are counted as a reader counts them, a character outside the basic plane
     * as one, and none is cut in half, so the start stays text a browser can show.
     */
    public static String shown(String name) {
        if (name == null || name.length() <= SHOWN) {
            return name;
        }
        int length = name.codePointCount(0, name.length());
        String suffix = "... (a name of " + length + " characters)";
        if (length <= SHOWN + suffix.length()) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, SHOWN)) + suffix;
    }

    /**
     * A request named with the folders it sits in, outermost first, each name held to
     * {@link #shown(String)} on its own. The path is the tool's own joining of names the file
     * gives, so a request three folders deep whose names are each ordinary reads as before, however
     * long the joined line is.
     */
    public static String path(List<String> folders, String name) {
        if (folders == null || folders.isEmpty()) {
            return shown(name);
        }
        StringBuilder out = new StringBuilder();
        for (String folder : folders) {
            out.append(shown(folder)).append(" / ");
        }
        return out.append(shown(name)).toString();
    }
}
