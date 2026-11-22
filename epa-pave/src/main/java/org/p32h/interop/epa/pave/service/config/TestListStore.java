package org.p32h.interop.epa.pave.service.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The files of the saved test lists, in {@code test-lists/} inside the configuration store folder:
 * {@code {"version": 1, "entries": [...]}}, one file per list.
 *
 * <p>They hold sandbox member data the tester chose to keep, so the folder and the files are
 * readable by this account alone, a file is written whole to a temporary name and moved into
 * place, and the log names a file and a count, never an entry. A file that cannot be read is
 * never written over: the list reads as empty with a line that says why, and every change to it is
 * refused until the file is moved aside, so that a tester's entries are never lost to a save.
 *
 * <p>Nothing is held in memory between requests: every read goes to the file, so an entry added
 * in one window is in the next read of another, and a file put right by hand is read at once.
 */
@Component
public class TestListStore {

    private static final Logger log = LoggerFactory.getLogger(TestListStore.class);

    public static final String MEMBERS = "members.json";
    public static final String PROVIDERS = "providers.json";
    static final String FOLDER = "test-lists";
    private static final int VERSION = 1;
    private static final String TEMP_MARK = ".tmp-";
    /** Far above 200 entries of a few hundred bytes each; a file larger than this is not one PAVE wrote. */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /**
     * The entries of one list as the file holds them.
     *
     * @param problem why the file could not be read, naming the file and what to do, or null. While
     *                it is set the entries are empty and the list is not written
     */
    public record Loaded<T>(List<T> entries, String problem) {

        public Loaded {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public boolean readable() {
            return problem == null;
        }

        /** A count only: the entries are member data. */
        @Override
        public String toString() {
            return "Loaded[entries=" + entries.size() + ", readable=" + readable() + "]";
        }
    }

    private final Path folder;
    private final JsonMapper mapper;
    private final ConfigStore.Disk disk;

    @Autowired
    public TestListStore(ConfigStoreProperties properties, JsonMapper mapper) {
        this(properties, mapper, ConfigStore.SYSTEM_DISK);
    }

    /** As the application's constructor, with the file system calls a test stands in for. */
    TestListStore(ConfigStoreProperties properties, JsonMapper mapper, ConfigStore.Disk disk) {
        this.mapper = mapper;
        this.disk = disk;
        if (Texts.blank(properties.directory())) {
            throw new IllegalStateException("config-store.directory is blank. Set it to the directory uploaded Postman files are kept in.");
        }
        try {
            this.folder = Path.of(properties.directory().strip()).toAbsolutePath().normalize().resolve(FOLDER);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("config-store.directory is \"" + properties.directory() + "\", which is not a usable path.", e);
        }
        if (Files.isDirectory(folder)) {
            // A folder copied in from elsewhere, or made by hand, is closed to other accounts too.
            OwnerOnly.restrict(folder);
        }
    }

    /** The folder the lists are kept in, named on screen so the tester knows where their entries are. */
    public Path folder() {
        return folder;
    }

    /** The entries of one list. A list never saved reads as empty and readable. */
    public <T> Loaded<T> read(String file, Class<T> type) {
        Path path = folder.resolve(file);
        try {
            if (!Files.exists(path)) {
                return new Loaded<>(List.of(), null);
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                // The path and the size only.
                log.warn("The saved test list {} is larger than {} bytes and was not read", path, MAX_FILE_BYTES);
                return new Loaded<>(List.of(), unreadable(path));
            }
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            JsonNode version = root == null ? null : root.get("version");
            JsonNode entries = root == null ? null : root.get("entries");
            if (version == null || !version.isIntegralNumber() || version.asInt() != VERSION || entries == null || !entries.isArray()) {
                // The path only. The content is member data.
                log.warn("The saved test list {} is not a version 1 list and was not read", path);
                return new Loaded<>(List.of(), unreadable(path));
            }
            List<T> read = new ArrayList<>();
            for (JsonNode entry : entries) {
                read.add(mapper.treeToValue(entry, type));
            }
            return new Loaded<>(read, null);
        } catch (NoSuchFileException e) {
            return new Loaded<>(List.of(), null);
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            // The path and the type of the failure. A parser's message can quote the content.
            log.warn("The saved test list {} could not be read ({})", path, e.getClass().getSimpleName());
            return new Loaded<>(List.of(), unreadable(path));
        }
    }

    private static String unreadable(Path path) {
        return "The file " + path + " could not be read, so this list is shown empty. PAVE does not write over it: move the file "
                + "aside, then add the entries again.";
    }

    /**
     * Writes the whole list. Refused, with nothing changed, when the file there cannot be read: a
     * save would replace entries the tester can still recover by hand.
     */
    public void write(String file, List<?> entries) {
        Path target = folder.resolve(file);
        Loaded<Object> before = read(file, Object.class);
        if (!before.readable()) {
            throw ConfigStoreException.ofThisComputer(before.problem() + " Nothing was changed.", null);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("entries", entries);
        // Every character outside ASCII as its escape, as store.json is written, so a name survives any editor.
        byte[] bytes = ConfigStore.asciiOnly(mapper.writeValueAsString(root)).getBytes(StandardCharsets.UTF_8);
        Path temporary = folder.resolve(file + TEMP_MARK + UUID.randomUUID());
        boolean written = false;
        try {
            Files.createDirectories(folder, OwnerOnly.directory());
            disk.writeNew(temporary, bytes);
            written = true;
            replace(temporary, target);
        } catch (ConfigStoreException e) {
            deleteQuietly(temporary);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            long usable = disk.usableSpace(folder);
            if (!written && usable >= 0 && usable < bytes.length + ConfigStore.SPACE_MARGIN_BYTES) {
                // The folder and the type of the failure. Never content.
                log.error("The saved test list {} could not be written: the disk is full ({})", target, e.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The disk that holds " + folder + " is full. Free some space, then try again. "
                        + "Nothing was changed.", e);
            }
            // The path and the type of the failure. Never content.
            log.error("The saved test list {} could not be written ({})", target, e.getClass().getSimpleName());
            throw new ConfigStoreException("The configuration store could not write " + target + ". Nothing was changed. Check that "
                    + "the directory exists, is writable and has free space.", e);
        }
        // The file and a count. Never an entry.
        log.info("Saved test list {}: {} entries", file, entries.size());
    }

    /**
     * Puts the written file in place, as the configuration store does: the read-only attribute of
     * Windows is taken off once, since the file is PAVE's own, and a refusal that may be another
     * program holding the file open is tried again for about a second.
     */
    private void replace(Path temporary, Path target) throws IOException {
        boolean clearedOnce = false;
        long waited = 0;
        while (true) {
            FileSystemException refused;
            try {
                disk.replace(temporary, target);
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                refused = e;
            }
            boolean readOnly = disk.readOnly(target);
            if (readOnly && !clearedOnce) {
                clearedOnce = true;
                if (disk.clearReadOnly(target)) {
                    continue;
                }
            }
            if (readOnly) {
                // The path and the type of the failure. Never content.
                log.error("The saved test list {} could not be written: it is marked read-only ({})", target,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + target + " is marked read-only. Clear Read-only in its "
                        + "Properties, then try again. Nothing was changed.", refused);
            }
            if (waited < ConfigStore.REPLACE_RETRY_MILLIS) {
                disk.pause(ConfigStore.REPLACE_RETRY_STEP_MILLIS);
                waited += ConfigStore.REPLACE_RETRY_STEP_MILLIS;
                continue;
            }
            if (disk.locksFiles() && Files.exists(target)) {
                // The path and the type of the failure. Never content.
                log.error("The saved test list {} could not be written: another program holds it open ({})", target,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + target + " is in use by another program (a sync or backup "
                        + "client, a virus scanner, an editor). Close it or wait, then try again. Nothing was changed.", refused);
            }
            throw refused;
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            // The path only.
            log.warn("The temporary file {} could not be deleted ({})", file, e.getClass().getSimpleName());
        }
    }

    @Override
    public String toString() {
        return "TestListStore[" + folder + "]";
    }
}
