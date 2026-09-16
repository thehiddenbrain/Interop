package com.thehiddenbrain.interop.patientaccess.common;

import java.security.SecureRandom;

/** Short, URL-safe random identifiers (20 chars of base32) for environments, runs and history entries. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private Ids() {
    }

    public static String next() {
        return next(20);
    }

    public static String next(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
