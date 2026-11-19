package org.p32h.interop.epa.pave.service.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a member-linked identifier into the token the ledger stores in its place.
 *
 * <p>The token is an HMAC-SHA-256 of the identifier under a salt that is random for each
 * installation, written as hex. Two runs for one member carry the same token, so a tester can
 * correlate them. Nobody can rebuild the token from a list of member IDs without the salt, so the
 * ledger does not become a roster. That holds only while the salt is kept apart from the ledger:
 * whoever holds both can test any ID against any line. This is why the salt file may not sit in
 * the ledger directory, and why nothing here prints or logs it.
 */
final class LedgerTokens {

    /** The name of the salt file when {@code tracking.salt-file} is blank. It sits in the config store directory. */
    static final String DEFAULT_FILE_NAME = "installation-salt";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int NEW_SALT_BYTES = 32;

    /**
     * A salt file an operator supplies is used as it is, whatever it holds, but not when it is
     * this short. A handful of bytes could be searched, and the tokens would protect nothing.
     */
    private static final int SHORTEST_SALT_BYTES = 16;

    private static final Pattern TOKEN_SHAPE = Pattern.compile("[0-9a-f]{64}");

    private final SecretKeySpec key;

    private LedgerTokens(byte[] salt) {
        this.key = new SecretKeySpec(salt, ALGORITHM);
        // Fails here, at startup, if this Java runtime cannot compute the token at all.
        token("self-check");
    }

    /** A salt held in memory only. Tokens made with it stop matching when the process ends. */
    static LedgerTokens ephemeral() {
        return new LedgerTokens(randomSalt());
    }

    /**
     * Reads the salt file, creating it with random bytes when it does not exist yet.
     *
     * @throws IOException           when the file can be neither read nor created
     * @throws IllegalStateException when the file exists and holds too little to be a salt
     */
    static LedgerTokens loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            create(file);
        }
        return load(file);
    }

    /** Reads an existing salt file. */
    static LedgerTokens load(Path file) throws IOException {
        byte[] salt = Files.readAllBytes(file);
        if (salt.length < SHORTEST_SALT_BYTES) {
            throw new IllegalStateException("The salt file " + file + " holds " + salt.length + " bytes, and a salt needs at least "
                    + SHORTEST_SALT_BYTES + ". Member tokens made with it would protect nothing. Delete the file to have a new salt "
                    + "created, knowing that tokens already in the ledger will then no longer match new runs for the same member.");
        }
        return new LedgerTokens(salt);
    }

    private static void create(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(file, options, ownerOnly())) {
            ByteBuffer buffer = ByteBuffer.wrap(randomSalt());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (FileAlreadyExistsException e) {
            // Another instance created it between the check and the write. Its salt is the one to use.
        }
    }

    /** Where the file system knows about owners, only the owner may read the salt. */
    private static FileAttribute<?>[] ownerOnly() {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))};
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[NEW_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /** The token for an identifier: 64 hex characters, the same every time for the same identifier and salt. */
    String token(String identifier) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // Every Java runtime is required to provide this algorithm, so this is a broken installation.
            throw new IllegalStateException("This Java runtime cannot compute HMAC-SHA-256, so member tokens cannot be made.");
        }
    }

    /**
     * True when a value has the shape of a token. A replay uses it to tell an identifier the
     * ledger tokenised, which cannot be sent anywhere, from one it kept.
     */
    static boolean looksLikeToken(String value) {
        return value != null && TOKEN_SHAPE.matcher(value).matches();
    }

    /** Says nothing about the salt. */
    @Override
    public String toString() {
        return "LedgerTokens[salt not shown]";
    }
}
