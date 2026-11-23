package org.p32h.interop.epa.pave.service.config;

import java.util.List;

/**
 * The body a collection request writes, for a run sent "as the collection writes it" (D-313): the
 * request a run of the operation would use, its raw body as a template, and the text of every
 * script of its collection, which is read for the names a script would have set and never run.
 *
 * @param requestName the request, with its folders, or null when the run would use none
 * @param source      where the run would go, in words, as a refusal names it
 * @param template    the raw body, each credential typed into it standing as a placeholder of its
 *                    own (as {@link PreparedCall#savedBody} gives it), or null when there is none
 * @param scripts     the source of each script of the collection, as exported. Never shown, logged
 *                    or sent: a script can hold a credential typed into it
 * @param typed       true when the run would go to an address typed in the settings, which carries
 *                    no body of a request
 */
public record WrittenBody(String requestName, String source, String template, List<String> scripts, boolean typed) {

    public WrittenBody {
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
    }

    /** A run that would use no collection request: nothing written to send. */
    public static WrittenBody none(String source, boolean typed) {
        return new WrittenBody(null, source, null, List.of(), typed);
    }

    /** True when a script of the collection names the placeholder in quotes, as {@code pm.environment.set("name", ...)} does. */
    public boolean setByAScript(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (String script : scripts) {
            if (script == null) {
                continue;
            }
            // An index search, never a parse: the script is text this tool does not run (D-313).
            if (script.contains("\"" + name + "\"") || script.contains("'" + name + "'") || script.contains("`" + name + "`")) {
                return true;
            }
        }
        return false;
    }

    /** Names the request only: the body and the scripts can hold a credential or member data. */
    @Override
    public String toString() {
        return "WrittenBody[" + (requestName == null ? "no request" : "a request") + ", scripts " + scripts.size() + "]";
    }
}
