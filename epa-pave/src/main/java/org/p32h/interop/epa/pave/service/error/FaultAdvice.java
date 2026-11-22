package org.p32h.interop.epa.pave.service.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * What to do about a failure of this tool when the failure itself says it: a heap too small for
 * what was asked, or classes rebuilt on disk under a running PAVE. In both cases a report would
 * send someone looking for a defect that is not there, and the tester can mend it there and then.
 * Every other failure gets {@link Faults#REPORT_ACTION}.
 *
 * <p>Only the types of the chain are read, never a message.
 */
public final class FaultAdvice {

    /** The action when classes changed on disk under a running PAVE and a class could not be linked (R-24). */
    public static final String CLASSES_CHANGED = "PAVE's classes changed on disk while it was running, as the IDE does when it rebuilds "
            + "the project. Stop PAVE and start it again.";

    /** The action when the heap ran out (R-36): more memory is what helps, and the setting is named. */
    public static final String MORE_MEMORY = "Start PAVE with -Xmx512m or more.";

    /** A real chain is far shorter; this only bounds a pathological one. */
    private static final int CHAIN_LIMIT = 32;

    private FaultAdvice() {
    }

    /** The advice the failure's types give, or null when they give none. */
    public static String advice(Throwable error) {
        if (holds(error, OutOfMemoryError.class)) {
            return MORE_MEMORY;
        }
        // A class that cannot be linked is a rebuild only when the files did change: otherwise it is
        // a defect of the build, and the reference is what finds it.
        if (holds(error, LinkageError.class) && ClassesOnDisk.changedSinceStart()) {
            return CLASSES_CHANGED;
        }
        return null;
    }

    /** The action under a failure of this tool: the advice its types give, else the request to report it. */
    public static String action(Throwable error) {
        String advice = advice(error);
        return advice == null ? Faults.REPORT_ACTION : advice;
    }

    private static boolean holds(Throwable error, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = error; t != null && depth < CHAIN_LIMIT && seen.add(t); t = t.getCause(), depth++) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
