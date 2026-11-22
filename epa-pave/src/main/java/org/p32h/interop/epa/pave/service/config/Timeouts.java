package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.config.OnyxProperties;

/**
 * How long a call may wait to connect and to be answered, each with where the value came from.
 *
 * <p>Two uses share this type. Stored with an environment's settings, a value may be null: the
 * environment does not state it, and application.yml's applies. Carried by a {@link PreparedCall}
 * or answered by {@link OperationGateway#activeTimeouts()}, both values are present, because a
 * client needs a number to build its connection with and a missing one would fail at the call
 * rather than where the call was prepared.
 *
 * @param connectMs   milliseconds to wait for a connection, or null when not stated
 * @param readMs      milliseconds to wait for an answer, or null when not stated
 * @param connectFrom where {@code connectMs} came from, such as {@link #FROM_APPLICATION}
 * @param readFrom    where {@code readMs} came from
 */
public record Timeouts(Integer connectMs, Integer readMs, String connectFrom, String readFrom) {

    /** The source word for a value application.yml gives, shown on screen beside the value. */
    public static final String FROM_APPLICATION = "application.yml";

    /** The source word for a value the environment's own settings state. */
    public static final String FROM_ENVIRONMENT = "this environment";

    /** The source of a timeout a lookup saved on screen states (R-11). */
    public static final String FROM_LOOKUP = "the saved lookup";

    /** The smallest and largest value an environment may state, in milliseconds (plan 5.1). */
    public static final int MIN_MS = 100;
    public static final int MAX_MS = 600_000;

    /**
     * The values the onyx properties bind to when application.yml names none. A gateway that knows
     * the properties in force answers with those instead (the gateway of this tool does), so this
     * stands only for a gateway that knows none, such as a stand-in in a test. Bound once from the
     * same defaults Spring binds, so the numbers are written in one place.
     */
    public static final Timeouts APPLICATION = of(OnyxProperties.defaults());

    /** application.yml's protocol timeouts, both present. */
    public static Timeouts of(OnyxProperties onyx) {
        return application(onyx.connectTimeoutMs(), onyx.readTimeoutMs());
    }

    /** Both values from application.yml. */
    public static Timeouts application(int connectMs, int readMs) {
        return new Timeouts(connectMs, readMs, FROM_APPLICATION, FROM_APPLICATION);
    }

    /** Nothing stated: an environment that leaves both to application.yml. */
    public static Timeouts notStated() {
        return new Timeouts(null, null, null, null);
    }

    /** Whether neither value is stated. */
    public boolean empty() {
        return connectMs == null && readMs == null;
    }

    /**
     * These values where present and the given ones where not, each keeping its own source. So an
     * environment that states only a read timeout is answered with its read timeout and
     * application.yml's connect timeout.
     */
    public Timeouts over(Timeouts underneath) {
        if (underneath == null) {
            return this;
        }
        boolean connectHere = connectMs != null;
        boolean readHere = readMs != null;
        return new Timeouts(connectHere ? connectMs : underneath.connectMs, readHere ? readMs : underneath.readMs,
                connectHere ? connectFrom : underneath.connectFrom, readHere ? readFrom : underneath.readFrom);
    }

    /** The connect timeout, or the given one when this states none. Never unboxes a null. */
    public int connectOr(int fallback) {
        return connectMs == null ? fallback : connectMs;
    }

    /** The read timeout, or the given one when this states none. Never unboxes a null. */
    public int readOr(int fallback) {
        return readMs == null ? fallback : readMs;
    }
}
