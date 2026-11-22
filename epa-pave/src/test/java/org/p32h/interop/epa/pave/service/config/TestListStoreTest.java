package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.JacksonConfig;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.service.lists.TestListViews.ImportRequest;
import org.p32h.interop.epa.pave.service.lists.TestMember;
import org.p32h.interop.epa.pave.service.lists.TestMemberInput;
import org.p32h.interop.epa.pave.service.lists.TestProvider;
import org.p32h.interop.epa.pave.service.lists.TestProviderInput;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/** The files of the saved test lists: kept, closed to other accounts, written whole, never logged. */
@ExtendWith(OutputCaptureExtension.class)
class TestListStoreTest {

    private static final String MEMBER_ID = "S123456789";
    private static final String SURNAME = "Placeholder-Surname";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private static JsonMapper mapper() {
        return JacksonConfig.strict(JsonMapper.builder()).build();
    }

    private TestListStore store() {
        return new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), mapper());
    }

    private static TestMember member(String id, String memberId) {
        return new TestMember(id, "Needs prior authorization", memberId, "Sample", SURNAME, "1980-01-31", "female", "Commercial", "Gold",
                memberId, "active", "2026-01-01", "2026-12-31", "patient-7");
    }

    private static TestMemberInput input(String memberId) {
        return new TestMemberInput("Needs prior authorization", memberId, "Sample", SURNAME, "1980-01-31", "female", "Commercial", "Gold",
                memberId, "active", "2026-01-01", "2026-12-31", "patient-7");
    }

    @Test
    void aSavedListIsReadBackByAStoreOpenedLaterOnTheSameFolder() {
        store().write(TestListStore.MEMBERS, List.of(member("m-1", MEMBER_ID), member("m-2", "S123456788")));
        store().write(TestListStore.PROVIDERS, List.of(new TestProvider("p-1", "Ordering", "1234567893", "P123", null, null, "Sample Clinic",
                null, null, null, "11")));

        TestListStore reopened = store();
        TestListStore.Loaded<TestMember> members = reopened.read(TestListStore.MEMBERS, TestMember.class);
        assertThat(members.readable()).isTrue();
        assertThat(members.entries()).containsExactly(member("m-1", MEMBER_ID), member("m-2", "S123456788"));
        TestListStore.Loaded<TestProvider> providers = reopened.read(TestListStore.PROVIDERS, TestProvider.class);
        assertThat(providers.entries()).singleElement().satisfies(provider -> {
            assertThat(provider.vendorPractitionerId()).isEqualTo("P123");
            assertThat(provider.firstName()).isNull();
            assertThat(provider.placeOfService()).isEqualTo("11");
        });
        assertThat(reopened.folder()).isEqualTo(directory.toAbsolutePath().normalize().resolve("test-lists"));
    }

    @Test
    void aListNeverSavedIsEmptyAndReadableAndCreatesNoFolder() {
        TestListStore.Loaded<TestMember> members = store().read(TestListStore.MEMBERS, TestMember.class);
        assertThat(members.entries()).isEmpty();
        assertThat(members.readable()).isTrue();
        assertThat(directory.resolve("test-lists")).doesNotExist();
    }

    @Test
    void theFileHoldsAVersionAndTheEntriesWithEveryCharacterOutsideAsciiEscaped() throws IOException {
        TestMember accented = new TestMember("m-1", "Café member", MEMBER_ID, null, null, null, null, null, null, null, null, null,
                null, null);
        store().write(TestListStore.MEMBERS, List.of(accented));

        String text = Files.readString(directory.resolve("test-lists").resolve("members.json"), StandardCharsets.UTF_8);
        assertThat(text).startsWith("{\"version\":1,\"entries\":[").contains("Caf\\u00e9 member").doesNotContain("\"firstName\"");
        assertThat(text.chars().allMatch(c -> c < 0x80)).isTrue();
        assertThat(store().read(TestListStore.MEMBERS, TestMember.class).entries()).containsExactly(accented);
    }

    @Test
    void theFolderAndTheFilesAreReadableByTheirOwnerOnly() throws IOException {
        assumeThat(OwnerOnly.supported()).as("a file system with owners and permission bits").isTrue();
        store().write(TestListStore.MEMBERS, List.of(member("m-1", MEMBER_ID)));

        Path folder = directory.resolve("test-lists");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve("members.json")))).isEqualTo("rw-------");

        // A folder made by hand, or copied in, is closed when the store opens.
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(folder.resolve("members.json"), PosixFilePermissions.fromString("rw-r--r--"));
        store();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve("members.json")))).isEqualTo("rw-------");
    }

    /** The file system itself, with each call recorded. */
    private static final class RecordingDisk implements ConfigStore.Disk {

        final List<String> calls = new ArrayList<>();
        byte[] lastBytesOnDiskAtReplace;

        @Override
        public void writeNew(Path file, byte[] bytes) throws IOException {
            calls.add("writeNew " + file.getFileName());
            ConfigStore.SYSTEM_DISK.writeNew(file, bytes);
        }

        @Override
        public void replace(Path file, Path target) throws IOException {
            calls.add("replace " + file.getFileName() + " -> " + target.getFileName());
            lastBytesOnDiskAtReplace = Files.exists(target) ? Files.readAllBytes(target) : null;
            ConfigStore.SYSTEM_DISK.replace(file, target);
        }

        @Override
        public boolean readOnly(Path file) {
            return false;
        }

        @Override
        public boolean clearReadOnly(Path file) {
            return false;
        }

        @Override
        public long usableSpace(Path folder) {
            return -1;
        }

        @Override
        public boolean locksFiles() {
            return false;
        }

        @Override
        public void pause(long millis) {
        }
    }

    @Test
    void aListIsWrittenWholeToATemporaryFileThenMovedOverTheOldOne() throws IOException {
        RecordingDisk disk = new RecordingDisk();
        TestListStore store = new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), mapper(), disk);
        store.write(TestListStore.MEMBERS, List.of(member("m-1", MEMBER_ID)));
        byte[] first = Files.readAllBytes(directory.resolve("test-lists").resolve("members.json"));
        store.write(TestListStore.MEMBERS, List.of(member("m-1", MEMBER_ID), member("m-2", "S123456788")));

        assertThat(disk.calls).hasSize(4);
        assertThat(disk.calls.get(2)).startsWith("writeNew members.json.tmp-");
        String temporary = disk.calls.get(2).substring("writeNew ".length());
        assertThat(disk.calls.get(3)).isEqualTo("replace " + temporary + " -> members.json");
        // Until the move, the file in place is the whole earlier list.
        assertThat(disk.lastBytesOnDiskAtReplace).isEqualTo(first);
        try (var files = Files.list(directory.resolve("test-lists"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("members.json");
        }
    }

    @Test
    void aFileThatCannotBeReadShowsEmptyWithItsPathAndIsNeverWrittenOver(CapturedOutput output) throws IOException {
        Path folder = Files.createDirectories(directory.resolve("test-lists"));
        String broken = "{\"version\":1,\"entries\":[{\"memberId\":\"" + MEMBER_ID + "\",\"lastName\":\"" + SURNAME + "\"";
        Files.writeString(folder.resolve("members.json"), broken);
        TestListStore store = store();

        TestListStore.Loaded<TestMember> members = store.read(TestListStore.MEMBERS, TestMember.class);
        assertThat(members.entries()).isEmpty();
        assertThat(members.readable()).isFalse();
        assertThat(members.problem()).contains(folder.resolve("members.json").toString()).contains("PAVE does not write over it");

        assertThatThrownBy(() -> store.write(TestListStore.MEMBERS, List.of(member("m-1", "S123456788"))))
                .isInstanceOfSatisfying(ConfigStoreException.class, e -> {
                    assertThat(e.thisComputer()).isTrue();
                    assertThat(e.getMessage()).endsWith("Nothing was changed.");
                });
        assertThat(Files.readString(folder.resolve("members.json"))).isEqualTo(broken);
        assertThat(output.getAll()).contains("members.json").doesNotContain(MEMBER_ID).doesNotContain(SURNAME);

        // A list of another version, or an entry with a field this version does not know, is not read either.
        Files.writeString(folder.resolve("members.json"), "{\"version\":2,\"entries\":[]}");
        assertThat(store.read(TestListStore.MEMBERS, TestMember.class).readable()).isFalse();
        Files.writeString(folder.resolve("members.json"), "{\"version\":1,\"entries\":[{\"memberId\":\"S1\",\"nickname\":\"x\"}]}");
        assertThat(store.read(TestListStore.MEMBERS, TestMember.class).readable()).isFalse();
        // Moved aside, the list reads as empty and can be saved again, without a restart.
        Files.delete(folder.resolve("members.json"));
        store.write(TestListStore.MEMBERS, List.of(member("m-1", MEMBER_ID)));
        assertThat(store.read(TestListStore.MEMBERS, TestMember.class).entries()).hasSize(1);
    }

    @Test
    void theLogHoldsTheFileAndCountsNeverAnEntry(CapturedOutput output) {
        TestListService service = new TestListService(TestListProperties.defaults(), store(), CLOCK);
        service.addMember(input(MEMBER_ID));
        service.addProvider(new TestProviderInput("Ordering", "1234567893", "P123", "Sample", "Placeholder-Doctor", null, null,
                "617-555-0101", null, "11"));
        service.importMembers(new ImportRequest("memberId,lastName,plan\r\nS123456788," + SURNAME + ",Gold\r\n,Other-Surname,Gold\r\n", false));

        String log = output.getAll();
        assertThat(log).contains("Saved test list members.json: 1 entries").contains("Saved test list members.json: 2 entries")
                .contains("Saved test list providers.json: 1 entries")
                .contains("Test members import: 1 to add, 0 to replace, 1 refused");
        assertThat(log).doesNotContain(MEMBER_ID).doesNotContain("S123456788").doesNotContain(SURNAME).doesNotContain("Placeholder-Doctor")
                .doesNotContain("1980-01-31").doesNotContain("patient-7").doesNotContain("Gold");
    }

    @Test
    void noTypeOfTheListsPrintsWhatAnEntryHolds() {
        TestMember member = member("m-1", MEMBER_ID);
        TestMemberInput input = input(MEMBER_ID);
        TestProvider provider = new TestProvider("p-1", "Ordering", "1234567893", "P123", "Sample", SURNAME, null, null, null, null, null);
        TestProviderInput providerInput = new TestProviderInput("Ordering", "1234567893", "P123", "Sample", SURNAME, null, null, null,
                null, null);
        TestListStore.Loaded<TestMember> loaded = new TestListStore.Loaded<>(List.of(member), null);
        ImportRequest request = new ImportRequest("memberId\r\n" + MEMBER_ID + "\r\n", true);
        List<Object> all = List.of(member, input, provider, providerInput, loaded, request, store());
        for (Object value : all) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain(MEMBER_ID).doesNotContain(SURNAME)
                    .doesNotContain("1980").doesNotContain("patient-7").doesNotContain("P123");
        }
        assertThat(member.toString()).isEqualTo("TestMember[id=m-1]");
        assertThat(loaded.toString()).isEqualTo("Loaded[entries=1, readable=true]");
    }

    @Test
    void aListKeepsTwoHundredEntriesAndRefusesTheNext() {
        TestListService service = new TestListService(TestListProperties.defaults(), store(), CLOCK);
        for (int i = 0; i < 200; i++) {
            service.addMember(input("S1" + String.format("%08d", i)));
        }
        assertThatThrownBy(() -> service.addMember(input("S199999999")))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("This list holds 200 entries, the most it keeps.");
                    assertThat(e.action()).isEqualTo("Remove an entry you no longer use, then add this one.");
                });
        assertThat(store().read(TestListStore.MEMBERS, TestMember.class).entries()).hasSize(200);

        // An import that would go past the limit refuses each row beyond it, and replaces still go through.
        var result = service.importMembers(new ImportRequest("memberId,plan\r\nS199999998,Gold\r\nS100000000,Silver\r\n", false));
        assertThat(result.rows()).extracting(row -> row.status().name()).containsExactly("REFUSED", "REPLACE");
        assertThat(result.rows().get(0).reason()).isEqualTo("This list holds 200 entries, the most it keeps.");
        assertThat(store().read(TestListStore.MEMBERS, TestMember.class).entries()).hasSize(200)
                .filteredOn(entry -> "S100000000".equals(entry.memberId())).singleElement()
                .satisfies(entry -> assertThat(entry.plan()).isEqualTo("Silver"));
    }
}
