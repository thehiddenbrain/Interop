package org.p32h.interop.epa.pave.config;

import java.util.List;
import java.util.Locale;
import org.p32h.interop.epa.pave.PaveStart;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.service.error.BusyException;
import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.RunReplay;
import org.p32h.interop.epa.pave.service.suite.SuiteService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The switch between mock and live made on screen: decides whether PAVE may switch now and, when
 * it may, has {@link PaveStart} restart it in place on the other profile.
 *
 * <p>Every refusal is made before anything stops, so a refused switch changes nothing.
 */
@Component
public class ModeSwitch {

    static final String CHOOSE = "Choose mock or live.";
    static final String NOT_SWITCHED = "PAVE did not switch.";
    static final String FILES_CHANGED = "PAVE's files changed on disk since it started, so it cannot switch in place: a switch loads "
            + "no class anew.";
    static final String FILES_CHANGED_ACTION = "Stop PAVE and start it again.";
    static final String SUITE_RUNNING = "A suite is still running, so PAVE did not switch.";
    static final String SUITE_RUNNING_ACTION = "Wait for the suite to finish, or stop it in Audit > Suites, then switch.";
    static final String REPLAY_RUNNING = "Runs are still being replayed, so PAVE did not switch.";
    static final String REPLAY_RUNNING_ACTION = "Wait for the replay to finish, then switch.";
    static final String NOT_FROM_MAIN = "This copy of PAVE was not started by its own main class, so it cannot switch itself.";
    static final String NOT_FROM_MAIN_ACTION = "Stop it and start it on the other profile.";

    /** What a switch needs from outside: the files check, the claim and the restart, which a test replaces. */
    public interface Restarts {

        boolean canSwitch();

        boolean filesChanged();

        boolean claim(String mode);

        void release(String mode);

        void restart(String from, String to);
    }

    private final EpaProperties epa;
    private final ObjectProvider<SuiteService> suites;
    private final ObjectProvider<RunReplay> replays;
    private final Restarts restarts;

    @Autowired
    public ModeSwitch(EpaProperties epa, ObjectProvider<SuiteService> suites, ObjectProvider<RunReplay> replays, ApplicationContext context) {
        this(epa, suites, replays, inPlace(context));
    }

    public ModeSwitch(EpaProperties epa, ObjectProvider<SuiteService> suites, ObjectProvider<RunReplay> replays, Restarts restarts) {
        this.epa = epa;
        this.suites = suites;
        this.replays = replays;
        this.restarts = restarts;
    }

    private static Restarts inPlace(ApplicationContext context) {
        return new Restarts() {
            @Override
            public boolean canSwitch() {
                return PaveStart.canSwitch() && context instanceof ConfigurableApplicationContext;
            }

            @Override
            public boolean filesChanged() {
                // Asked afresh: the answer the page polls is held for some seconds, and a rebuild
                // made just before the switch is the one that matters here.
                return ClassesOnDisk.changedSinceStartNow();
            }

            @Override
            public boolean claim(String mode) {
                return PaveStart.claim(mode);
            }

            @Override
            public void release(String mode) {
                PaveStart.release(mode);
            }

            @Override
            public void restart(String from, String to) {
                PaveStart.restart((ConfigurableApplicationContext) context, from, to);
            }
        };
    }

    /**
     * The mode a request names, checked, with the one switch claimed: either it returns the mode
     * PAVE now goes to, and {@link #begin} must follow, or it throws and nothing is claimed.
     */
    public String claim(String requested) {
        String wanted = requested == null ? null : requested.strip().toLowerCase(Locale.ROOT);
        if (!EpaProperties.MOCK.equals(wanted) && !EpaProperties.LIVE.equals(wanted)) {
            throw new ValidationException(NOT_SWITCHED, List.of(new FieldError("mode", CHOOSE)));
        }
        String current = current();
        if (wanted.equals(current)) {
            throw new BusyException("PAVE is already on " + wanted + ".", "Choose the other mode to switch.");
        }
        if (!restarts.canSwitch()) {
            throw new BusyException(NOT_FROM_MAIN, NOT_FROM_MAIN_ACTION);
        }
        if (restarts.filesChanged()) {
            throw new BusyException(FILES_CHANGED, FILES_CHANGED_ACTION);
        }
        if (!restarts.claim(wanted)) {
            String under = PaveStart.switchingTo();
            throw new BusyException("PAVE is already switching" + (under == null ? "." : " to " + under + "."),
                    "Wait a few seconds until it answers again.");
        }
        try {
            SuiteService suite = suites.getIfAvailable();
            if (suite != null && suite.running()) {
                throw new BusyException(SUITE_RUNNING, SUITE_RUNNING_ACTION);
            }
            RunReplay replay = replays.getIfAvailable();
            if (replay != null && replay.running()) {
                throw new BusyException(REPLAY_RUNNING, REPLAY_RUNNING_ACTION);
            }
        } catch (RuntimeException refused) {
            restarts.release(wanted);
            throw refused;
        }
        return wanted;
    }

    /** Restarts PAVE on the mode {@link #claim} returned. Called once the answer to the request is on its way. */
    public void begin(String to) {
        try {
            restarts.restart(current(), to);
        } catch (RuntimeException | Error e) {
            restarts.release(to);
            throw e;
        }
    }

    /** Gives back a claim made by {@link #claim} when the switch will not be made after all. */
    public void release(String to) {
        restarts.release(to);
    }

    /** The mode PAVE runs on now. */
    public String current() {
        return epa.mock() ? EpaProperties.MOCK : EpaProperties.LIVE;
    }

    /** The mode a switch under way goes to, or null. */
    public String switchingTo() {
        return PaveStart.switchingTo();
    }
}
