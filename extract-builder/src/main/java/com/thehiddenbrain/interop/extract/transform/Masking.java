package com.thehiddenbrain.interop.extract.transform;

import com.thehiddenbrain.interop.extract.store.Ids;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * De-identification that keeps the shape of the value. Sample files are masked with these so the vendor still
 * validates real widths, character classes and date formats. Every mask is deterministic for a given salt,
 * so the same member masks the same way across runs and joins still line up inside one file.
 */
public final class Masking {

    private Masking() {}

    /** Replace letters with letters of the same case and digits with digits, keeping everything else. */
    public static String formatPreserving(String s, String salt) {
        if (s == null || s.isEmpty()) return s;
        String hash = Ids.sha256(salt + "|" + s);
        StringBuilder out = new StringBuilder(s.length());
        int hi = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int h = Character.digit(hash.charAt(hi % hash.length()), 16);
            hi++;
            if (Character.isDigit(c)) out.append((char) ('0' + (h % 10)));
            else if (c >= 'a' && c <= 'z') out.append((char) ('a' + ((h * 7 + i) % 26)));
            else if (c >= 'A' && c <= 'Z') out.append((char) ('A' + ((h * 7 + i) % 26)));
            else if (Character.isLetter(c)) out.append((char) ('A' + ((h * 7 + i) % 26)));
            else out.append(c);
        }
        return out.toString();
    }

    private static final String[] WORDS = {
            "Ada", "Bea", "Cal", "Dee", "Eli", "Fay", "Gus", "Ida", "Jon", "Kay", "Lee", "Mae", "Ned", "Ora", "Pat", "Rae", "Sal", "Tim", "Una", "Val",
            "Anna", "Beck", "Cole", "Dale", "Emma", "Finn", "Gail", "Hugh", "Iris", "Jude", "Kane", "Lena", "Mara", "Noel", "Opal", "Paul", "Reid", "Sara", "Tess", "Vera",
            "Alder", "Blake", "Clara", "Dixon", "Ellis", "Flynn", "Grace", "Hayes", "Irene", "Jonas", "Keane", "Lowry", "Mason", "Nolan", "Olsen", "Perry", "Quinn", "Rowan", "Stone", "Tobin",
            "Archer", "Barlow", "Carver", "Dalton", "Easton", "Farley", "Garner", "Hollis", "Ingram", "Jarvis", "Keller", "Landon", "Mercer", "Norris", "Oakley", "Palmer", "Rhodes", "Sutton", "Tanner", "Weston",
            "Ashford", "Bennett", "Carlson", "Delaney", "Everett", "Fischer", "Griffin", "Harding", "Ireland", "Jamison", "Kendall", "Lindsey", "Marlowe", "Newport", "Osborne", "Preston", "Rutland", "Sherman", "Thatcher", "Whitney",
            "Anderson", "Bradford", "Chandler", "Donovan", "Ellison", "Fletcher", "Garrison", "Harrison", "Iverson", "Jennings", "Kimberly", "Lawrence", "Mitchell", "Nicholas", "Pemberton", "Radcliff", "Sheridan", "Thornton", "Whitaker", "Winslow",
            "Abernathy", "Blackwood", "Carrington", "Davenport", "Ellsworth", "Fairbanks", "Gallagher", "Hawthorne", "Kensington", "Lockhart", "Middleton", "Northcott", "Pendleton", "Rosenberg", "Stanfield", "Templeton", "Underwood", "Wakefield", "Yardley", "Zimmerman"
    };

    /**
     * Replace each alphabetic word with a plausible pseudonym of about the same length, chosen deterministically, and
     * digits with hashed digits. A masked name still reads like a name, so a vendor validating a sample sees a real
     * layout with real widths and nothing about the real member.
     */
    public static String keepFirst(String s, String salt) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                String word = s.substring(i, j);
                out.append(pseudonym(word, salt));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                out.append(formatPreserving(s.substring(i, j), salt));
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String pseudonym(String word, String salt) {
        if (word.length() < 2) return formatPreserving(word, salt);
        String hash = Ids.sha256(salt + "|" + word.toLowerCase());
        int len = Math.min(10, Math.max(3, word.length()));
        java.util.List<String> bucket = new java.util.ArrayList<>();
        for (String w : WORDS) if (w.length() == len) bucket.add(w);
        if (bucket.isEmpty()) return formatPreserving(word, salt);
        String pick = bucket.get((int) (Long.parseLong(hash.substring(0, 8), 16) % bucket.size()));
        if (word.equals(word.toUpperCase())) return pick.toUpperCase();
        if (word.equals(word.toLowerCase())) return pick.toLowerCase();
        return pick;
    }

    public static String lastN(String s, int keep, char maskChar) {
        if (s == null) return null;
        int n = Math.max(0, s.length() - keep);
        return maskChar == 0 ? s.substring(n) : String.valueOf(maskChar).repeat(n) + s.substring(n);
    }

    public static String firstN(String s, int keep, char maskChar) {
        if (s == null) return null;
        int n = Math.min(keep, s.length());
        return s.substring(0, n) + String.valueOf(maskChar).repeat(s.length() - n);
    }

    public static String fixed(String s, char maskChar) {
        if (s == null) return null;
        return String.valueOf(maskChar).repeat(s.length());
    }

    public static String hash(String s, String salt, int length) {
        if (s == null) return null;
        String h = Ids.sha256(salt + "|" + s);
        return length > 0 ? h.substring(0, Math.min(length, h.length())) : h;
    }

    /** Shift a date by a deterministic number of days within plus or minus {@code maxDays}. */
    public static LocalDate shiftDate(LocalDate d, String salt, int maxDays) {
        if (d == null) return null;
        String h = Ids.sha256(salt + "|" + d);
        int span = Math.max(1, maxDays * 2 + 1);
        int shift = (int) (Long.parseLong(h.substring(0, 8), 16) % span) - maxDays;
        if (shift == 0) shift = 1;
        return d.plusDays(shift);
    }

    public static LocalDateTime shiftTimestamp(LocalDateTime t, String salt, int maxDays) {
        if (t == null) return null;
        return shiftDate(t.toLocalDate(), salt, maxDays).atTime(t.toLocalTime());
    }

    /** Perturb a decimal by a deterministic factor between 0.8 and 1.2, keeping the scale. */
    public static BigDecimal perturb(BigDecimal b, String salt) {
        if (b == null) return null;
        String h = Ids.sha256(salt + "|" + b.toPlainString());
        double factor = 0.8 + (Long.parseLong(h.substring(0, 6), 16) % 400) / 1000.0;
        return b.multiply(BigDecimal.valueOf(factor)).setScale(Math.max(b.scale(), 0), java.math.RoundingMode.HALF_UP);
    }

    /** The policy overlay for PHI inputs in a masked run: keep the value's type and shape. */
    public static Object overlay(Object v, String salt) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return shiftDate(d, salt, 180);
        if (v instanceof LocalDateTime t) return shiftTimestamp(t, salt, 180);
        if (v instanceof BigDecimal b) return perturb(b, salt);
        if (v instanceof Number n) return perturb(new BigDecimal(n.toString()), salt);
        if (v instanceof Boolean) return v;
        return keepFirst(v.toString(), salt);
    }
}
