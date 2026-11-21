package org.p32h.interop.epa.pave.service.run;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.springframework.stereotype.Component;

/**
 * Fills the placeholders of a body of the tester's own before the identifiers it names are read
 * from it ({@link RunInputs#common}). A body loaded from a collection names its member as
 * {@code {{memberId}}}, so the body as typed names nobody, and a note naming that member would be
 * checked against nothing.
 *
 * <p>The values come from the gateway's own resolution: the values typed for the run, then the
 * active environment, then application.yml. The form checks run before a call is prepared, and are
 * made by the protocol services with a clock and nothing else, so the gateway is handed to them here,
 * once, when the application starts.
 *
 * <p>Until it is handed over, and when any placeholder has no value, the body is read as typed. A
 * placeholder with no value is refused when the call is prepared, and this check is no place to
 * refuse it a second time.
 */
@Component
public class OwnBodyPlaceholders {

    private static final AtomicReference<BiFunction<String, Map<String, String>, ResolvedText>> FILLING = new AtomicReference<>();

    private final BiFunction<String, Map<String, String>, ResolvedText> mine;

    public OwnBodyPlaceholders(OperationGateway gateway) {
        this.mine = gateway::resolve;
        FILLING.set(mine);
    }

    /** Hands the filling back, unless another application started since has taken it. */
    @PreDestroy
    public void close() {
        FILLING.compareAndSet(mine, null);
    }

    /**
     * The body as it will be sent, or as typed when it cannot be filled whole. Never throws: a
     * resolution that fails leaves the body as typed, and the call then fails where it is prepared.
     */
    static String asSent(String body, Map<String, String> runValues) {
        BiFunction<String, Map<String, String>, ResolvedText> filling = FILLING.get();
        if (filling == null || Texts.blank(body) || !body.contains("{{")) {
            return body;
        }
        try {
            ResolvedText filled = filling.apply(body, runValues == null ? Map.of() : runValues);
            return filled != null && filled.resolved() ? filled.value() : body;
        } catch (RuntimeException unresolvable) {
            // Nothing of the exception is kept: its message can quote the body.
            return body;
        }
    }
}
