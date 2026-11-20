package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;

/**
 * m4: the configuration store's folders and every file in them are readable by their owner only,
 * where the file system has owners. On Windows this test does not run: nothing is set there, and a
 * file inherits the access list of its folder.
 */
class OwnerOnlyStoreTest {

    @TempDir
    Path directory;

    private static String mode(Path path) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void everyFolderAndFileTheStoreCreatesIsForItsOwnerOnly() throws Exception {
        assumeTrue(OwnerOnly.supported(), "the file system has no owner permissions");
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("store"));
        fixture.environmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", "https://sandbox.example.invalid", "clientSecret",
                "placeholder-secret");
        fixture.collection(org.p32h.interop.epa.pave.postman.PostmanFiles.VENDOR_COLLECTION);
        Path store = fixture.store.directory();
        try (Stream<Path> all = Files.walk(store)) {
            for (Path path : all.toList()) {
                assertThat(mode(path)).as(store.relativize(path).toString()).isEqualTo(Files.isDirectory(path) ? "rwx------" : "rw-------");
            }
        }
    }

    @Test
    void aStoreFolderAndFilesMadeEarlierWithOpenModesAreClosedAtStartUp() throws Exception {
        assumeTrue(OwnerOnly.supported(), "the file system has no owner permissions");
        Path store = Files.createDirectories(directory.resolve("open"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path environments = Files.createDirectories(store.resolve("environments"));
        Files.setPosixFilePermissions(environments, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path left = Files.writeString(environments.resolve("left-by-hand.json"), "{}");
        Files.setPosixFilePermissions(left, PosixFilePermissions.fromString("rw-r--r--"));

        ConfigFixture.mock(store);
        assertThat(mode(store)).isEqualTo("rwx------");
        assertThat(mode(environments)).isEqualTo("rwx------");
        assertThat(mode(left)).isEqualTo("rw-------");
    }
}
