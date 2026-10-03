package com.thehiddenbrain.interop.memberid.domain;

import com.thehiddenbrain.interop.memberid.api.ErrorDetail;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises the member id exactly as the EMR supplied it into one of three shapes. It never invents a
 * suffix, never strips digits and never guesses: anything that is not 9 digits, 11 digits or HP+digits
 * after separators are removed is rejected before MMI is called.
 *
 * <p>Separators (any whitespace, any dash, {@code _ . /}) are tolerated anywhere: {@code 123456789   01},
 * {@code 123-456-789 01}, {@code HP-123456789} and {@code hp 123456789} are all accepted. Removing a
 * separator cannot change a digit, and MMI verifies the result.
 */
public final class MemberIdParser {

    public static final int MAX_LENGTH = 40;
    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");
    private static final Pattern ELEVEN_DIGITS = Pattern.compile("^\\d{11}$");

    private final Pattern hphc;

    public MemberIdParser(List<Integer> hphcDigitLengths) {
        StringBuilder alternation = new StringBuilder();
        for (Integer n : hphcDigitLengths) {
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append("\\d{").append(n).append('}');
        }
        this.hphc = Pattern.compile("^HP(?:" + alternation + ")$");
    }

    public ParsedMemberId parse(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            throw reject("MEMBER_ID_MISSING", "memberId is required");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw reject("MEMBER_ID_TOO_LONG", "memberId longer than " + MAX_LENGTH + " characters");
        }
        StringBuilder compact = new StringBuilder(trimmed.length());
        StringBuilder illegal = new StringBuilder();
        trimmed.codePoints().forEach(cp -> {
            if (cp >= '0' && cp <= '9') {
                compact.appendCodePoint(cp);
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                compact.append(Character.toUpperCase((char) cp));
            } else if (isSeparator(cp)) {
                // dropped
            } else {
                if (illegal.length() > 0) {
                    illegal.append(", ");
                }
                illegal.append(String.format(Locale.ROOT, "U+%04X", cp));
            }
        });
        if (illegal.length() > 0) {
            throw reject("MEMBER_ID_ILLEGAL_CHARACTERS", "memberId contains characters that are not digits, letters or separators: " + illegal);
        }
        String key = compact.toString();
        if (NINE_DIGITS.matcher(key).matches()) {
            return new ParsedMemberId(raw, key, InputShape.THP_9);
        }
        if (ELEVEN_DIGITS.matcher(key).matches()) {
            return new ParsedMemberId(raw, key, InputShape.ID_11);
        }
        if (hphc.matcher(key).matches()) {
            return new ParsedMemberId(raw, key, InputShape.HPHC);
        }
        throw reject("MEMBER_ID_UNRECOGNIZED_SHAPE",
                describeShape(key) + " is not a THP member id (9 or 11 digits) or an HPHC member id (HP + digits)");
    }

    private static boolean isSeparator(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        if (Character.getType(cp) == Character.DASH_PUNCTUATION) {
            return true;
        }
        return cp == '_' || cp == '.' || cp == '/';
    }

    /** Describes the shape without revealing the id (it may be PHI). */
    private static String describeShape(String key) {
        boolean allDigits = key.chars().allMatch(Character::isDigit);
        if (allDigits) {
            return key.length() + " digits";
        }
        return key.length() + " characters";
    }

    private static InvalidRequestException reject(String code, String message) {
        return new InvalidRequestException(new ErrorDetail("memberId", code, message));
    }
}
