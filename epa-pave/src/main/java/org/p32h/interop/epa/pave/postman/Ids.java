package org.p32h.interop.epa.pave.postman;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The ids this tool gives to what is uploaded.
 *
 * <p>Every id is derived from the content it names and from nothing else: no counter, no clock, no
 * random number. Uploading the same file again therefore gives the same ids, which is what lets an
 * operator's mapping correction survive a re-upload and a restart. An id is also safe to use as a
 * file name and as one segment of a url path, which a name typed by a vendor is not.
 */
public final class Ids {

    private Ids() {
    }

    /** The first characters of the SHA-256 of some text, as lower case hex. */
    public static String hash(String text, int hexCharacters) {
        return hash((text == null ? "" : text).getBytes(StandardCharsets.UTF_8), hexCharacters);
    }

    public static String hash(byte[] bytes, int hexCharacters) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes));
            return hex.substring(0, Math.min(hexCharacters, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256, so this cannot happen in practice.
            throw new IllegalStateException("This Java runtime has no SHA-256.", e);
        }
    }

    /**
     * An id for something that is identified by its name: readable at the front, exact at the back.
     * The readable part alone would give "Onyx Sandbox" and "onyx-sandbox" the same id; the hash of
     * the exact name keeps them apart.
     */
    public static String named(String name) {
        String exact = name == null ? "" : name.strip();
        String readable = exact.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (readable.length() > 40) {
            readable = readable.substring(0, 40).replaceAll("-+$", "");
        }
        return (readable.isEmpty() ? "unnamed" : readable) + "-" + hash(exact, 8);
    }
}
