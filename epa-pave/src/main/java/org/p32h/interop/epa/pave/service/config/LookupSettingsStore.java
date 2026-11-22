package org.p32h.interop.epa.pave.service.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The file of the lookups saved on screen (R-11): {@code lookups.json} in the configuration store
 * folder, {@code {"version": 1, "member": {...}, "provider": {...}}}, never in {@code store.json}.
 *
 * <p>It can hold an API key or a client secret typed as such, so the file is readable by this
 * account alone, written whole to a temporary name and moved into place, with every character
 * outside ASCII escaped, and the log names the file and never a value. A file that cannot be read
 * never stops the start: it reads as no lookup saved, with a line that says why, and it is never
 * written over, so a key in it is not lost to a save.
 *
 * <p>What was read is kept until the file's time or size changes, because the gateway asks for
 * the lookups' secrets on every call it prepares and a disk read each time would be waste. A file
 * put right by hand is read at the next question.
 */
@Component
public class LookupSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(LookupSettingsStore.class);

    public static final String FILE = "lookups.json";
    private static final int VERSION = 1;
    private static final String TEMP_MARK = ".tmp-";
    /** Far above two lookups of a few kilobytes each; a file larger than this is not one PAVE wrote. */
    static final long MAX_FILE_BYTES = 1024L * 1024;

    /**
     * What the file holds.
     *
     * @param member   the member lookup saved, or null
     * @param provider the provider lookup saved, or null
     * @param problem  why the file could not be read, naming it and what to do, or null. While set,
     *                 neither lookup is read from it and it is not written
     */
    public record Loaded(LookupSettings member, LookupSettings provider, String problem) {

        public static final Loaded NOTHING = new Loaded(null, null, null);

        public boolean readable() {
            return problem == null;
        }

        /** Whether each is saved; never a setting, since one may hold a key. */
        @Override
        public String toString() {
            return "Loaded[member=" + (member != null) + ", provider=" + (provider != null) + ", readable=" + readable() + "]";
        }
    }

    private record Seen(FileTime time, long size, Loaded loaded) {
    }

    private final Path folder;
    private final Path file;
    private final JsonMapper mapper;
    private final ConfigStore.Disk disk;
    private volatile Seen seen;

    @Autowired
    public LookupSettingsStore(ConfigStoreProperties properties, JsonMapper mapper) {
        this(properties, mapper, ConfigStore.SYSTEM_DISK);
    }

    /** As the application's constructor, with the file system calls a test stands in for. */
    LookupSettingsStore(ConfigStoreProperties properties, JsonMapper mapper, ConfigStore.Disk disk) {
        this.mapper = mapper;
        this.disk = disk;
        if (Texts.blank(properties.directory())) {
            throw new IllegalStateException("config-store.directory is blank. Set it to the directory uploaded Postman files are kept in.");
        }
        try {
            this.folder = Path.of(properties.directory().strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("config-store.directory is \"" + properties.directory() + "\", which is not a usable path.", e);
        }
        this.file = folder.resolve(FILE);
    }

    /** The file, named on screen so the tester knows where a stored key sits. */
    public Path file() {
        return file;
    }

    /** What the file holds now. A file never written reads as nothing saved, and readable. */
    public Loaded read() {
        FileTime time;
        long size;
        try {
            if (!Files.exists(file)) {
                seen = null;
                return Loaded.NOTHING;
            }
            time = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (IOException | RuntimeException e) {
            // The path and the type of the failure only.
            log.warn("The saved lookups file {} could not be looked at ({})", file, e.getClass().getSimpleName());
            return new Loaded(null, null, unreadable());
        }
        Seen known = seen;
        if (known != null && known.time().equals(time) && known.size() == size) {
            return known.loaded();
        }
        Loaded loaded = load(size);
        seen = new Seen(time, size, loaded);
        return loaded;
    }

    private Loaded load(long size) {
        if (size > MAX_FILE_BYTES) {
            log.warn("The saved lookups file {} is larger than {} bytes and was not read", file, MAX_FILE_BYTES);
            return new Loaded(null, null, unreadable());
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            JsonNode version = root == null ? null : root.get("version");
            if (root == null || !root.isObject() || version == null || !version.isIntegralNumber() || version.asInt() != VERSION) {
                // The path only. The content can hold a key.
                log.warn("The saved lookups file {} is not a version 1 file and was not read", file);
                return new Loaded(null, null, unreadable());
            }
            for (Map.Entry<String, JsonNode> property : org.p32h.interop.epa.pave.model.JsonSafe.properties(root)) {
                if (!property.getKey().equals("version") && !property.getKey().equals("member") && !property.getKey().equals("provider")) {
                    log.warn("The saved lookups file {} holds a part PAVE does not write and was not read", file);
                    return new Loaded(null, null, unreadable());
                }
            }
            return new Loaded(one(root.get("member")), one(root.get("provider")), null);
        } catch (NoSuchFileException e) {
            return Loaded.NOTHING;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            // The path and the type of the failure. A parser's message can quote the content.
            log.warn("The saved lookups file {} could not be read ({})", file, e.getClass().getSimpleName());
            return new Loaded(null, null, unreadable());
        }
    }

    private LookupSettings one(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("not an object");
        }
        return mapper.treeToValue(node, LookupSettings.class);
    }

    /** The line a screen shows while the file cannot be read. */
    public String unreadable() {
        return "The file " + file + " could not be read, so no lookup saved on screen is used and application.yml's lookups apply. "
                + "PAVE does not write over it: move the file aside, then save the lookups again.";
    }

    /**
     * Writes both lookups, null leaving one out. Refused, with nothing changed, while the file
     * there cannot be read: a save would replace settings the tester can still recover by hand.
     */
    public void write(LookupSettings member, LookupSettings provider) {
        Loaded before = read();
        if (!before.readable()) {
            throw ConfigStoreException.ofThisComputer(before.problem() + " Nothing was changed.", null);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        if (member != null) {
            root.put("member", member);
        }
        if (provider != null) {
            root.put("provider", provider);
        }
        byte[] bytes = ConfigStore.asciiOnly(mapper.writeValueAsString(root)).getBytes(StandardCharsets.UTF_8);
        Path temporary = folder.resolve(FILE + TEMP_MARK + UUID.randomUUID());
        boolean written = false;
        try {
            Files.createDirectories(folder, OwnerOnly.directory());
            disk.writeNew(temporary, bytes);
            written = true;
            replace(temporary);
        } catch (ConfigStoreException e) {
            deleteQuietly(temporary);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            long usable = disk.usableSpace(folder);
            if (!written && usable >= 0 && usable < bytes.length + ConfigStore.SPACE_MARGIN_BYTES) {
                log.error("The saved lookups file {} could not be written: the disk is full ({})", file, e.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The disk that holds " + folder + " is full. Free some space, then try again. "
                        + "Nothing was changed.", e);
            }
            // The path and the type of the failure. Never content: it can hold a key.
            log.error("The saved lookups file {} could not be written ({})", file, e.getClass().getSimpleName());
            throw new ConfigStoreException("The configuration store could not write " + file + ". Nothing was changed. Check that the "
                    + "directory exists, is writable and has free space.", e);
        }
        seen = null;
        // Which lookups are saved, never a setting.
        log.info("Saved lookups file {}: member {}, provider {}", FILE, member != null ? "saved" : "none", provider != null ? "saved" : "none");
    }

    /** Puts the written file in place as the test lists do: read-only cleared once, a lock waited on for about a second. */
    private void replace(Path temporary) throws IOException {
        boolean clearedOnce = false;
        long waited = 0;
        while (true) {
            FileSystemException refused;
            try {
                disk.replace(temporary, file);
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                refused = e;
            }
            boolean readOnly = disk.readOnly(file);
            if (readOnly && !clearedOnce) {
                clearedOnce = true;
                if (disk.clearReadOnly(file)) {
                    continue;
                }
            }
            if (readOnly) {
                log.error("The saved lookups file {} could not be written: it is marked read-only ({})", file,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + file + " is marked read-only. Clear Read-only in its "
                        + "Properties, then try again. Nothing was changed.", refused);
            }
            if (waited < ConfigStore.REPLACE_RETRY_MILLIS) {
                disk.pause(ConfigStore.REPLACE_RETRY_STEP_MILLIS);
                waited += ConfigStore.REPLACE_RETRY_STEP_MILLIS;
                continue;
            }
            if (disk.locksFiles() && Files.exists(file)) {
                log.error("The saved lookups file {} could not be written: another program holds it open ({})", file,
                        refused.getClass().getSimpleName());
                throw ConfigStoreException.ofThisComputer("The file " + file + " is in use by another program (a sync or backup "
                        + "client, a virus scanner, an editor). Close it or wait, then try again. Nothing was changed.", refused);
            }
            throw refused;
        }
    }

    private void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException e) {
            log.warn("The temporary file {} could not be deleted ({})", temporary, e.getClass().getSimpleName());
        }
    }

    @Override
    public String toString() {
        return "LookupSettingsStore[" + file + "]";
    }
}
