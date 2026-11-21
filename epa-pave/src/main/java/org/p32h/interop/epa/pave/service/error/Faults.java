package org.p32h.interop.epa.pave.service.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Describes a failure of this tool for the server log and, as one line, for the tester.
 *
 * <p>Exception messages are not logged or returned. A message from an HTTP client or a JSON parser
 * can quote a url, a body or a field value, and any of those can be member data. What is logged is
 * the chain of exception types with the first frames of each, which is enough to find the defect
 * and can carry nothing a member would recognise.
 *
 * <p>The one exception is {@link #detail}: a few throwables whose message the Java runtime or the
 * IDE's compiler writes about the program itself, never from request data. Without that text a
 * missing class or a class the IDE could not compile is reported as a bare type, and the person
 * who meets it cannot say which class it was.
 *
 * <p>The chain comes first, on one line, because a person who pastes only the first line of a log
 * entry, or reads only the screen, has then still given the cause. That is how a fault on an
 * operator's workstation went unfound (DECISIONS.md, D-275).
 */
public final class Faults {

    /** How many exceptions of a chain are named. A real chain is far shorter; this only bounds a pathological one. */
    private static final int CHAIN_LIMIT = 12;

    /** How many exceptions of a chain get frames in the log, and how many frames each. */
    private static final int FRAMED_LIMIT = 6;
    private static final int FRAMES_EACH = 8;

    /** The longest detail given. A missing class or method fits easily; a compiler's list of problems is cut. */
    static final int DETAIL_LIMIT = 300;

    /** What the Eclipse compiler writes into a class it could not compile, which the IDE then starts all the same. */
    private static final String UNRESOLVED_COMPILATION = "Unresolved compilation problem";

    /**
     * What to do about a failure of this tool, under the reference and the cause the screen shows.
     * The cause is named on screen because the server log, which holds the rest, can be on a
     * machine the person who meets the failure does not look at.
     */
    public static final String REPORT_ACTION = "Copy the reference and the cause below with the button and report them with what you "
            + "were doing. The server log holds the rest under the same reference.";

    private Faults() {
    }

    /**
     * The log text of a failure: first the line {@link #cause} gives, then the types and the first
     * frames of each exception of the chain.
     */
    public static String describe(Throwable error) {
        StringBuilder out = new StringBuilder(cause(error));
        List<Throwable> chain = chain(error, FRAMED_LIMIT);
        for (int depth = 0; depth < chain.size(); depth++) {
            Throwable t = chain.get(depth);
            out.append(depth == 0 ? System.lineSeparator() : " <- caused by ").append(t.getClass().getName());
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, FRAMES_EACH); i++) {
                out.append(System.lineSeparator()).append("    at ").append(frames[i]);
            }
        }
        return out.toString();
    }

    /**
     * The cause of a failure on one line: every exception type of the chain, outermost first, and
     * then the detail of the first of them that may give one, for example
     * {@code jakarta.servlet.ServletException <- java.lang.NoClassDefFoundError; java.lang.NoClassDefFoundError says: org/example/Missing}.
     * It holds no stack frame and no message other than a {@link #detail}.
     */
    public static String cause(Throwable error) {
        if (error == null) {
            return "";
        }
        List<Throwable> chain = chain(error, CHAIN_LIMIT + 1);
        StringBuilder out = new StringBuilder();
        String detail = null;
        Throwable detailed = null;
        for (int i = 0; i < Math.min(chain.size(), CHAIN_LIMIT); i++) {
            Throwable t = chain.get(i);
            out.append(i == 0 ? "" : " <- ").append(t.getClass().getName());
            if (detail == null) {
                detail = detail(t);
                detailed = t;
            }
        }
        if (chain.size() > CHAIN_LIMIT) {
            out.append(" <- ...");
        }
        if (detail != null) {
            out.append("; ").append(detailed.getClass().getName()).append(" says: ").append(detail);
        }
        return out.toString();
    }

    /**
     * The message of a throwable whose message is about the program and never about a request, made
     * one line and cut at {@link #DETAIL_LIMIT} characters; null for every other throwable, and for
     * one with no message.
     */
    static String detail(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank() || !mayGiveDetail(t)) {
            return null;
        }
        String line = message.replaceAll("\\s+", " ").replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() <= DETAIL_LIMIT ? line : line.substring(0, DETAIL_LIMIT - 3) + "...";
    }

    private static boolean mayGiveDetail(Throwable t) {
        // An initializer's failure carries, in its text, the message of whatever the static
        // initializer threw, and that can be anything, including a parser's quote of a file.
        if (t instanceof ExceptionInInitializerError) {
            return false;
        }
        // The class loader and the verifier write these: a class, method or field name, a library
        // name with the path it was looked for on, a version of a class file. All of it is about
        // how this program was built or started, which is what was missing on the workstation.
        if (t instanceof LinkageError) {
            return true;
        }
        // Its message is the name of the class that could not be found. This tool never loads a
        // class by a name taken from a request, so that name is one the program itself asked for.
        if (t instanceof ClassNotFoundException) {
            return true;
        }
        // "Java heap space", "Metaspace", a thread that could not be created: the state of the
        // machine. Only without a cause, because InternalError and UnknownError, given a cause and
        // no message, take that cause's own text, which could be any message at all.
        if (t instanceof VirtualMachineError) {
            return t.getCause() == null;
        }
        // The Eclipse compiler writes this into the body of a class it could not compile. The text
        // is its list of problems with the source, so it names the program's own code.
        return t.getClass() == Error.class && t.getMessage().startsWith(UNRESOLVED_COMPILATION);
    }

    /** The exceptions of a chain, outermost first, at most {@code limit}, each once even if the chain loops. */
    private static List<Throwable> chain(Throwable error, int limit) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && out.size() < limit && seen.add(t); t = t.getCause()) {
            out.add(t);
        }
        return out;
    }
}
