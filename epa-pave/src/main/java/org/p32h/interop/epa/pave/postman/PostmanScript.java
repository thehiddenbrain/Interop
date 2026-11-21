package org.p32h.interop.epa.pave.postman;

/**
 * A script found in a collection. It is kept as text so a tester can read what Postman would have
 * computed. It is never run, compiled or evaluated in any way.
 *
 * @param listen when Postman would run it: {@link #PRE_REQUEST} or {@link #TEST}
 * @param from   where it is attached, in words: this request, a named folder, or the collection.
 *               Postman runs the scripts of every ancestor too, so a request inherits them
 * @param source the script text exactly as exported
 */
public record PostmanScript(String listen, String from, String source) {

    /** Runs before the request is sent, so a value it computes is missing from what this tool sends. */
    public static final String PRE_REQUEST = "prerequest";

    /** Runs after the response arrives. It can store a value that a later request needs. */
    public static final String TEST = "test";

    public boolean preRequest() {
        return PRE_REQUEST.equals(listen);
    }

    /** Prints where the script sits, never its text, which can hold a credential typed into it. */
    @Override
    public String toString() {
        return "PostmanScript[" + listen + ", " + from + "]";
    }
}
