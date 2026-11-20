package org.p32h.interop.epa.pave.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on a configuration that would make the screen lie.
 *
 * <p>The mode badge is driven by {@code epa.mode} and the client wiring by the Spring profile. If
 * the two could disagree, a tester could see "live" while every answer came from a scenario file,
 * or the reverse. So the application starts only when exactly one of the mock and live profiles
 * is active and {@code epa.mode} names the same one.
 *
 * <p>It also says where the tool keeps what it writes. Both folders are relative to the directory
 * the tool is started from, so the same command run from another directory writes somewhere else.
 * A folder inside a git working tree is named as a risk: an uploaded environment, with a secret in
 * it, or the run history could be committed by a {@code git add} of the whole tree.
 */
@Component
public class StartupChecks implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private static final List<String> TARGETS = List.of("onyx", "nehen");

    /** The two ways supporting documentation can travel with a prior authorization request. */
    public static final String ATTACHMENT_FHIR = "fhir";
    public static final String ATTACHMENT_X12_275 = "x12-275";

    private final EpaProperties epa;
    private final OnyxProperties onyx;
    private final LookupProperties lookup;
    private final TrackingProperties tracking;
    private final AppProperties app;
    private final Environment environment;
    private final ConfigStoreProperties store;

    @Autowired
    public StartupChecks(EpaProperties epa, OnyxProperties onyx, LookupProperties lookup, TrackingProperties tracking, AppProperties app,
                         Environment environment, ConfigStoreProperties store) {
        this.epa = epa;
        this.onyx = onyx;
        this.lookup = lookup;
        this.tracking = tracking;
        this.app = app;
        this.environment = environment;
        this.store = store;
    }

    public StartupChecks(EpaProperties epa, OnyxProperties onyx, LookupProperties lookup, TrackingProperties tracking, AppProperties app,
                         Environment environment) {
        this(epa, onyx, lookup, tracking, app, environment, null);
    }

    @Override
    public void afterPropertiesSet() {
        boolean mockProfile = environment.matchesProfiles(EpaProperties.MOCK);
        boolean liveProfile = environment.matchesProfiles(EpaProperties.LIVE);
        String active = Arrays.toString(environment.getActiveProfiles());
        if (mockProfile == liveProfile) {
            throw new IllegalStateException("Exactly one of the Spring profiles 'mock' and 'live' must be active, but the active "
                    + "profiles are " + active + ". Start with no profile for mock, or with --spring.profiles.active=live.");
        }
        String profile = mockProfile ? EpaProperties.MOCK : EpaProperties.LIVE;
        if (!profile.equalsIgnoreCase(epa.mode())) {
            throw new IllegalStateException("epa.mode is '" + epa.mode() + "' but the active Spring profile is '" + profile
                    + "'. Do not set epa.mode by hand: it follows the profile. Start with --spring.profiles.active=live for live.");
        }
        if (epa.target() == null || !TARGETS.contains(epa.target().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("epa.target is '" + epa.target() + "'. It must be one of " + TARGETS + ".");
        }
        // Free text here would be found at the first submission, if at all. A misspelt mode is
        // refused now, where the person who typed it is looking.
        String mode = onyx.pas().attachmentMode();
        if (!ATTACHMENT_FHIR.equals(mode) && !ATTACHMENT_X12_275.equals(mode)) {
            throw new IllegalStateException("onyx.pas.attachment-mode is '" + mode + "'. It must be '" + ATTACHMENT_FHIR + "' or '"
                    + ATTACHMENT_X12_275 + "'.");
        }
        // A timeout of zero does not mean "no timeout" to the HTTP client. A read timeout of zero
        // reports every call as timed out, and a connect timeout of zero fails while the live beans
        // are built, neither with a word about which setting did it. Checked on both profiles, so a
        // bad value is found on the mock profile, before anyone switches to live.
        positive("onyx.connect-timeout-ms", onyx.connectTimeoutMs());
        positive("onyx.read-timeout-ms", onyx.readTimeoutMs());
        positive("lookup.member.connect-timeout-ms", lookup.member().connectTimeoutMs());
        positive("lookup.member.read-timeout-ms", lookup.member().readTimeoutMs());
        positive("lookup.provider.connect-timeout-ms", lookup.provider().connectTimeoutMs());
        positive("lookup.provider.read-timeout-ms", lookup.provider().readTimeoutMs());
        positive("app.autocomplete-limit", app.autocompleteLimit());
        if (tracking.slowThresholdMs() < 0) {
            throw new IllegalStateException("tracking.slow-threshold-ms is " + tracking.slowThresholdMs()
                    + ". It must be zero, to use the Onyx read timeout, or greater.");
        }
        if (store != null) {
            for (Kept line : whereThingsAreKept(store.directory(), tracking.enabled() ? tracking.directory() : null)) {
                if (line.risk()) {
                    // Two paths: the folder and the working tree. Nothing from inside either.
                    log.warn("{}", line.text());
                } else {
                    // A path only.
                    log.info("{}", line.text());
                }
            }
        }
    }

    /**
     * One line said at start-up about where the tool keeps what it writes.
     *
     * @param text the sentence
     * @param risk true when it names a risk rather than a place
     */
    record Kept(String text, boolean risk) {
    }

    /**
     * Where the configuration store and the run history are, in full, and a second line for each
     * that lies inside a git working tree. Git is not run: a folder is inside a working tree when
     * it, or a folder above it, holds an entry named {@code .git}, which is a folder in a plain
     * clone and a file in a linked worktree. The folders may not exist yet, so the walk starts at
     * the folder itself and goes up through whatever exists.
     *
     * @param runHistory the run history folder, or null when tracking is switched off
     */
    static List<Kept> whereThingsAreKept(String configStore, String runHistory) {
        List<Kept> lines = new ArrayList<>();
        Path storePath = absolute(configStore);
        lines.add(new Kept("The configuration store, which holds uploaded environments and collections and can hold credentials, is at "
                + storePath, false));
        Path storeTree = workingTree(storePath);
        if (storeTree != null) {
            lines.add(new Kept("The configuration store " + storePath + " lies inside the git working tree " + storeTree + ". An uploaded "
                    + "environment, with any secret in it, could be committed from there. Check that the tree's .gitignore covers it, "
                    + "or start the tool from a directory outside the tree.", true));
        }
        if (runHistory == null) {
            lines.add(new Kept("The run history is switched off (tracking.enabled is false), so no run is recorded.", false));
            return lines;
        }
        Path historyPath = absolute(runHistory);
        lines.add(new Kept("The run history is at " + historyPath, false));
        Path historyTree = workingTree(historyPath);
        if (historyTree != null) {
            lines.add(new Kept("The run history " + historyPath + " lies inside the git working tree " + historyTree + ". Its files, "
                    + "with what the runs recorded, could be committed from there. Check that the tree's .gitignore covers it, or start "
                    + "the tool from a directory outside the tree.", true));
        }
        return lines;
    }

    private static Path absolute(String directory) {
        try {
            return Path.of(directory == null ? "" : directory.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            // The store and the ledger refuse such a path themselves, naming it. Here it is only reported as written.
            return Path.of("").toAbsolutePath().resolve("(" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The nearest folder at or above this one that holds an entry named .git, or null when there is none. */
    static Path workingTree(Path folder) {
        for (Path at = folder; at != null; at = at.getParent()) {
            try {
                if (Files.exists(at.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                    return at;
                }
            } catch (RuntimeException e) {
                // A folder that cannot be looked at says nothing about a working tree.
                return null;
            }
        }
        return null;
    }

    private static void positive(String property, long value) {
        if (value <= 0) {
            throw new IllegalStateException(property + " is " + value + ". It must be greater than zero.");
        }
    }
}
