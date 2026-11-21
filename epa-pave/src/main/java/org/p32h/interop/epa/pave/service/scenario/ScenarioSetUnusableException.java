package org.p32h.interop.epa.pave.service.scenario;

import java.util.List;
import org.p32h.interop.epa.pave.service.error.BadInputException;

/**
 * A protocol's mock scenario files cannot be used, so the mock did not answer. Nothing was sent
 * for the call that met it.
 *
 * <p>It is a fault of the tool's own scenario files, which a tester may have edited in the
 * directory {@code app.scenario-dir} names while the tool runs. It is never a fault of the request
 * or of a suite row. It is its own type so that a caller can tell it apart from an input that
 * cannot be used: a single run answers it with HTTP 422 like any {@link BadInputException}, while
 * a suite row or a chain must not report the row as invalid for it.
 */
public class ScenarioSetUnusableException extends BadInputException {

    private final String protocolDirectory;
    private final List<String> problems;

    public ScenarioSetUnusableException(String protocolDirectory, List<String> problems) {
        super("The mock scenarios for " + protocolDirectory + " cannot be used, so nothing was answered. " + String.join(" ", problems),
                "Put the files named right, then run again. The scenario list for " + protocolDirectory + " shows the same problems.");
        this.protocolDirectory = protocolDirectory;
        this.problems = List.copyOf(problems);
    }

    /** The protocol directory whose files cannot be used, such as {@code pas}. */
    public String protocolDirectory() {
        return protocolDirectory;
    }

    /** Each file and its reason, as the scenario list shows them. */
    public List<String> problems() {
        return problems;
    }
}
