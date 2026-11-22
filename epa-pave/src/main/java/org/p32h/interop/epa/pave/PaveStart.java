package org.p32h.interop.epa.pave;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.UncaughtFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The one place PAVE is built and started: the first start from {@code main}, and every start
 * after a switch between mock and live made on screen.
 *
 * <p>A switch restarts PAVE in place: the running application context is closed and a new one is
 * started in the same JVM, on the other profile, with the arguments PAVE was started with. Mock and
 * live stay what they were, a Spring profile with every bean bound to it, so what holds on live by
 * construction holds after a switch as after a start by hand.
 *
 * <p>What lives in this class lives as long as the JVM, across starts: the arguments of the first
 * start, the class loader that started it, the instant of the latest start and the switch under
 * way. Nothing in it holds member data or a secret.
 */
public final class PaveStart {

    private static final Logger log = LoggerFactory.getLogger(PaveStart.class);

    /** The property a profile is chosen with, as Spring Boot reads it from the command line. */
    static final String ACTIVE_PROFILES = "spring.profiles.active";

    private static final Object STARTS = new Object();

    /** The arguments of the first start, in order. Null when PAVE was not started through {@code main}, as in a test context. */
    private static volatile String[] arguments;

    /** The class loader {@code main} ran in: in the jar it is Spring Boot's own, which a thread made later does not get by itself. */
    private static volatile ClassLoader loader;

    /** When the latest start began. Null until a switch, when the start of the JVM is the start of PAVE. */
    private static volatile Instant startedAt;

    /** The mode a switch under way goes to, or null. Held across the close and the new start, so two switches never overlap. */
    private static final AtomicReference<String> switchingTo = new AtomicReference<>();

    /** The context of the latest start that succeeded, or null: for a test to close whatever a switch left running. */
    private static volatile ConfigurableApplicationContext current;

    /** Starts PAVE on a list of arguments; a test puts a start of its own in its place. */
    private static volatile Function<String[], ConfigurableApplicationContext> starter = PaveStart::run;

    /** What is done when neither the wanted mode nor the one before will start; a test puts something of its own in its place. */
    private static volatile Runnable giveUp = () -> System.exit(1);

    private PaveStart() {
    }

    /**
     * The application as {@code main} builds it. The main class is named here because a start made
     * on the thread of a switch has no {@code main} on its stack to find it by, and without it the
     * banner and the line "Started PaveApplication in ..." would change.
     */
    static SpringApplication application() {
        SpringApplication application = new SpringApplication(PaveApplication.class);
        application.setMainApplicationClass(PaveApplication.class);
        return application;
    }

    /** The first start, from {@code main}: remembers what a switch needs and starts PAVE as given. */
    public static ConfigurableApplicationContext first(String[] args) {
        arguments = args == null ? new String[0] : args.clone();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        loader = context != null ? context : PaveApplication.class.getClassLoader();
        current = starter.apply(arguments.clone());
        return current;
    }

    private static ConfigurableApplicationContext run(String[] args) {
        return application().run(args);
    }

    /** True when PAVE was started through {@code main}, so that it knows the arguments to start again with. */
    public static boolean canSwitch() {
        return arguments != null;
    }

    /** When the start that runs now began: the start of the JVM until the first switch, then the start after the latest switch. */
    public static Instant startedAt() {
        Instant latest = startedAt;
        return latest != null ? latest
                : Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** The mode a switch under way goes to, or null when none is under way. */
    public static String switchingTo() {
        return switchingTo.get();
    }

    /** Claims the one switch PAVE makes at a time. False when another is under way. */
    public static boolean claim(String mode) {
        return switchingTo.compareAndSet(null, mode);
    }

    /** Gives back a claim that was refused after it was made, so it does not block the next switch. */
    public static void release(String mode) {
        switchingTo.compareAndSet(mode, null);
    }

    /**
     * Closes the running context and starts PAVE again on the mode claimed, on a thread of its own.
     * The thread is not a daemon, so the JVM stays up between the close and the new start, and it
     * is none of the context's own, which the close stops.
     */
    public static Thread restart(ConfigurableApplicationContext running, String from, String to) {
        Thread thread = new Thread(() -> switchOver(running, from, to), "pave-mode-switch");
        thread.setDaemon(false);
        thread.setContextClassLoader(loader != null ? loader : PaveApplication.class.getClassLoader());
        thread.setUncaughtExceptionHandler(UncaughtFailures.HANDLER);
        thread.start();
        return thread;
    }

    private static void switchOver(ConfigurableApplicationContext running, String from, String to) {
        try {
            // The tool's ordinary stop: a call still out gets its time and its ledger line, and the
            // configuration store's lock, the ledger, the trail and the port are let go.
            try {
                running.close();
            } catch (RuntimeException | Error e) {
                // A reference and the exception types and frames only, as every failure of this tool.
                log.error("Closing PAVE for the switch to {} failed inside this tool, reference {}: {}", to, UUID.randomUUID(),
                        Faults.describe(e));
            }
            if (startOn(to)) {
                return;
            }
            // Spring has written why the start failed, as at any start; this line says what comes next.
            log.warn("PAVE could not start on {}, so it starts again on {}.", to, from);
            if (startOn(from)) {
                return;
            }
            log.error("PAVE could not start on {} nor again on {}, so it stops. Start it again by hand.", to, from);
            giveUp.run();
        } finally {
            switchingTo.set(null);
        }
    }

    private static boolean startOn(String mode) {
        synchronized (STARTS) {
            startedAt = Instant.now();
            try {
                current = starter.apply(argumentsFor(arguments == null ? new String[0] : arguments, mode));
                return true;
            } catch (RuntimeException | Error e) {
                // Spring reports a failed start itself and closes what it had made, the port and the
                // store's lock with it. Nothing of the exception is written here.
                return false;
            }
        }
    }

    /**
     * The arguments of the first start, in order, with every one that chooses the active profiles
     * taken out and {@code --spring.profiles.active=<mode>} added at the end. A profile chosen by a
     * system property or by {@code SPRING_PROFILES_ACTIVE} needs no taking out: Spring Boot ranks
     * the command line above both. Every other argument, a port or a folder among them, stays.
     */
    public static String[] argumentsFor(String[] original, String mode) {
        List<String> kept = new ArrayList<>();
        if (original != null) {
            for (String argument : original) {
                if (!choosesProfiles(argument)) {
                    kept.add(argument);
                }
            }
        }
        kept.add("--" + ACTIVE_PROFILES + "=" + mode);
        return kept.toArray(new String[0]);
    }

    /**
     * True for a command line option that sets {@code spring.profiles.active}, written in any form
     * Spring Boot binds to it: any case, with dashes, underscores for dots, an index such as
     * {@code [0]}, and with or without a value.
     */
    static boolean choosesProfiles(String argument) {
        if (argument == null || !argument.startsWith("--")) {
            return false;
        }
        String name = argument.substring(2);
        int equals = name.indexOf('=');
        if (equals >= 0) {
            name = name.substring(0, equals);
        }
        int index = name.indexOf('[');
        if (index >= 0) {
            name = name.substring(0, index);
        }
        String plain = name.strip().toLowerCase(Locale.ROOT).replace("-", "").replace('_', '.');
        return plain.equals(ACTIVE_PROFILES);
    }

    /** The context of the latest start that succeeded, or null when none did through this class. */
    public static ConfigurableApplicationContext current() {
        return current;
    }

    /** For tests: what starts PAVE and what is done when no start works, put back with {@link #reset()}. */
    public static void useForTests(Function<String[], ConfigurableApplicationContext> start, Runnable end) {
        starter = start == null ? PaveStart::run : start;
        giveUp = end;
    }

    /** For tests: arguments as if {@code main} had been given them, without starting anything. */
    public static void rememberForTests(String[] args) {
        arguments = args == null ? null : args.clone();
        loader = PaveStart.class.getClassLoader();
    }

    /** For tests: everything back as it is in a JVM that has started nothing. */
    public static void reset() {
        starter = PaveStart::run;
        giveUp = () -> System.exit(1);
        arguments = null;
        loader = null;
        startedAt = null;
        current = null;
        switchingTo.set(null);
    }
}
