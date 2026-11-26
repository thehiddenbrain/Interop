package org.p32h.interop.epa.pave.postman;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.VariableUse;

/**
 * What a template resolved to, kept in pieces.
 *
 * <p>The pieces are kept, not just the two finished strings, because a url needs more work after
 * its placeholders are resolved: its single-brace tokens are filled, and a subscription id may have
 * to be bound to the placeholder that holds its place. Both need to know which stretch of the text
 * came from which variable, and both must leave a secret alone. Working on the finished string
 * would mean looking for tokens inside a secret's value.
 */
public final class Resolution {

    /** What one piece is. */
    public enum Kind {
        /** Text of the template itself. */
        LITERAL,
        /** Text that came from a variable and may be shown. */
        VALUE,
        /** The value of a secret. Sent as it is, shown as a mask, and never searched for tokens. */
        SECRET,
        /** A placeholder that has no value. */
        MISSING
    }

    /**
     * @param kind    what the piece is
     * @param name    the variable the piece came from, or null for text of the template
     * @param send    the text to send. Null for a missing piece
     * @param display the text to show. For a secret this is its mask. Null for a missing piece
     * @param whole   true when the piece is the entire value of the variable it names. False for a
     *                stretch of a variable whose value is itself built from other variables
     */
    public record Piece(Kind kind, String name, String send, String display, boolean whole) {

        static Piece literal(String text) {
            return new Piece(Kind.LITERAL, null, text, text, false);
        }

        static Piece partOf(String owner, String text) {
            return new Piece(Kind.VALUE, owner, text, text, false);
        }

        static Piece value(String name, String value) {
            return new Piece(Kind.VALUE, name, value, value, true);
        }

        static Piece secret(String name, String value) {
            return new Piece(Kind.SECRET, name, value, Secrets.mask(name), true);
        }

        static Piece missing(String name) {
            return new Piece(Kind.MISSING, name, null, null, true);
        }

        /** True when nothing of the piece may be read by code that analyses the text: it is a secret, or it has no value. */
        public boolean opaque() {
            return kind == Kind.SECRET || kind == Kind.MISSING;
        }

        /** Prints what the piece is and never its text, which can be a secret. */
        @Override
        public String toString() {
            return "Piece[" + kind + (name == null ? "" : " " + name) + "]";
        }
    }

    private final String template;
    private final List<Piece> pieces;
    private final List<VariableUse> variables;
    private final List<String> unresolved;
    private final Map<String, String> reasons;
    private final Map<String, String> explanations;

    Resolution(String template, List<Piece> pieces, List<VariableUse> variables, List<String> unresolved, Map<String, String> reasons,
               Map<String, String> explanations) {
        this.template = template;
        this.pieces = List.copyOf(pieces);
        this.variables = List.copyOf(variables);
        this.unresolved = List.copyOf(unresolved);
        this.reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
        this.explanations = Collections.unmodifiableMap(new LinkedHashMap<>(explanations));
    }

    public String template() {
        return template;
    }

    public List<Piece> pieces() {
        return pieces;
    }

    /** Every variable met while resolving, the nested ones included, each as it resolved. */
    public List<VariableUse> variables() {
        return variables;
    }

    /**
     * The names that no layer could supply. These are the variables at the root of the problem: for
     * a variable whose value needs another one that is missing, the missing one is listed, because
     * that is the one a tester can type or define.
     */
    public List<String> unresolved() {
        return unresolved;
    }

    /**
     * Why an unresolved name has no value, for the names where there is more to say than "it is not
     * defined anywhere": it is defined but blank, it refers to itself, or the operating system
     * variable it points at is not set. A name with no entry is simply not defined.
     */
    public Map<String, String> reasons() {
        return reasons;
    }

    /** One sentence about every variable that did not resolve, the ones in {@link #reasons()} included. */
    public Map<String, String> explanations() {
        return explanations;
    }

    public boolean complete() {
        return unresolved.isEmpty() && pieces.stream().noneMatch(piece -> piece.kind() == Kind.MISSING);
    }

    /** The text to send, or null when anything in it has no value. */
    public String send() {
        return complete() ? join(Piece::send) : null;
    }

    /** The text to show, secrets masked, or null when anything in it has no value. */
    public String display() {
        return complete() ? join(Piece::display) : null;
    }

    /** The text to show with whatever did resolve filled in and every placeholder that did not left as written. */
    public String shown() {
        return join(piece -> piece.kind() == Kind.MISSING ? "{{" + piece.name() + "}}" : piece.display());
    }

    public ResolvedText text() {
        return new ResolvedText(template, display(), send(), variables, unresolved);
    }

    private String join(Function<Piece, String> form) {
        return pieces.stream().map(form).collect(Collectors.joining());
    }

    /** Prints whether it resolved and the names that did not. Never a resolved form, which holds values. */
    @Override
    public String toString() {
        return "Resolution[" + (complete() ? "resolved" : "unresolved " + unresolved) + "]";
    }
}
