package org.p32h.interop.epa.pave.service.error;

/**
 * Describes a failure of this tool for the server log.
 *
 * <p>Exception messages are never logged or returned. A message from an HTTP client or a JSON
 * parser can quote a url, a body or a field value, and any of those can be member data. What is
 * logged is the chain of exception types with the first frames of each, which is enough to find
 * the defect and can carry nothing a member would recognise.
 */
public final class Faults {

    private Faults() {
    }

    public static String describe(Throwable error) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (Throwable t = error; t != null && depth < 6; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (depth > 0) {
                out.append(" <- caused by ");
            }
            out.append(t.getClass().getName());
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) {
                out.append(System.lineSeparator()).append("    at ").append(frames[i]);
            }
        }
        return out.toString();
    }
}
