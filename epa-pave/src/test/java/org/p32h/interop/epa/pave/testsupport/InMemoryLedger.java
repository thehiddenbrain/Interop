package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.ledger.RunIds;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A ledger that keeps records in a list, for tests of the services that write to one.
 *
 * <p>It applies no tracking policy: a draft is stored exactly as given, so a test can assert on
 * what a service handed to the ledger. The policy itself is tested against the real ledger. One
 * part of it can be switched on, {@link #tokenisingAuthorizationRefs()}, because a caller that
 * compared a stored authorization number as text would pass every test against a ledger that
 * keeps the number as written and fail against the real one, which may hold a token.
 *
 * <p>A query applies every filter of {@link RunQuery}, as the real ledger does. A fake that
 * returned everything would let a caller that queries under the wrong key pass its tests. In the
 * same way a note, a match and a missing run behave as they do on the real ledger.
 */
public final class InMemoryLedger implements RunLedger {

    /**
     * What the record itself writes in place of a correlation value it refused. The real ledger
     * leaves it as it is and does not tokenise it, and so does this one.
     */
    private static final Pattern NOT_RECORDED = Pattern.compile("\\(not recorded, \\d+ characters\\)");

    /** The key the tokens of this fake are made with. Fixed, so a test gets the same token on every run. */
    private static final SecretKeySpec TOKEN_KEY = new SecretKeySpec("in-memory-ledger-test-salt".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final List<RunRecord> records = new ArrayList<>();
    private String refusal;
    private RuntimeException failure;
    private RuntimeException readFailure;
    private boolean tokenising;

    /** Makes every later write answer "not recorded" with this message, as a full disk would. */
    public InMemoryLedger refusing(String message) {
        this.refusal = message;
        return this;
    }

    /** Makes every later write throw, which a real ledger must never do and a service must survive. */
    public InMemoryLedger throwing(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /**
     * Makes every later question throw: a query, and a lookup of one run by its id. Writing still
     * works, and so do {@link #records()}, {@link #only()} and {@link #last()}, which are for the
     * test and are not questions a caller can ask. A caller that asks the ledger something must
     * survive a ledger that cannot answer, and must not take "could not be asked" for "nothing
     * was found". Null lets it answer again.
     */
    public InMemoryLedger throwingOnRead(RuntimeException exception) {
        this.readFailure = exception;
        return this;
    }

    /**
     * Stores the authorization number of every later run as a token, as the real ledger does when
     * member IDs are not recorded: the number with its white space removed, put through a keyed
     * hash, 64 hexadecimal characters. Nothing else of a draft is changed. A stored value then
     * equals the number only through {@link #matches}, which is how a caller has to compare.
     */
    public InMemoryLedger tokenisingAuthorizationRefs() {
        this.tokenising = true;
        return this;
    }

    public List<RunRecord> records() {
        return records;
    }

    public RunRecord only() {
        if (records.size() != 1) {
            throw new AssertionError("Expected exactly one ledger record but there are " + records.size());
        }
        return records.get(0);
    }

    public RunRecord last() {
        return records.get(records.size() - 1);
    }

    @Override
    public LedgerReceipt record(RunRecord draft) {
        if (failure != null) {
            throw failure;
        }
        RunRecord record = tokenising ? withTokenisedAuthorizationRef(draft) : draft;
        if (refusal != null) {
            // As the real ledger does: the record is returned whether or not it was kept.
            return new LedgerReceipt(false, record, refusal, records.size());
        }
        records.add(record);
        return new LedgerReceipt(true, record, null, records.size());
    }

    /** The draft with its authorization number in the form this ledger stores. Every other part is the draft's own. */
    private RunRecord withTokenisedAuthorizationRef(RunRecord draft) {
        String authorization = draft.correlation().get(RunRecord.CORR_AUTHORIZATION_REF);
        if (authorization == null || NOT_RECORDED.matcher(authorization).matches()) {
            return draft;
        }
        Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
        String stored = tokenFor(authorization);
        if (stored == null) {
            correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
        } else {
            correlation.put(RunRecord.CORR_AUTHORIZATION_REF, stored);
        }
        return draft.toBuilder().correlation(correlation).build();
    }

    @Override
    public List<RunRecord> query(RunQuery query) {
        if (readFailure != null) {
            throw readFailure;
        }
        List<RunRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            RunRecord record = records.get(i);
            if (accepts(query, record)) {
                out.add(record);
            }
        }
        int limit = query == null || query.limit() == null ? 0 : query.limit();
        return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private boolean accepts(RunQuery query, RunRecord record) {
        if (query == null) {
            return true;
        }
        LocalDate day = record.timestamp() == null || record.timestamp().length() < 10 ? null
                : LocalDate.parse(record.timestamp().substring(0, 10));
        if (query.from() != null && (day == null || day.isBefore(query.from()))) {
            return false;
        }
        if (query.to() != null && (day == null || day.isAfter(query.to()))) {
            return false;
        }
        if (query.protocol() != null && query.protocol() != record.protocol()) {
            return false;
        }
        if (query.operation() != null && query.operation() != record.operation()) {
            return false;
        }
        if (query.result() != null && query.result() != record.result()) {
            return false;
        }
        // An outcome typed into a filter is matched whatever its case, as the real ledger matches it.
        if (query.outcome() != null && !query.outcome().strip().equalsIgnoreCase(record.outcome())) {
            return false;
        }
        if (query.chainId() != null && !query.chainId().equals(record.chainId())) {
            return false;
        }
        if (query.suiteRunId() != null && !query.suiteRunId().equals(record.suiteRunId())) {
            return false;
        }
        if (query.correlationKey() != null) {
            String stored = record.correlation().get(query.correlationKey());
            return stored != null && matches(stored, query.correlationValue());
        }
        return true;
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        if (readFailure != null) {
            throw readFailure;
        }
        return records.stream().filter(r -> r.runId().equals(runId)).findFirst();
    }

    /**
     * As the real ledger: a value that is left out leaves what the run has, a blank one clears it,
     * a run the ledger does not hold is not found, and a call that gives neither is refused.
     */
    @Override
    public LedgerReceipt addNote(String runId, String note, String testerRef) {
        RunRecord record = find(runId).orElseThrow(() -> RunIds.notFound(runId));
        if (note == null && testerRef == null) {
            throw ValidationException.of("note", "Give a note, a tester reference or both. Nothing was changed.");
        }
        RunRecord noted = record.toBuilder().note(note == null ? record.note() : Texts.trimToNull(note))
                .testerRef(testerRef == null ? record.testerRef() : Texts.trimToNull(testerRef)).build();
        records.set(records.indexOf(record), noted);
        return new LedgerReceipt(true, noted, null, records.size());
    }

    @Override
    public LedgerHealth health() {
        return new LedgerHealth(true, "memory", true, "memory", records.size(), 0, null, refusal, null, refusal == null ? 0 : 1);
    }

    /**
     * The form this ledger stores a member-linked identifier in: its token when authorization
     * numbers are tokenised, otherwise the identifier with its white space removed. Null when
     * there is nothing to store.
     */
    @Override
    public String tokenFor(String identifier) {
        String id = MemberIds.noSpace(identifier);
        if (id.isEmpty()) {
            return null;
        }
        return tokenising ? token(id) : id;
    }

    /**
     * True when a stored value is the identifier in either form a ledger can have written: the
     * identifier with its white space removed, or its token. Two blanks never match, because an
     * identifier that is not there identifies nothing.
     */
    @Override
    public boolean matches(String stored, String identifier) {
        String kept = MemberIds.noSpace(stored);
        String id = MemberIds.noSpace(identifier);
        if (kept.isEmpty() || id.isEmpty()) {
            return false;
        }
        return kept.equals(id) || kept.equals(token(id));
    }

    private static String token(String identifier) {
        try {
            Mac mac = Mac.getInstance(TOKEN_KEY.getAlgorithm());
            mac.init(TOKEN_KEY);
            return HexFormat.of().formatHex(mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime cannot compute HMAC-SHA-256.");
        }
    }
}
