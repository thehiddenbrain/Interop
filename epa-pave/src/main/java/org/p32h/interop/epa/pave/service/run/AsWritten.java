package org.p32h.interop.epa.pave.service.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.WrittenBody;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * A run sent "as the collection writes it" (D-313): the body of the collection request the run
 * uses, with the environment's values put in, is sent as Postman would send it, down the path a
 * body of the tester's own takes. The page never holds the body: it asks for this, and the body
 * is taken here from the stored collection.
 *
 * <p>What a body written for Postman needs and this tool cannot give is said before anything is
 * sent, never guessed: a placeholder nothing defines, one a script of the collection would have
 * set (scripts are never run; their text is searched for the name, never parsed), and a dynamic
 * value of Postman's that this tool does not make.
 *
 * @param written the request and its body, as the gateway found them
 */
public record AsWritten(WrittenBody written) {

    /** The field a refusal names, as the run request posts it. */
    public static final String FIELD = "asWritten";

    /** The dynamic values the resolver makes afresh at each send, as Postman does. */
    static final List<String> DYNAMIC = List.of("$guid", "$randomUUID", "$timestamp", "$isoTimestamp", "$randomInt");

    /**
     * The body to send for a run that asks for it, or null for a run that does not.
     *
     * @throws ValidationException when the run also carries a body of its own, or when the request it
     *                             would use has no body to send as written
     */
    public static AsWritten take(org.p32h.interop.epa.pave.service.run.RunRequest request, EpaOperation operation, OperationGateway gateway) {
        if (request == null || !Boolean.TRUE.equals(request.asWritten())) {
            return null;
        }
        if (Texts.present(request.rawBody())) {
            throw ValidationException.of(FIELD, "A run sends a body of your own or the collection's body as written, not both. Nothing was sent.");
        }
        WrittenBody written = gateway.writtenBody(operation, request.request());
        if (written == null || written.requestName() == null) {
            throw ValidationException.of(FIELD, written != null && written.typed()
                    ? "This step goes to the address typed in the settings, which carries no body of a collection request. Choose a "
                    + "request under Request to send its body as written. Nothing was sent."
                    : "No request of the stored collections is in use for this step, so there is no body to send as written. Nothing was sent.");
        }
        if (Texts.blank(written.template())) {
            throw ValidationException.of(FIELD, "The request \"" + written.requestName() + "\" has no body of its own to send as written. "
                    + "Nothing was sent.");
        }
        return new AsWritten(written);
    }

    /** The collection's body, as the template a run of the tester's own would carry. */
    public String template() {
        return written.template();
    }

    /**
     * Refuses the body before it is sent when a placeholder in it has no value, each placeholder
     * named with what is known of why: set by a script, a dynamic value this tool does not make, or
     * defined nowhere. The page takes a value for each in "Values for this run".
     */
    public void requireFilled(PreparedCall call, ResolvedText body) {
        if (body == null || body.resolved()) {
            return;
        }
        throw new UnresolvedVariablesException(call.operation(), body.unresolved(), call.environment() == null ? null
                : call.environment().name(), "the body of the request \"" + written.requestName() + "\"", reasons(body.unresolved()));
    }

    /** Why each placeholder has no value, for those this tool can say more of than that nothing defines them. */
    Map<String, String> reasons(List<String> unresolved) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String name : unresolved) {
            if (name.startsWith("$")) {
                reasons.put(name, "Postman makes {{" + name + "}} itself, and PAVE makes only " + String.join(", ", DYNAMIC.stream()
                        .map(each -> "{{" + each + "}}").toList()) + ". Type a value for it here.");
            } else if (written.setByAScript(name)) {
                reasons.put(name, "a script of the collection sets {{" + name + "}}, and PAVE never runs a script. Type its value here, "
                        + "or add it under Values of the environment.");
            }
        }
        return reasons;
    }

    /** The lines a preview adds for placeholders with no value, one per placeholder, with its reason where there is one. */
    public List<String> previewLines(ResolvedText body) {
        if (body == null || body.resolved()) {
            return List.of();
        }
        Map<String, String> reasons = reasons(body.unresolved());
        List<String> lines = new ArrayList<>();
        for (String name : body.unresolved()) {
            lines.add("No value for {{" + name + "}}" + (reasons.containsKey(name) ? ": " + reasons.get(name) : ". Type one in Values for this "
                    + "run, or add it under Values of the environment.") + " A run is refused until it has one.");
        }
        return lines;
    }

    /**
     * What a result and a preview say about a body sent as written.
     *
     * @param laterStep true for a step after the coverage check, where something may have been handed on
     */
    public List<String> notes(boolean laterStep) {
        List<String> notes = new ArrayList<>();
        notes.add("The body is the collection's own, from the request \"" + written.requestName() + "\", with the environment's values put in. "
                + "The member, provider and codes in it were sent, not those of the Case column.");
        if (laterStep) {
            notes.add("What the step before handed on was not put into it.");
        }
        Set<String> dynamic = new LinkedHashSet<>();
        for (String name : DYNAMIC) {
            if (template().contains("{{" + name + "}}")) {
                dynamic.add("{{" + name + "}}");
            }
        }
        if (!dynamic.isEmpty()) {
            notes.add("Made afresh for this send, as Postman does: " + String.join(", ", dynamic) + ".");
        }
        return notes;
    }

    /**
     * The notes of a body of the tester's own, said of the collection's body: they were written for
     * a body typed on the screen, and "the body you supplied" would be untrue here.
     */
    public static List<String> reworded(List<String> notes) {
        List<String> out = new ArrayList<>();
        for (String note : notes) {
            out.add(note.replace("The body you supplied", "The collection's body").replace("the body you supplied", "the collection's body"));
        }
        return out;
    }

    @Override
    public String toString() {
        return "AsWritten[" + written + "]";
    }
}
