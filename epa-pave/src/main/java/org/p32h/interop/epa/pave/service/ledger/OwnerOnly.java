package org.p32h.interop.epa.pave.service.ledger;

import java.nio.file.FileSystems;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

/**
 * The permissions the ledger creates its folder and its files with: for their owner only.
 *
 * <p>The ledger holds member tokens, and member IDs where they are recorded, and the salt holds the
 * key to the tokens. Neither is for every account on the machine. Where the file system knows about
 * owners and modes (Linux, macOS) a new folder is created as {@code rwx------} and a new file as
 * {@code rw-------}. Where it does not (Windows), nothing is set here and a new file takes the
 * permissions of the folder it is created in, which under the tester's own profile is that tester
 * and the administrators. A folder or a file that already exists is left as it is: changing the
 * permissions of something the operator made is theirs to decide.
 */
final class OwnerOnly {

    private OwnerOnly() {
    }

    static boolean supported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    static FileAttribute<?>[] file() {
        return supported() ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))} : new FileAttribute<?>[0];
    }

    static FileAttribute<?>[] directory() {
        return supported() ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))}
                : new FileAttribute<?>[0];
    }
}
