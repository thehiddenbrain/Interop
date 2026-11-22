package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The configuration store tells a problem with this computer's files from a failure of its own
 * (R-32). A workstation marks a file read-only, a sync client or a scanner holds one open, a disk
 * fills up, a clean-up removes the store folder: each is named with what to do about it, nothing
 * is changed, and the answer is marked as the computer's so it is not headed as a failure of this
 * tool. Windows alone refuses to replace a read-only or an open file, and a full disk cannot be
 * made here, so those refusals are planted through the store's file system seam; a removed folder
 * is removed for real.
 */
@ExtendWith(OutputCaptureExtension.class)
class StoreWriteFailuresTest {

    /** A placeholder secret every environment here carries. No sentence and no log line may hold it. */
    private static final String SECRET = "placeholder-secret-r32";
    /** The text of each planted exception. The store never passes an exception's message on, so it may appear nowhere. */
    private static final String PLANTED = "planted-reason-r32";

    @TempDir
    Path temporary;

    /** The file system itself, with the refusals a test plants on top of it. */
    static final class PlantedDisk implements ConfigStore.Disk {

        private final ConfigStore.Disk real = ConfigStore.SYSTEM_DISK;
        /** File names Windows will not replace because they carry the read-only attribute. */
        final Set<String> readOnly = new HashSet<>();
        boolean attributeCanBeCleared = true;
        /** File names another program holds open, refused as Windows refuses them. */
        final Set<String> heldOpen = new HashSet<>();
        /** How many more replaces of a held file are refused before the other program lets go. */
        int refusalsLeft = Integer.MAX_VALUE;
        boolean windows = true;
        /** Thrown by the next write of a new file, then cleared. */
        IOException failNextWrite;
        /** The free space to report, or -1 for what the disk really has. */
        long usable = -1;
        final List<Path> clears = new ArrayList<>();
        int refusals;
        long paused;

        @Override
        public void writeNew(Path file, byte[] bytes) throws IOException {
            if (failNextWrite != null) {
                IOException failure = failNextWrite;
                failNextWrite = null;
                throw failure;
            }
            real.writeNew(file, bytes);
        }

        @Override
        public void replace(Path file, Path target) throws IOException {
            String name = target.getFileName().toString();
            if (readOnly.contains(name) && Files.exists(target)) {
                refusals++;
                throw new AccessDeniedException(target.toString(), null, PLANTED);
            }
            if (heldOpen.contains(name) && refusalsLeft > 0) {
                refusals++;
                refusalsLeft--;
                // A file held without delete sharing: Windows answers with a sharing violation, which Java gives as this type.
                throw new FileSystemException(target.toString(), null, PLANTED);
            }
            real.replace(file, target);
        }

        @Override
        public boolean readOnly(Path file) {
            return readOnly.contains(file.getFileName().toString()) && Files.exists(file);
        }

        @Override
        public boolean clearReadOnly(Path file) {
            clears.add(file);
            if (!attributeCanBeCleared) {
                return false;
            }
            readOnly.remove(file.getFileName().toString());
            return true;
        }

        @Override
        public long usableSpace(Path folder) {
            return usable >= 0 ? usable : real.usableSpace(folder);
        }

        @Override
        public boolean locksFiles() {
            return windows;
        }

        @Override
        public void pause(long millis) {
            paused += millis;
        }
    }

    private Path directory() {
        return temporary.resolve("config-store");
    }

    private Path state() {
        return directory().resolve(ConfigStore.STATE_FILE);
    }

    private ConfigStore store(PlantedDisk disk) {
        return new ConfigStore(ConfigStoreProperties.of(Map.of("directory", directory().toString())), OnyxProperties.of(Map.of()),
                ConfigFixture.CLOCK, disk);
    }

    private static byte[] environment(String name) {
        return environment(name, "https://sandbox.vendor.example.invalid/fhir");
    }

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\", \"enabled\": true}, "
                + "{\"key\": \"clientSecret\", \"value\": \"" + SECRET + "\", \"type\": \"secret\", \"enabled\": true}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void upload(ConfigStore store, String name) {
        store.uploadEnvironment(name + ".json", environment(name), EnvironmentKind.SANDBOX, false);
    }

    /** Every regular file under the store, so a test can see that a refused save left nothing behind. */
    private List<Path> files() throws IOException {
        try (Stream<Path> walk = Files.walk(directory())) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void removeTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // A file marked read-only
    // ------------------------------------------------------------------------------------------

    @Test
    void aStoreFileMarkedReadOnlyIsClearedOnceAndTheSaveGoesThrough(CapturedOutput output) throws IOException {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        disk.readOnly.add(ConfigStore.STATE_FILE);

        upload(store, "Vendor Sandbox B");

        assertThat(store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Vendor Sandbox A", "Vendor Sandbox B");
        assertThat(disk.clears).containsExactly(state());
        assertThat(disk.readOnly).isEmpty();
        // A read-only refusal is not waited out: it does not go away by itself.
        assertThat(disk.paused).isZero();
        assertThat(Files.readString(state())).contains("\"Vendor Sandbox B\"");
        assertThat(store(new PlantedDisk()).snapshot().environments()).hasSize(2);
        assertThat(output).contains("took the read-only attribute off " + state()).doesNotContain(SECRET).doesNotContain(PLANTED);
    }

    @Test
    void aStoreFileMarkedReadOnlyThatCannotBeClearedIsNamedAndNothingChanges(CapturedOutput output) throws IOException {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        ConfigSnapshot before = store.snapshot();
        byte[] stateBefore = Files.readAllBytes(state());
        List<Path> filesBefore = files();
        disk.readOnly.add(ConfigStore.STATE_FILE);
        disk.attributeCanBeCleared = false;

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox B")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The file " + state() + " is marked read-only. Clear Read-only in its Properties, then try "
                    + "again. Nothing was changed.");
            assertThat(refused.thisComputer()).isTrue();
        });

        // Cleared once, then named: it is not tried again and again.
        assertThat(disk.clears).containsExactly(state());
        assertThat(disk.refusals).isEqualTo(1);
        assertThat(disk.paused).isZero();
        assertThat(store.snapshot()).isSameAs(before);
        assertThat(Files.readAllBytes(state())).isEqualTo(stateBefore);
        // The file the refused save wrote for the new environment is taken away again, and no temporary file is left.
        assertThat(files()).isEqualTo(filesBefore);
        assertThat(output).contains("could not write " + state() + ": it is marked read-only (AccessDeniedException)")
                .doesNotContain(SECRET).doesNotContain(PLANTED);
    }

    // ------------------------------------------------------------------------------------------
    // A file another program holds open
    // ------------------------------------------------------------------------------------------

    @Test
    void aStoreFileHeldOpenPastTheRetriesIsNamedAsInUseAndNothingChanges(CapturedOutput output) throws IOException {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        ConfigSnapshot before = store.snapshot();
        byte[] stateBefore = Files.readAllBytes(state());
        List<Path> filesBefore = files();
        disk.heldOpen.add(ConfigStore.STATE_FILE);

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox B")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The file " + state() + " is in use by another program (a sync or backup client, a virus "
                    + "scanner, an editor). Close it or wait, then try again. Nothing was changed.");
            assertThat(refused.thisComputer()).isTrue();
        });

        // About a second of attempts, a tenth of a second apart, and then the answer.
        assertThat(disk.paused).isEqualTo(ConfigStore.REPLACE_RETRY_MILLIS);
        assertThat(disk.refusals).isEqualTo(1 + (int) (ConfigStore.REPLACE_RETRY_MILLIS / ConfigStore.REPLACE_RETRY_STEP_MILLIS));
        assertThat(disk.clears).isEmpty();
        assertThat(store.snapshot()).isSameAs(before);
        assertThat(Files.readAllBytes(state())).isEqualTo(stateBefore);
        assertThat(files()).isEqualTo(filesBefore);
        assertThat(output).contains("could not write " + state() + ": another program holds it open (FileSystemException)")
                .doesNotContain(SECRET).doesNotContain(PLANTED);
    }

    @Test
    void aStoreFileHeldOpenForAMomentIsReplacedOnALaterAttempt() throws IOException {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        disk.heldOpen.add(ConfigStore.STATE_FILE);
        disk.refusalsLeft = 3;

        upload(store, "Vendor Sandbox B");

        assertThat(disk.refusals).isEqualTo(3);
        assertThat(disk.paused).isEqualTo(3 * ConfigStore.REPLACE_RETRY_STEP_MILLIS);
        assertThat(store.snapshot().environments()).hasSize(2);
        assertThat(Files.readString(state())).contains("\"Vendor Sandbox B\"");
    }

    @Test
    void whereAnOpenFileCannotStopAReplaceTheRefusalKeepsTheGeneralSentence() throws IOException {
        PlantedDisk disk = new PlantedDisk();
        disk.windows = false;
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        ConfigSnapshot before = store.snapshot();
        disk.heldOpen.add(ConfigStore.STATE_FILE);

        // Linux and macOS replace a file whatever holds it open, so their refusal has another cause, which is not guessed at.
        assertThatThrownBy(() -> upload(store, "Vendor Sandbox B")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The configuration store could not write " + state() + ". Nothing was changed. Check that "
                    + "the directory exists, is writable and has free space.");
            assertThat(refused.thisComputer()).isFalse();
        });
        assertThat(disk.paused).isEqualTo(ConfigStore.REPLACE_RETRY_MILLIS);
        assertThat(store.snapshot()).isSameAs(before);
    }

    // ------------------------------------------------------------------------------------------
    // A full disk
    // ------------------------------------------------------------------------------------------

    @Test
    void aFullDiskIsNamedAndNothingChanges(CapturedOutput output) throws IOException {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        upload(store, "Vendor Sandbox A");
        ConfigSnapshot before = store.snapshot();
        byte[] stateBefore = Files.readAllBytes(state());
        List<Path> filesBefore = files();
        disk.failNextWrite = new IOException(PLANTED);
        disk.usable = 1000;

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox B")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The disk that holds " + directory().resolve(ConfigStore.ENVIRONMENTS) + " is full. Free some "
                    + "space, then try again. Nothing was changed.");
            assertThat(refused.thisComputer()).isTrue();
        });

        assertThat(store.snapshot()).isSameAs(before);
        assertThat(Files.readAllBytes(state())).isEqualTo(stateBefore);
        assertThat(files()).isEqualTo(filesBefore);
        assertThat(output).contains("the disk is full (IOException)").doesNotContain(SECRET).doesNotContain(PLANTED);
    }

    @Test
    void aWriteThatFailsWithRoomLeftOnTheDiskIsNotCalledAFullDisk() {
        PlantedDisk disk = new PlantedDisk();
        ConfigStore store = store(disk);
        disk.failNextWrite = new IOException(PLANTED);
        disk.usable = 10L * 1024 * 1024 * 1024;

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox A")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused.getMessage()).startsWith("The configuration store could not write " + directory().resolve(ConfigStore.ENVIRONMENTS))
                    .endsWith("Nothing was changed. Check that the directory exists, is writable and has free space.")
                    .doesNotContain(PLANTED);
            assertThat(refused.thisComputer()).isFalse();
        });
        assertThat(store.snapshot().environments()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // A store folder removed while the tool runs
    // ------------------------------------------------------------------------------------------

    @Test
    void aStoreFolderRemovedWhileTheToolRunsIsMadeAgainAndTheSaveGoesThrough() throws IOException {
        ConfigStore store = store(new PlantedDisk());
        // A clean-up of the project folder takes the whole store with it, the tool still running.
        removeTree(directory());

        upload(store, "Vendor Sandbox A");

        assertThat(store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Vendor Sandbox A");
        assertThat(Files.isRegularFile(state())).isTrue();
        assertThat(Files.isDirectory(directory().resolve(ConfigStore.ENVIRONMENTS))).isTrue();
        if (OwnerOnly.supported()) {
            // Made again for the owner alone, as the store first made it: an environment file can hold a client secret.
            assertThat(Files.getPosixFilePermissions(directory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(directory().resolve(ConfigStore.ENVIRONMENTS)))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }

        // Each folder is made again when something is written into it: the collections folder at the first collection.
        assertThat(Files.exists(directory().resolve(ConfigStore.COLLECTIONS))).isFalse();
        store.uploadCollection("c.json", ("{\"info\": {\"name\": \"Vendor Collection\", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": []}").getBytes(StandardCharsets.UTF_8));
        assertThat(Files.isDirectory(directory().resolve(ConfigStore.COLLECTIONS))).isTrue();

        // What was written is what a restart reads.
        ConfigSnapshot restarted = store(new PlantedDisk()).snapshot();
        assertThat(restarted.environments()).extracting(StoredEnvironment::name).containsExactly("Vendor Sandbox A");
        assertThat(restarted.collections()).extracting(StoredCollection::name).containsExactly("Vendor Collection");
    }

    // ------------------------------------------------------------------------------------------
    // Stored files gone from under the running tool
    // ------------------------------------------------------------------------------------------

    @Test
    void aSaveAfterTheWholeStoreFolderWentIsRefusedAndTheStartItAsksForOpens(CapturedOutput output) throws IOException {
        ConfigStore store = store(new PlantedDisk());
        upload(store, "Vendor Sandbox A");
        upload(store, "Vendor Sandbox B");
        ConfigSnapshot before = store.snapshot();
        removeTree(directory());

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox C")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The configuration store folder " + directory() + " changed while PAVE ran: 2 stored files are "
                    + "missing. Stop PAVE and start it again.");
            assertThat(refused.thisComputer()).isTrue();
        });

        // No store.json names the missing files, and the file the refused upload wrote is taken away again.
        assertThat(store.snapshot()).isSameAs(before);
        assertThat(Files.exists(state())).isFalse();
        assertThat(files()).isEmpty();
        // So the start the sentence asks for opens the store as it now is.
        assertThat(store(new PlantedDisk()).snapshot().environments()).isEmpty();
        assertThat(output).contains("is missing 2 of the files it names, so the change was refused").doesNotContain(SECRET);
    }

    @Test
    void aSaveWhileOneStoredFileIsGoneIsRefusedAndGoesThroughOnceItIsBack() throws IOException {
        ConfigStore store = store(new PlantedDisk());
        upload(store, "Vendor Sandbox A");
        upload(store, "Vendor Sandbox B");
        ConfigSnapshot before = store.snapshot();
        byte[] stateBefore = Files.readAllBytes(state());
        Path gone = directory().resolve(before.byName("Vendor Sandbox A").file());
        byte[] kept = Files.readAllBytes(gone);
        // A scanner takes one file into quarantine. store.json still names it.
        Files.delete(gone);

        assertThatThrownBy(() -> upload(store, "Vendor Sandbox C")).isInstanceOfSatisfying(ConfigStoreException.class, refused -> {
            assertThat(refused).hasMessage("The configuration store folder " + directory() + " changed while PAVE ran: 1 stored file is "
                    + "missing. Put it back and try again, or stop PAVE, move the folder aside and start PAVE again with an empty store.");
            assertThat(refused.thisComputer()).isTrue();
        });
        assertThat(store.snapshot()).isSameAs(before);
        assertThat(Files.readAllBytes(state())).isEqualTo(stateBefore);
        // Why the sentence does not offer a restart here: a start reads that store.json and refuses it.
        assertThatThrownBy(() -> store(new PlantedDisk())).isInstanceOf(IllegalStateException.class).hasMessageContaining(gone.toString());

        // Put back, the same save goes through, with no restart.
        Files.write(gone, kept);
        upload(store, "Vendor Sandbox C");
        assertThat(store.snapshot().environments()).hasSize(3);
        assertThat(store(new PlantedDisk()).snapshot().environments()).hasSize(3);
    }

    @Test
    void anEnvironmentWhoseFileIsGoneCanStillBeRemovedAndNothingElseIsCommitted() throws IOException {
        ConfigStore store = store(new PlantedDisk());
        upload(store, "Vendor Sandbox A");
        upload(store, "Vendor Sandbox B");
        Files.delete(directory().resolve(store.snapshot().byName("Vendor Sandbox A").file()));

        // Any other change would write a store.json that names the missing file, so it is refused, an activation too.
        assertThatThrownBy(() -> store.activate("Vendor Sandbox B")).isInstanceOf(ConfigStoreException.class)
                .hasMessageContaining(": 1 stored file is missing.");
        assertThat(store.snapshot().activeName()).isNull();

        store.removeEnvironment("Vendor Sandbox A");

        assertThat(store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Vendor Sandbox B");
        assertThat(store(new PlantedDisk()).snapshot().environments()).extracting(StoredEnvironment::name)
                .containsExactly("Vendor Sandbox B");
    }

    @Test
    void aSecondCopyOnTheSameFolderCannotLeaveAStoreThatStopsTheNextStart() {
        // Two copies on one folder, as the hunt ran them: each holds its own picture of the store in memory.
        ConfigStore first = store(new PlantedDisk());
        upload(first, "Vendor Sandbox A");
        ConfigStore second = store(new PlantedDisk());
        upload(second, "Vendor Sandbox B");
        // The first replaces A with new content, which removes the file of A that the second still names.
        first.uploadEnvironment("a2.json", environment("Vendor Sandbox A", "https://sandbox-a2.vendor.example.invalid/fhir"),
                EnvironmentKind.SANDBOX, false);

        // The second would have written a store.json naming that file, and the next start would have been refused.
        assertThatThrownBy(() -> upload(second, "Vendor Sandbox C")).isInstanceOfSatisfying(ConfigStoreException.class, refused ->
                assertThat(refused).hasMessage("The configuration store folder " + directory() + " changed while PAVE ran: 1 stored "
                        + "file is missing. Stop PAVE and start it again."));

        // The start the sentence asks for reads what the first copy wrote, and opens.
        assertThat(store(new PlantedDisk()).snapshot().environments()).extracting(StoredEnvironment::name)
                .containsExactly("Vendor Sandbox A");
    }
}
