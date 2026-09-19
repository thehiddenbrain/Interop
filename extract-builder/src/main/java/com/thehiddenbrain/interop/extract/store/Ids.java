package com.thehiddenbrain.interop.extract.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class Ids {

    private static final AtomicInteger RUN_SEQ = new AtomicInteger((int) (System.currentTimeMillis() / 1000 % 100000));

    private Ids() {}

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String shortId(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime() % 100_000_000L, 36) + Integer.toString(RUN_SEQ.incrementAndGet() % 1000, 36);
    }

    public static String slug(String s) {
        String slug = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "definition" : slug;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
