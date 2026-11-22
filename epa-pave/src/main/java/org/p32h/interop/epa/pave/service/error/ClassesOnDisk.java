package org.p32h.interop.epa.pave.service.error;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.CodeSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.system.ApplicationHome;

/**
 * Whether the files PAVE runs from changed on disk while it ran, and when the build it started
 * from was made.
 *
 * <p>Started from the IDE, PAVE runs from a classes folder that the IDE rewrites whenever it builds
 * the project: after a pull, a branch switch, a Gradle refresh or a clean. A class loaded after
 * that is the new one, called by old classes already in memory, and fails with a
 * {@link LinkageError} on every request that reaches it until PAVE is started again. That failure
 * looked like a defect of the tool on the operator's workstation (hunt-conditions F1). Two things
 * answer it here: every class of the tool is loaded at start, so the running copy keeps one
 * consistent set of classes whatever the IDE writes later, and the rewrite is noticed, so the
 * screen can say to start PAVE again instead of reporting a defect.
 *
 * <p>Run from a jar, nothing can change under PAVE: the jar is open and read once, and a new jar
 * takes a new start. Then {@link #changedSinceStart()} is always false and {@link #builtAt()} is the
 * jar's time.
 *
 * <p>No path is written to the log: a classes folder sits under a home folder, whose name is a
 * person's. The log gets counts only.
 */
public final class ClassesOnDisk {

    /** The system property that turns the loading at start off, for the check that classes changed under PAVE are told. */
    public static final String LOAD_AT_START = "pave.classes.load-at-start";

    /** The longest a folder is left unlooked at: the walk is cheap, but a page asks on every answer. */
    static final Duration LOOK_EVERY = Duration.ofSeconds(10);

    /**
     * The most files one look reads. A classes folder holds about a thousand; the bound only keeps a
     * folder mistaken for one, such as a whole drive, from costing a walk of the drive.
     */
    static final int FILES_LIMIT = 50_000;

    private static final Logger log = LoggerFactory.getLogger(ClassesOnDisk.class);

    private static volatile Watch current;

    private ClassesOnDisk() {
    }

    /**
     * Called first thing when PAVE starts: records the start, and when PAVE runs from a classes
     * folder loads every class in it (load, never initialize) unless {@link #LOAD_AT_START} is
     * {@code false}. Never throws: a start is never stopped by what is only a precaution.
     */
    public static void atStart() {
        try {
            Watch watch = find(Clock.systemUTC());
            current = watch;
            boolean load = !"false".equalsIgnoreCase(System.getProperty(LOAD_AT_START, "true").strip());
            if (watch.folder() && load) {
                Loaded loaded = watch.loadAll(ClassesOnDisk.class.getClassLoader());
                // Counts only: a class name is the tool's own, but a path would name a home folder.
                log.info("Loaded {} classes of this tool from its classes folder at start; {} could not be loaded", loaded.loaded(),
                        loaded.failed());
            }
            log.info("Running from {}, built at {}", watch.folder() ? "a classes folder" : watch.builtAt() == null ? "an unknown source" : "a jar",
                    watch.builtAt() == null ? "an unknown time" : watch.builtAt());
        } catch (RuntimeException | LinkageError e) {
            // Types only, as everywhere: nothing here is worth stopping the start for.
            log.warn("The classes of this tool could not be inspected at start: {}", Faults.cause(e));
        }
    }

    /**
     * True when a class file, or a file under the folder that holds {@code static/index.html} when
     * that is a folder, is newer than the start. Only a classes folder can change; a jar never
     * does. A folder is looked at at most once every {@link #LOOK_EVERY}, and once true it stays
     * true, since only a new start puts the classes in memory and on disk back in step.
     */
    public static boolean changedSinceStart() {
        return watch().changed();
    }

    /** When the build PAVE started from was made: the jar's time, or the newest class file's at start. Null when unknown. */
    public static Instant builtAt() {
        return watch().builtAt();
    }

    /** Puts a watch in place, returning the one it replaces. For the start, and for tests that need a known answer. */
    public static Watch install(Watch watch) {
        Watch before = current;
        current = watch;
        return before;
    }

    private static Watch watch() {
        Watch watch = current;
        if (watch == null) {
            // Started some other way than through main, as a test context is: what started is
            // looked at from the first question on, and nothing is loaded.
            synchronized (ClassesOnDisk.class) {
                if (current == null) {
                    try {
                        current = find(Clock.systemUTC());
                    } catch (RuntimeException | LinkageError e) {
                        current = Watch.unknown();
                    }
                }
                watch = current;
            }
        }
        return watch;
    }

    /** What this copy of PAVE runs from. */
    private static Watch find(Clock clock) {
        Path classes = codeSourceFolder();
        if (classes != null) {
            return Watch.ofFolder(classes, staticFolder(), clock);
        }
        Path jar = jarFile();
        return jar == null ? Watch.unknown() : Watch.ofJar(jar);
    }

    /** The folder this class was loaded from, or null when it was loaded from anything else. */
    private static Path codeSourceFolder() {
        CodeSource source = ClassesOnDisk.class.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return null;
        }
        try {
            Path path = Path.of(location.toURI());
            return Files.isDirectory(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The folder the page's files are served from, when it is a folder: the IDE copies them on a build too. */
    private static Path staticFolder() {
        URL index = ClassesOnDisk.class.getClassLoader().getResource("static/index.html");
        if (index == null || !"file".equalsIgnoreCase(index.getProtocol())) {
            return null;
        }
        try {
            Path folder = Path.of(index.toURI()).getParent();
            return folder != null && Files.isDirectory(folder) ? folder : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The jar this copy runs from, found the way Spring Boot finds its own home, or null. */
    private static Path jarFile() {
        java.io.File source = new ApplicationHome(ClassesOnDisk.class).getSource();
        if (source == null || !source.isFile()) {
            return null;
        }
        return source.toPath();
    }

    /** How many classes the loading at start loaded, and how many it could not. */
    public record Loaded(int loaded, int failed) {
    }

    /** What one copy of PAVE runs from, and what has been seen of it. */
    public static final class Watch {

        private final Path classes;
        private final Path statics;
        private final Instant start;
        private final Instant builtAt;
        private final Clock clock;
        private Instant lookedAt;
        private boolean changed;

        private Watch(Path classes, Path statics, Instant start, Instant builtAt, Clock clock) {
            this.classes = classes;
            this.statics = statics;
            this.start = start;
            this.builtAt = builtAt;
            this.clock = clock;
        }

        /**
         * A classes folder, with the folder of the page's files when that is a folder (may be null).
         * The start is now on the clock; the build time is the newest class file's.
         */
        public static Watch ofFolder(Path classes, Path statics, Clock clock) {
            Instant start = clock.instant();
            return new Watch(classes, statics, start, newest(classes, ".class"), clock);
        }

        /** A jar: it never changes under PAVE, and its time is the build's. */
        public static Watch ofJar(Path jar) {
            Instant built;
            try {
                built = Files.getLastModifiedTime(jar).toInstant();
            } catch (IOException | RuntimeException e) {
                built = null;
            }
            return new Watch(null, null, null, built, Clock.systemUTC());
        }

        /** Nothing known: never changed, no build time. */
        public static Watch unknown() {
            return new Watch(null, null, null, null, Clock.systemUTC());
        }

        boolean folder() {
            return classes != null;
        }

        public Instant builtAt() {
            return builtAt;
        }

        /** See {@link ClassesOnDisk#changedSinceStart()}. */
        public synchronized boolean changed() {
            if (classes == null || changed) {
                return changed;
            }
            Instant now = clock.instant();
            if (lookedAt != null && now.isBefore(lookedAt.plus(LOOK_EVERY))) {
                return false;
            }
            lookedAt = now;
            // A file newer than the start, and newer than the newest class file the start saw, so
            // that a class file written with a clock ahead of this one is not taken for a rebuild.
            Instant since = builtAt != null && builtAt.isAfter(start) ? builtAt : start;
            // A classes folder that is gone was cleaned by the IDE: what comes back is a new build.
            changed = !Files.isDirectory(classes) || anyNewer(classes, since) || (statics != null && anyNewer(statics, since));
            return changed;
        }

        /** Loads every class of the folder by name, without initializing any. */
        public Loaded loadAll(ClassLoader loader) {
            if (classes == null) {
                return new Loaded(0, 0);
            }
            int loaded = 0;
            int failed = 0;
            try (Stream<Path> files = Files.walk(classes)) {
                Iterator<Path> each = files.iterator();
                int seen = 0;
                while (each.hasNext() && seen++ < FILES_LIMIT) {
                    String name = className(classes, each.next());
                    if (name == null) {
                        continue;
                    }
                    try {
                        Class.forName(name, false, loader);
                        loaded++;
                    } catch (ClassNotFoundException | LinkageError e) {
                        // A class that needs a library this start does not have: it is never used, or
                        // it fails where it is used exactly as it would have without this loading.
                        failed++;
                    }
                }
            } catch (IOException | UncheckedIOException | SecurityException e) {
                // What was loaded stays loaded; the rest is loaded when first used, as without this.
            }
            return new Loaded(loaded, failed);
        }

        /** The binary name of a class file under the root, or null for anything that is not a class file of a class. */
        static String className(Path root, Path file) {
            String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
            if (!fileName.endsWith(".class") || fileName.equals("module-info.class") || fileName.equals("package-info.class")) {
                return null;
            }
            Path relative = root.relativize(file);
            StringBuilder name = new StringBuilder();
            for (Path part : relative) {
                name.append(name.length() == 0 ? "" : ".").append(part);
            }
            return name.substring(0, name.length() - ".class".length());
        }

        /** The time of the newest file under the folder whose name ends so, or null when there is none. */
        private static Instant newest(Path folder, String ending) {
            Instant newest = null;
            try (Stream<Path> files = Files.walk(folder)) {
                Iterator<Path> each = files.iterator();
                int seen = 0;
                while (each.hasNext() && seen++ < FILES_LIMIT) {
                    Path file = each.next();
                    if (!file.toString().endsWith(ending) || !Files.isRegularFile(file)) {
                        continue;
                    }
                    Instant time = Files.getLastModifiedTime(file).toInstant();
                    if (newest == null || time.isAfter(newest)) {
                        newest = time;
                    }
                }
            } catch (IOException | UncheckedIOException | SecurityException e) {
                // An unreadable folder has no known build time.
            }
            return newest;
        }

        /** True when a file under the folder is newer than the instant; a folder that cannot be read has nothing newer. */
        private static boolean anyNewer(Path folder, Instant since) {
            FileTime limit = FileTime.from(since);
            try (Stream<Path> files = Files.walk(folder)) {
                Iterator<Path> each = files.iterator();
                int seen = 0;
                while (each.hasNext() && seen++ < FILES_LIMIT) {
                    Path file = each.next();
                    if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).compareTo(limit) > 0) {
                        return true;
                    }
                }
            } catch (IOException | UncheckedIOException | SecurityException e) {
                // A folder removed while PAVE runs is a clean of the IDE: the next look finds the new files.
                return false;
            }
            return false;
        }
    }
}
