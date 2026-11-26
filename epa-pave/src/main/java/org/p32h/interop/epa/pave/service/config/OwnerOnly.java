package org.p32h.interop.epa.pave.service.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Files and folders of the configuration store, readable by the account that runs the tool and by
 * no other.
 *
 * <p>The store holds uploaded environments, which can carry a client secret in plain text, and the
 * salt that keys the member tokens of the ledger. Where the file system knows about owners and
 * permission bits (Linux, macOS), a folder is created as rwx------ and a file as rw-------. Where it
 * does not (Windows, whose NTFS uses access lists), nothing is set here and a file inherits the
 * access list of the folder it is created in, so the store should sit inside the user's own profile.
 */
final class OwnerOnly {

    private static final Logger log = LoggerFactory.getLogger(OwnerOnly.class);

    private static final Set<PosixFilePermission> FILE = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIRECTORY = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private OwnerOnly() {
    }

    static boolean supported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    static FileAttribute<?>[] file() {
        return supported() ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)} : new FileAttribute<?>[0];
    }

    static FileAttribute<?>[] directory() {
        return supported() ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY)} : new FileAttribute<?>[0];
    }

    /**
     * Closes a folder and the regular files directly in it to other accounts. A link is not
     * followed. A failure is logged with the path and the kind of failure, and the tool goes on:
     * the store still works, and the warning says where to look.
     */
    static void restrict(Path folder) {
        if (!supported()) {
            return;
        }
        set(folder, DIRECTORY);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    set(entry, FILE);
                }
            }
        } catch (IOException | RuntimeException e) {
            // The path and the kind of failure only.
            log.warn("The configuration store could not list {} to close its files to other accounts ({})", folder, e.getClass().getSimpleName());
        }
    }

    private static void set(Path path, Set<PosixFilePermission> permissions) {
        try {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException | RuntimeException e) {
            // The path and the kind of failure only.
            log.warn("The configuration store could not make {} readable by its owner only ({})", path, e.getClass().getSimpleName());
        }
    }
}
