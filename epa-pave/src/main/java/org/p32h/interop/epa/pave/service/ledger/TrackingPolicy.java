package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the tracking configuration lets a ledger line hold.
 *
 * <p>The ledger applies this to every draft it is given, so no caller can get it wrong. It rewrites
 * exactly the parts of a draft that {@code RunLedger.record} lists and nothing else: the member ID,
 * the date of service, the NPI, and among the correlation entries the authorization number and the
 * identifiers a service, a notification or a tester supplies (a claim id, a ClaimResponse id, a
 * subscription id). It also
 * decides the one thing the record cannot decide for itself: whether the line of business is one
 * the tool knows, which needs the code list.
 *
 * <p>On the mock profile member IDs are recorded because every ID there is synthetic. That holds for
 * the IDs the tool itself provides, its {@code P32H-} sentinels and the {@code S123456789} style of
 * its fixtures, and for nothing a tester types. Any other ID typed on mock is tokenised exactly as
 * on live, so a real ID typed into the default checkout does not land in the file in the clear.
 *
 * <p>A member-linked identifier has two stored forms. When member IDs are recorded it is stored
 * with its whitespace removed. Otherwise it is stored as a token. The policy can change between
 * one start of the tool and the next while the lines already written stay as they are, so
 * {@link #matches} accepts either form.
 */
final class TrackingPolicy {

    /**
     * What the record itself writes in place of a correlation value it refused. It holds a length
     * and nothing else, so it is left as it is: tokenising it would replace the one honest
     * statement on the line, that something was there and was not kept, with a token that looks
     * like an identifier and matches nothing.
     */
    private static final Pattern NOT_RECORDED = Pattern.compile("\\(not recorded, \\d+ characters\\)");

    /**
     * A statement the record or this policy wrote in place of a line of business. It is not a line
     * and is left as it is. Only the exact statements are, never anything shaped like one: lower
     * case words in brackets would otherwise be a way past the known list.
     */
    private static final Pattern STATEMENT = Pattern.compile("\\((?:" + Pattern.quote(RunRecord.LINE_NOT_KNOWN) + "|"
            + Pattern.quote(RunRecord.READS_LIKE_AN_IDENTIFIER) + "|not a line of business), \\d{1,9} characters\\)");

    /**
     * The member IDs that are synthetic by construction: the tool's sentinels and the placeholder
     * style its fixtures use. The same two shapes the lookup fixtures are held to. The sentinel is
     * read possessively, so that java.util.regex reads its parts in a loop and not by one call per part.
     */
    private static final Pattern SENTINEL = Pattern.compile("P32H-[A-Z0-9]++(?:-[A-Z0-9]++)*+");
    private static final Pattern PLACEHOLDER = Pattern.compile("S\\d{9,10}");

    private final boolean recordMemberId;
    private final boolean placeholdersOnly;
    private final boolean recordNpi;
    private final Supplier<LedgerTokens> tokens;
    private final UnaryOperator<String> knownLine;
    private final java.util.function.Predicate<String> listedCode;

    /**
     * @param recordMemberId   {@code tracking.record-member-id}
     * @param placeholdersOnly true on the mock profile: an ID is kept in the clear only when it is a
     *                         sentinel or a placeholder, whatever {@code recordMemberId} says
     * @param knownLine        for a line of business the tool knows (one the code list names, directly
     *                         or through {@code codelist.line-of-business-aliases}) the spelling to
     *                         write, and null for any other text
     * @param listedCode       true for a procedure code the loaded code list holds, asked only when a
     *                         line is written ({@link RunRecord#withCodesJudgedAtWriting})
     */
    TrackingPolicy(boolean recordMemberId, boolean placeholdersOnly, boolean recordNpi, Supplier<LedgerTokens> tokens,
                   UnaryOperator<String> knownLine, java.util.function.Predicate<String> listedCode) {
        this.recordMemberId = recordMemberId;
        this.placeholdersOnly = placeholdersOnly;
        this.recordNpi = recordNpi;
        this.tokens = tokens;
        this.knownLine = knownLine;
        this.listedCode = listedCode;
    }

    /** True when an ID in its no-space form is one the tool provides, never one a person holds. */
    static boolean synthetic(String memberId) {
        return SENTINEL.matcher(memberId).matches() || PLACEHOLDER.matcher(memberId).matches();
    }

    /** The draft as it may be written. */
    RunRecord apply(RunRecord draft) {
        RunRecord.Builder out = draft.toBuilder();
        // Whitespace goes first, so a spaced and an unspaced spelling of one ID give the same
        // stored value, the same token and the same last four characters.
        String memberId = MemberIds.noSpace(draft.memberId());
        boolean inTheClear = inTheClear(memberId);
        if (inTheClear) {
            out.memberId(memberId.isEmpty() ? null : memberId);
        } else {
            out.memberId(null).dateOfService(null);
            if (!memberId.isEmpty()) {
                out.memberToken(tokens.get().token(memberId)).memberIdLast4(lastFour(memberId));
            }
        }
        if (!recordNpi) {
            out.npi(null);
        }
        String line = draft.lineOfBusiness();
        if (line != null && !STATEMENT.matcher(line).matches()) {
            // Only a line the tool knows is written. A name in the place of one cannot be told from
            // a line by its shape, so anything else is written as its length and shown on screen.
            String known = knownLine.apply(line);
            out.lineOfBusiness(known != null ? known : "(" + RunRecord.LINE_NOT_KNOWN + ", " + line.length() + " characters)");
        }
        String authorization = draft.correlation().get(RunRecord.CORR_AUTHORIZATION_REF);
        if (authorization != null && !NOT_RECORDED.matcher(authorization).matches()) {
            // An authorization number identifies one member's request, so it follows the member ID.
            Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
            String stored = stored(authorization, inTheClear);
            if (stored == null) {
                correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
            } else {
                correlation.put(RunRecord.CORR_AUTHORIZATION_REF, stored);
            }
            out.correlation(correlation);
        }
        return settled(withSuppliedIds(out.build(), inTheClear).withCodesJudgedAtWriting(listedCode));
    }

    /**
     * The record with each identifier a service, a notification or a tester supplied written as
     * this policy writes the authorization number: in clear only when member IDs are, or when it
     * cannot be anybody's data ({@link RunRecord#nobodysData}), and otherwise as a token. Its shape
     * cannot tell a sequence number from a member ID with a separator in it, and a token is matched
     * as well as the identifier is, so a notification still finds the run that created its
     * subscription.
     */
    private RunRecord withSuppliedIds(RunRecord record, boolean inTheClear) {
        Map<String, String> correlation = new LinkedHashMap<>(record.correlation());
        boolean changed = false;
        for (String key : RunRecord.SUPPLIED_ID_KEYS) {
            String value = correlation.get(key);
            if (value == null || inTheClear || NOT_RECORDED.matcher(value).matches() || RunRecord.token(value)
                    || RunRecord.nobodysData(value)) {
                continue;
            }
            String stored = stored(value, false);
            if (stored == null) {
                correlation.remove(key);
            } else {
                correlation.put(key, stored);
            }
            changed = true;
        }
        return changed ? record.toBuilder().correlation(correlation).build() : record;
    }

    /**
     * A line as it is read: an identifier a line written by an earlier version of this tool kept in
     * clear, and that this policy would now write as a token, is shown as its token. The file is
     * never rewritten, and a token matches whatever the identifier matched.
     */
    RunRecord asRead(RunRecord line) {
        if (line.caseLine() || line.correlation().isEmpty()) {
            return line;
        }
        boolean inTheClear = Texts.present(line.memberId()) || Texts.blank(line.memberToken()) && recordMemberId;
        return withSuppliedIds(line, inTheClear);
    }

    /**
     * The draft with everything this policy governs removed, for the one case in which the policy
     * could not be applied. It is right under any configuration: the real member ID must not ride
     * along in a receipt because something failed.
     */
    static RunRecord withoutMemberData(RunRecord draft) {
        Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
        correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
        for (String key : RunRecord.SUPPLIED_ID_KEYS) {
            if (correlation.containsKey(key) && !RunRecord.nobodysData(correlation.get(key))) {
                correlation.remove(key);
            }
        }
        return settled(draft.toBuilder().memberId(null).memberToken(null).memberIdLast4(null).npi(null).dateOfService(null)
                .correlation(correlation).build());
    }

    /**
     * The record as it will read back from a ledger line.
     *
     * <p>The record type replaces text it will not keep, such as a sentence where a code belongs,
     * with a statement that something was there and how long it was. It runs the same check again
     * every time a record is copied or read, and its own statement is not a code either, so the
     * number in it changes on the next copy and settles after one or two more. Left alone, a line
     * could read back differently from the record the receipt carried, and a replay would report
     * that as a change in the answer. Copying here until nothing changes makes what is written the
     * same as what is read.
     *
     * <p>The cost is the number. Once settled it is the length of the statement, not of the text
     * that was there, so nothing in this package reads it as a length. It would be lost at the
     * first read whatever was written, because reading runs the same check. When the record type
     * leaves its own statements alone, this does nothing and the number is a length again.
     */
    static RunRecord settled(RunRecord record) {
        RunRecord current = record;
        for (int copies = 0; copies < 4; copies++) {
            RunRecord copy = current.toBuilder().build();
            if (copy.equals(current)) {
                return current;
            }
            current = copy;
        }
        return current;
    }

    /**
     * True when a member ID, in its no-space form, is written as it is. With no member ID on the
     * draft only the setting decides, and an authorization number on such a line follows it.
     */
    private boolean inTheClear(String memberId) {
        return recordMemberId && (!placeholdersOnly || memberId.isEmpty() || synthetic(memberId));
    }

    /** The form written for a member-linked identifier under this policy, or null when there is nothing to write. */
    String stored(String identifier) {
        return stored(identifier, recordMemberId);
    }

    private String stored(String identifier, boolean inTheClear) {
        String id = MemberIds.noSpace(identifier);
        if (id.isEmpty()) {
            return null;
        }
        return inTheClear ? id : tokens.get().token(id);
    }

    /**
     * True when a stored value is the identifier in either form a ledger can have written. Two
     * blanks never match: an identifier that is not there identifies nothing.
     */
    boolean matches(String stored, String identifier) {
        String kept = MemberIds.noSpace(stored);
        String id = MemberIds.noSpace(identifier);
        if (kept.isEmpty() || id.isEmpty()) {
            return false;
        }
        return kept.equals(id) || kept.equals(tokens.get().token(id));
    }

    /**
     * The last four characters, or null when the ID has four or fewer: the "last four" of a short
     * ID would be the whole ID, written in a field that is kept under every configuration.
     */
    private static String lastFour(String id) {
        int length = id.codePointCount(0, id.length());
        return length > 4 ? id.substring(id.offsetByCodePoints(id.length(), -4)) : null;
    }
}
