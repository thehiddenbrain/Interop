package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.RunRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the tracking configuration lets a ledger line hold.
 *
 * <p>The ledger applies this to every draft it is given, so no caller can get it wrong. It rewrites
 * exactly the parts of a draft that {@code RunLedger.record} lists and nothing else: the member ID,
 * the date of service, the NPI and the authorization number among the correlation entries.
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

    private final boolean recordMemberId;
    private final boolean recordNpi;
    private final Supplier<LedgerTokens> tokens;

    TrackingPolicy(boolean recordMemberId, boolean recordNpi, Supplier<LedgerTokens> tokens) {
        this.recordMemberId = recordMemberId;
        this.recordNpi = recordNpi;
        this.tokens = tokens;
    }

    /** The draft as it may be written. */
    RunRecord apply(RunRecord draft) {
        RunRecord.Builder out = draft.toBuilder();
        // Whitespace goes first, so a spaced and an unspaced spelling of one ID give the same
        // stored value, the same token and the same last four characters.
        String memberId = MemberIds.noSpace(draft.memberId());
        if (recordMemberId) {
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
        String authorization = draft.correlation().get(RunRecord.CORR_AUTHORIZATION_REF);
        if (authorization != null && !NOT_RECORDED.matcher(authorization).matches()) {
            // An authorization number identifies one member's request, so it follows the member ID.
            Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
            String stored = stored(authorization);
            if (stored == null) {
                correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
            } else {
                correlation.put(RunRecord.CORR_AUTHORIZATION_REF, stored);
            }
            out.correlation(correlation);
        }
        return settled(out.build());
    }

    /**
     * The draft with everything this policy governs removed, for the one case in which the policy
     * could not be applied. It is right under any configuration: the real member ID must not ride
     * along in a receipt because something failed.
     */
    static RunRecord withoutMemberData(RunRecord draft) {
        Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
        correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
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
     * same as what is read. Nothing is lost by it: the text was never kept, and once the record
     * type leaves its own statements alone this does nothing.
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

    /** The form written for a member-linked identifier under this policy, or null when there is nothing to write. */
    String stored(String identifier) {
        String id = MemberIds.noSpace(identifier);
        if (id.isEmpty()) {
            return null;
        }
        return recordMemberId ? id : tokens.get().token(id);
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
