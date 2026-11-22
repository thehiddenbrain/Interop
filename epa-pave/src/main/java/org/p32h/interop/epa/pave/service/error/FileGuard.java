package org.p32h.interop.epa.pave.service.error;

import java.util.UUID;
import java.util.function.Supplier;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.ConfigStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one file of an upload or a draft so that whatever goes wrong ends as a line about that
 * file, never as an internal error of the whole request (R-07).
 *
 * <p>An upload that answered "This tool hit an internal error" for every file was what the
 * operator met, again and again: a missing class after a rebuild, a heap too small for a large
 * export, a parser that ran out of stack. Each of those is a failure to read one file, and the
 * tester can always type the values instead. So each file is read inside this guard: a refusal of
 * the file keeps its own words, and any other failure gives one plain line with a reference and
 * the cause by types, logged under that reference with its frames as every failure of this tool is.
 *
 * <p>Wrap the reading of the file only. A check that follows the reading, the production guardrail
 * above all, belongs outside the guard, where its refusal keeps its own answer and status. A refusal
 * of this tool that does reach the guard keeps its words in the line all the same.
 */
public final class FileGuard {

    /** The line for a file whose reading failed for any reason other than a refusal of the file. */
    public static final String COULD_NOT_READ = "PAVE could not read this file. Type the values instead.";

    private static final Logger log = LoggerFactory.getLogger(FileGuard.class);

    private FileGuard() {
    }

    /**
     * What reading one file gave.
     *
     * @param value     what the reader returned; null when it did not return
     * @param line      null when the file was read; otherwise what to say about this file
     * @param reference for a failure of this tool, the reference it is logged under; null for a refusal
     * @param cause     for a failure of this tool, the chain of exception types as {@link Faults#cause} gives it; null otherwise
     */
    public record Read<T>(T value, String line, String reference, String cause) {

        /** True when the file was read. */
        public boolean ok() {
            return line == null;
        }

        /** Holds only the value's kind: a value read from a file can hold member data. */
        @Override
        public String toString() {
            return "Read[" + (ok() ? "read" : reference == null ? "refused" : "failed") + "]";
        }
    }

    /**
     * Runs the reader for one file. Never throws: a refusal becomes its own line, anything else,
     * an {@link Error} included, the line {@link #COULD_NOT_READ} with a reference and the cause.
     * {@code fileName} is the tester's text and is never logged.
     */
    public static <T> Read<T> read(String fileName, Supplier<T> reader) {
        try {
            return new Read<>(reader.get(), null, null, null);
        } catch (Throwable thrown) {
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failed(thrown);
        }
    }

    private static <T> Read<T> failed(Throwable thrown) {
        try {
            String refusal = refusal(thrown);
            if (refusal != null) {
                return new Read<>(null, refusal, null, null);
            }
            String reference = UUID.randomUUID().toString();
            // Types and frames under a reference, never a message beyond what Faults.cause allows,
            // and never the file name or its content: either can hold member data.
            log.error("A file could not be read, reference {}: {}", reference, Faults.describe(thrown));
            String advice = FaultAdvice.advice(thrown);
            return new Read<>(null, advice == null ? COULD_NOT_READ : COULD_NOT_READ + " " + advice, reference, Faults.cause(thrown));
        } catch (Throwable again) {
            // Describing the failure failed too, as it can when the heap is gone: the line is fixed
            // text and needs nothing new, and this guard throws nothing on.
            return new Read<>(null, COULD_NOT_READ, null, null);
        }
    }

    /**
     * The words of a refusal of this tool, which are its own sentences and already what the answer
     * to such a refusal shows; null for anything else. The action follows the title, so that the
     * line also says what to do.
     */
    private static String refusal(Throwable thrown) {
        if (thrown instanceof BadInputException e) {
            return joined(e.getMessage(), e.action());
        }
        if (thrown instanceof ValidationException e) {
            return joined(e.getMessage(), e.action());
        }
        if (thrown instanceof ProdWriteBlockedException e) {
            // The guardrail's sentence word for word: its words never change.
            return joined(e.getMessage(), e.action());
        }
        if (thrown instanceof BusyException e) {
            return joined(e.getMessage(), e.action());
        }
        if (thrown instanceof ConfigurationChangedException e) {
            return e.getMessage();
        }
        if (thrown instanceof ConfigStoreException e && e.thisComputer()) {
            return e.getMessage();
        }
        return null;
    }

    private static String joined(String title, String action) {
        if (!Texts.present(title)) {
            return COULD_NOT_READ;
        }
        return Texts.present(action) ? title + " " + action : title;
    }
}
