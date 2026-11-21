package org.p32h.interop.epa.pave.client.lookup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Everything any logger writes on the calling thread while it is open, at every level, for the
 * tests that prove a lookup puts no member data in the log. It listens at the root, so a statement
 * added to any class later, in this tool or in a library it calls, is seen as well. Only the
 * calling thread is kept, so a background thread of another test cannot change what is captured.
 */
final class CapturedLog implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Level levelBefore = root.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final String thread = Thread.currentThread().getName();

    CapturedLog() {
        appender.start();
        root.addAppender(appender);
        // The lowest level, so that a statement at debug or trace is not missed because a test ran at info.
        root.setLevel(Level.TRACE);
    }

    /** Every event, as "LEVEL logger: message" with the arguments filled in and any exception text appended. */
    List<String> lines() {
        return List.copyOf(appender.list).stream().filter(event -> thread.equals(event.getThreadName())).map(CapturedLog::line).toList();
    }

    /** The events of this tool's own loggers only. */
    List<String> ownLines() {
        return List.copyOf(appender.list).stream().filter(event -> thread.equals(event.getThreadName()))
                .filter(event -> event.getLoggerName().startsWith("org.p32h.")).map(CapturedLog::line).toList();
    }

    private static String line(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(event.getLevel() + " " + event.getLoggerName() + ": " + event.getFormattedMessage());
        if (event.getThrowableProxy() != null) {
            out.append(" | ").append(event.getThrowableProxy().getClassName()).append(": ").append(event.getThrowableProxy().getMessage());
        }
        return out.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(levelBefore);
        appender.stop();
    }
}
