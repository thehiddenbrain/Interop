package org.point32health.memberprofile.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Member ids never go to the log in clear; a short SHA-256 prefix is enough to correlate a support case. */
public final class MemberIds {

    private MemberIds() {
    }

    /** e.g. {@code m:3f9a2c1e7b04} for a member id; {@code m:-} for null. */
    public static String forLog(String memberId) {
        if (memberId == null) return "m:-";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(memberId.getBytes(StandardCharsets.UTF_8));
            return "m:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
