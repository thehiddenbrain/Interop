package org.p32h.interop.epa.pave.service.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last word on a throwable that nothing caught, on any thread of the process.
 *
 * <p>Without it the Java runtime prints such a throwable itself, to standard error and so into the
 * log: "Exception in thread", every message of the chain, every "Caused by:" and every frame. A
 * message can quote member data (see {@link Faults}), and a thread nobody thought of, or a path
 * nobody foresaw on a known one, would print it. This writes one entry through {@link Faults}
 * under a reference instead, and nothing else.
 *
 * <p>It is the default handler of the process, set by {@code PaveApplication.main} before Spring
 * starts, which is the entry point of the jar and of a start from the IDE alike. A thread that has
 * a handler of its own is not covered by it, so the one thread this tool makes is given this same
 * handler as its own.
 */
public final class UncaughtFailures implements Thread.UncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UncaughtFailures.class);

    /** The one handler, shared by the process and by the threads this tool makes. */
    public static final UncaughtFailures HANDLER = new UncaughtFailures();

    private UncaughtFailures() {
    }

    /** Makes {@link #HANDLER} the handler of every thread that has none of its own. */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(HANDLER);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        String reference = UUID.randomUUID().toString();
        try {
            // The thread's name, a reference, and the exception types and frames only. A thread's
            // name is given by the code that made it, never by a request.
            log.error("A thread of this tool ended on a failure that nothing caught, thread {}, reference {}: {}",
                    thread == null ? "unknown" : thread.getName(), reference, Faults.describe(error));
        } catch (RuntimeException | Error unlogged) {
            // Nothing is left to write it with. Letting this escape would make the runtime print
            // its own line about this handler, and nothing here may print anything else.
        }
    }
}
