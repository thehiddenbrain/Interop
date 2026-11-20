package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Map;

/**
 * One line of the test run ledger.
 *
 * <p>This type has no component for a member name, date of birth, address, gender, subscriber ID
 * or any request or response JSON, and none may be added. That alone is not enough, because
 * several components are filled from what a service returned, and a service can put anything
 * there: a sentence in place of a code, a person's name in place of a vendor. So every such
 * component is checked as the record is constructed, whoever constructs it. What has the shape
 * of a code is kept. Anything else is replaced by a statement that something was there and how
 * long it was, so the fact survives and the text does not:
 * <ul>
 *   <li>{@code coverageCodes}: an entry is kept only as {@code name=code}, optionally after a
 *       subject, where the code is one unbroken token that does not read like an identifier or a
 *       date. A code of a known vocabulary is a word or a short mix of letters and digits; a member
 *       ID, a subscriber ID and a date of birth are long runs of digits or dates, and one of those
 *       where a code belongs is not kept</li>
 *   <li>{@code delegatedVendor}: only the vendors this tool knows by name are written. Any other
 *       text is shown on screen and not stored</li>
 *   <li>{@code correlation}: only the CORR keys are accepted. A questionnaire is kept only as one
 *       or more canonical addresses (http, https or urn, no query, no fragment, no user, no white
 *       space, an optional {@code |version}) none of whose path segments, urn name or version reads
 *       like an identifier or a date (an OID and a UUID excepted); every other value only as one
 *       identifier token</li>
 *   <li>{@code procedureCodes} and {@code lineOfBusiness}: a code is one token, a line of business
 *       is a short label that does not read like an identifier or a date. Which lines the tool
 *       knows is the ledger's to decide, since the record cannot see the code list</li>
 *   <li>a coverage code, a line of business or a correlation value that holds this run's own
 *       member ID is not kept, whatever its shape and however it is punctuated</li>
 *   <li>{@code note}, {@code testerRef} and {@code expected}: kept up to {@link #LONGEST_NOTE},
 *       {@link #LONGEST_TESTER_REF} and {@link #LONGEST_EXPECTED} characters. Every way in refuses
 *       a longer one with a message to the tester; this is the last line of defence behind them</li>
 * </ul>
 * A run line that lacks a run id, a timestamp, a protocol, an operation, an outcome or a result is
 * refused, so an incomplete line fails to read and is counted, and is never taken for a run.
 *
 * <p>The first group of components is always written, with null where a value does not apply. The
 * second group is written only when present. {@code memberId}, {@code dateOfService} and
 * {@code npi} are written only when the tracking configuration allows it; otherwise the ledger
 * stores {@code memberToken} and {@code memberIdLast4} so two runs for the same member can still
 * be correlated without the file becoming a roster.
 *
 * <p>Construct with {@link #builder()}. A service hands the ledger a draft holding the real member
 * ID; the ledger applies the tracking policy and returns the record as actually written.
 *
 * @param outcome          the protocol outcome code, for example NO_AUTH_REQUIRED
 * @param coverageCodes    standard codes from the response as {@code name=code} pairs. Coded values
 *                         only: an identifier such as an authorization number belongs in
 *                         {@code correlation} under {@link #CORR_AUTHORIZATION_REF}
 * @param errorType        an {@link OnyxFailureType} name, or {@link #ERROR_BLOCKED}
 * @param memberId         the member ID in its no-space form
 * @param targetSource     where the url came from: the name of the collection request that was
 *                         used, or null when it came from the fallback layer or nothing is configured
 * @param attachmentMode   for a PAS submission that carries supporting documentation, how it
 *                         travelled: fhir or x12-275
 * @param nextAction       the {@link NextAction} name the result stated as the next step. The
 *                         result on screen is gone when the browser tab is, and this line is all
 *                         the history, a reopened chain and a suite row ever see, so the answer to
 *                         "what do I do next" is kept here. A name, like the findings, so an old
 *                         line stays readable if the enum changes
 * @param correlation      identifiers that tie runs together, keyed by the CORR constants
 * @param findings         {@link FindingKind} names raised on this run
 * @param unrecordedInputs names of things this run sent that cannot be rebuilt from this line and
 *                         a fresh lookup, from the INPUT constants. Names only, never values. Empty
 *                         means the line is enough to run the same request again. Replay refuses a
 *                         record that lists any, because re-running it would send something else
 * @param caseExpected     on the last step of a chain case, what the case as a whole declared, as
 *                         its case line holds it. Never written on a run line: the ledger applies
 *                         it when the line is read, as it applies a note. Null otherwise
 * @param caseResult       on the last step of a chain case, the test result of the case as a whole,
 *                         which no single step can state: a chain that expected an answer from a
 *                         step it never reached has failed although every step it made passed.
 *                         Applied when the line is read, from the case line that names this run.
 *                         Null otherwise
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"recordType", "runId", "timestamp", "protocol", "operation", "target", "profile", "environment",
        "environmentKind", "scenarioId", "procedureCodes", "lineOfBusiness", "outcome", "coverageCodes",
        "delegatedVendor", "httpStatus", "elapsedMillis", "errorType", "expected", "result", "note", "testerRef",
        "chainId", "suiteRunId", "caseId", "replayOfRunId", "hook", "attachmentMode", "nextAction", "targetSource", "memberId",
        "memberToken", "memberIdLast4", "npi",
        "dateOfService", "correlation", "findings", "unrecordedInputs", "slow", "caseExpected", "caseResult"})
public record RunRecord(
        String recordType,
        String runId,
        String timestamp,
        Protocol protocol,
        EpaOperation operation,
        String target,
        String profile,
        String environment,
        EnvironmentKind environmentKind,
        String scenarioId,
        List<String> procedureCodes,
        String lineOfBusiness,
        String outcome,
        List<String> coverageCodes,
        String delegatedVendor,
        Integer httpStatus,
        Long elapsedMillis,
        String errorType,
        String expected,
        TestResult result,
        String note,
        String testerRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String chainId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String suiteRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String caseId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String replayOfRunId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String hook,
        @JsonInclude(JsonInclude.Include.NON_NULL) String attachmentMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) String targetSource,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String memberIdLast4,
        @JsonInclude(JsonInclude.Include.NON_NULL) String npi,
        @JsonInclude(JsonInclude.Include.NON_NULL) String dateOfService,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> correlation,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> findings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> unrecordedInputs,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) Boolean slow,
        @JsonInclude(JsonInclude.Include.NON_NULL) String caseExpected,
        @JsonInclude(JsonInclude.Include.NON_NULL) TestResult caseResult) {

    /** The record type of a run line. Other line types in the same file carry a different value. */
    public static final String TYPE_RUN = "run";

    /**
     * The record type of a case line: the result of a chain case as a whole, written after its
     * last step, naming that step's run. It records no call, so it is never counted or listed as a
     * run; the ledger applies it to the run it names when that run is read.
     */
    public static final String TYPE_CASE = "case";

    /** Error type of a run the production guardrail refused to send. */
    public static final String ERROR_BLOCKED = "BLOCKED";

    /** The identifier the tool put on a PAS claim, used to match a notification to its submission. */
    public static final String CORR_CLAIM_ID = "claimId";
    /** The id of a subscription a run created, read or changed. */
    public static final String CORR_SUBSCRIPTION_ID = "subscriptionId";
    /** An authorization number the service returned. The ledger tokenises it when it tokenises member IDs. */
    public static final String CORR_AUTHORIZATION_REF = "authorizationRef";
    /**
     * The earlier run this one follows from: the submission for an inquiry or a notification, the
     * package fetch for a later DTR step.
     */
    public static final String CORR_RUN_ID = "correlatedRunId";
    /** The run that created the subscription a notification arrived on. */
    public static final String CORR_SUBSCRIPTION_RUN_ID = "subscriptionRunId";
    /** The id of the ClaimResponse a submission or an inquiry returned, which a notification may name. */
    public static final String CORR_CLAIM_RESPONSE_ID = "claimResponseId";
    /** The canonical url of each questionnaire a run fetched or answered. Several are joined with a space. */
    public static final String CORR_QUESTIONNAIRE = "questionnaire";

    /** The request body was loaded from a collection and edited by hand. It is not kept. */
    public static final String INPUT_BODY = "body";
    /** Member details were typed or overridden by hand, so a fresh lookup would not reproduce them. */
    public static final String INPUT_MEMBER_DETAILS = "member-details";
    /** Provider details were typed or overridden by hand. */
    public static final String INPUT_PROVIDER_DETAILS = "provider-details";
    /** Placeholder values were typed on the form for this run only. */
    public static final String INPUT_RUN_VALUES = "run-values";
    /** The tester picked a specific collection request, which may not be the one a re-run would use. */
    public static final String INPUT_CHOSEN_REQUEST = "chosen-request";
    /** The run used something an earlier step produced, such as questionnaire answers, which the ledger does not hold. */
    public static final String INPUT_PRIOR_STEP = "prior-step";
    /** The line records something that arrived, such as a notification, so there is nothing to send again. */
    public static final String INPUT_INBOUND = "inbound";

    /** The correlation keys a record may carry. Anything else is a programming error, refused when the record is built. */
    private static final java.util.Set<String> CORRELATION_KEYS = java.util.Set.of(CORR_CLAIM_ID, CORR_SUBSCRIPTION_ID,
            CORR_AUTHORIZATION_REF, CORR_RUN_ID, CORR_SUBSCRIPTION_RUN_ID, CORR_CLAIM_RESPONSE_ID, CORR_QUESTIONNAIRE);

    /**
     * The longest note a run may carry. A note is a remark about a run. Much longer than this and
     * it is something else pasted in, most likely a request or a response, which this file must
     * never hold. The same limit applies at every way in: the run form, a suite row and a note
     * added later.
     */
    public static final int LONGEST_NOTE = 4000;
    /** The longest tester reference: a ticket number, a test step or a name for the run, never a paragraph. */
    public static final int LONGEST_TESTER_REF = 200;
    /**
     * The longest declared expectation. An expectation is outcome codes and {@code name=code}
     * terms, each perhaps after a subject such as a procedure code or a questionnaire address; ten
     * such terms with long addresses stay well under this.
     */
    public static final int LONGEST_EXPECTED = 1000;

    /** What a code, a line of business or an identifier is replaced by when it reads like an identifier or a date. */
    public static final String READS_LIKE_AN_IDENTIFIER = "not recorded: reads like an identifier or a date";
    /** What the ledger writes in place of a line of business it does not know. */
    public static final String LINE_NOT_KNOWN = "line of business not on the known list";

    private static final String NOTE_NOT_KEPT = "note not recorded";
    private static final String TESTER_REF_NOT_KEPT = "tester reference not recorded";
    private static final String EXPECTED_NOT_KEPT = "expectation not recorded";

    private static final String TOKEN = "[A-Za-z0-9._\\-:/|+]{1,64}";
    private static final String NAME = "[A-Za-z0-9._-]{1,40}";
    private static final String ONE_SUBJECT = "[A-Za-z0-9._\\-/\\[\\]]{1,80}";
    private static final String SUBJECT = ONE_SUBJECT + "(?:, " + ONE_SUBJECT + ")*";
    private static final java.util.regex.Pattern CODE_ENTRY =
            java.util.regex.Pattern.compile("(?:(" + SUBJECT + "): )?(" + NAME + ")=(" + TOKEN + ")");
    private static final java.util.regex.Pattern NO_ANSWER_ENTRY = java.util.regex.Pattern.compile("(" + SUBJECT + "): \\(no answer\\)");
    private static final java.util.regex.Pattern NAMED_ENTRY =
            java.util.regex.Pattern.compile("(?:(" + SUBJECT + "): )?(" + NAME + ")=(.*)", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern PROCEDURE_CODE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]{0,19}");
    private static final java.util.regex.Pattern SHORT_LABEL = java.util.regex.Pattern.compile("[A-Za-z0-9 &/.,()'+\\-]{1,60}");
    private static final java.util.regex.Pattern MEMBER_TOKEN = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final java.util.regex.Pattern LAST_FOUR = java.util.regex.Pattern.compile("\\S{1,4}");
    /**
     * An identifier under a correlation key: a claim id, a ClaimResponse id, a subscription id, an
     * authorization number or a run id. One token, so neither a sentence nor an address with a
     * query can be one.
     */
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z0-9\\-._:/|]{1,128}");
    private static final java.util.regex.Pattern SPACED_IDENTIFIER =
            java.util.regex.Pattern.compile(" *[A-Za-z0-9\\-._:/|]+(?: +[A-Za-z0-9\\-._:/|]+){0,3} *");
    /**
     * One canonical address: http or https with a host and a path, or a urn, then an optional
     * {@code |version}. No query, no fragment, no user before the host, no percent escape (which
     * could spell a space) and no white space.
     */
    private static final String CANONICAL = "(?i:https?://[A-Za-z0-9.\\-]{1,253}(?::\\d{1,5})?(?:/[A-Za-z0-9\\-._~:]*)*"
            + "|urn:[A-Za-z0-9][A-Za-z0-9\\-]{0,31}:[A-Za-z0-9\\-._:/]{1,400})(?:\\|[A-Za-z0-9.\\-_+]{1,64})?";
    /** What the DTR service writes in place of a requested questionnaire that was not an address. */
    private static final String NOT_A_URL = "\\(not a url, \\d{1,9} characters\\)";
    /** The questionnaire entry: one or more canonicals, or the DTR service's statements, joined by a space. */
    private static final java.util.regex.Pattern QUESTIONNAIRES = java.util.regex.Pattern.compile(
            "(?:" + CANONICAL + "|" + NOT_A_URL + ")(?: (?:" + CANONICAL + "|" + NOT_A_URL + ")){0,19}");
    /**
     * A run of six digits or more, or a date written in any of the usual ways. A code of the
     * vocabularies the three protocols use never holds either: they are words, X12 codes of a
     * letter and a digit or two, and short numbers. A member ID, a subscriber ID and a date of
     * birth almost always do.
     */
    private static final java.util.regex.Pattern IDENTIFIER_OR_DATE;

    static {
        String month = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?"
                + "|nov(?:ember)?|dec(?:ember)?)";
        IDENTIFIER_OR_DATE = java.util.regex.Pattern.compile("\\d{6,}|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}"
                // A date written with the name of its month: 23Jul1961, 23-July-61, Jul-23-1961, July 23, 1961.
                + "|\\d{1,2}[-/. ]?" + month + "[-/. ,]?\\d{2,4}|" + month + "[-/. ]?\\d{1,2}[-/. ,]{1,2}\\d{2,4}",
                java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    /**
     * A part of a questionnaire address that cannot be anyone's identifier or date, though it
     * holds long runs of digits: a UUID, which is hex in fixed groups, and the arcs of an OID.
     */
    private static final java.util.regex.Pattern UUID_SHAPE =
            java.util.regex.Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final java.util.regex.Pattern OID_SHAPE = java.util.regex.Pattern.compile("[0-2](?:\\.\\d{1,20}){1,40}");
    /** A member ID shorter than this is not looked for inside other values: too short to say anything. */
    private static final int SHORTEST_MEMBER_ID_SOUGHT = 6;

    public RunRecord {
        // Boxed, not primitive, because Jackson 3 refuses to bind a missing property to a primitive
        // and a line without these two must still be readable. They are normalised here so callers
        // never see a null.
        elapsedMillis = elapsedMillis == null ? Long.valueOf(0L) : elapsedMillis;
        slow = slow != null && slow;
        recordType = recordType == null ? TYPE_RUN : recordType;
        if (TYPE_RUN.equals(recordType)) {
            if (Texts.blank(runId) || Texts.blank(timestamp) || Texts.blank(outcome) || protocol == null || operation == null
                    || result == null) {
                throw new IllegalStateException("A run record needs a run id, a timestamp, a protocol, an operation, an outcome and a result.");
            }
        } else if (TYPE_CASE.equals(recordType) && (Texts.blank(runId) || Texts.blank(timestamp) || result == null)) {
            throw new IllegalStateException("A case line needs the run id of the last step, a timestamp and a result.");
        }
        // Read before anything else is checked, because a value that holds the member ID is not
        // kept, whatever its shape. Only a draft holds the ID: once the ledger has tokenised it,
        // the values it would have caught were replaced when the draft was built.
        String member = alphanumerics(memberId);
        String sought = member.length() >= SHORTEST_MEMBER_ID_SOUGHT ? member : null;
        procedureCodes = procedureCodes == null ? List.of()
                : procedureCodes.stream().map(code -> kept(PROCEDURE_CODE, code, "not a code")).toList();
        coverageCodes = coverageCodes == null ? List.of() : coverageCodes.stream().map(entry -> codeEntry(entry, sought)).toList();
        delegatedVendor = knownVendors(delegatedVendor);
        lineOfBusiness = line(lineOfBusiness, sought);
        correlation = checkedCorrelation(correlation, sought);
        note = bounded(note, LONGEST_NOTE, NOTE_NOT_KEPT);
        testerRef = bounded(testerRef, LONGEST_TESTER_REF, TESTER_REF_NOT_KEPT);
        expected = bounded(expected, LONGEST_EXPECTED, EXPECTED_NOT_KEPT);
        caseExpected = bounded(caseExpected, LONGEST_EXPECTED, EXPECTED_NOT_KEPT);
        // These two are written under every configuration, when the member ID itself is not. So
        // neither may hold anything but what the ledger puts there: a keyed hash in hex, and at
        // most four characters. Anything else is dropped, never written.
        memberToken = memberToken != null && MEMBER_TOKEN.matcher(memberToken).matches() ? memberToken : null;
        memberIdLast4 = memberIdLast4 != null && LAST_FOUR.matcher(memberIdLast4).matches() ? memberIdLast4 : null;
        findings = findings == null ? List.of() : List.copyOf(findings);
        unrecordedInputs = unrecordedInputs == null ? List.of() : List.copyOf(unrecordedInputs);
    }

    private static String kept(java.util.regex.Pattern shape, String value, String what) {
        return value != null && (shape.matcher(value).matches() || statement(what, value)) ? value
                : "(" + what + ", " + (value == null ? 0 : value.length()) + " characters)";
    }

    /**
     * True when a value already is the statement this record writes in place of text it did not
     * keep. Such a value is left as it is, so checking a record again when it is copied or read
     * gives the same record, and the number in the statement stays the length of what was there.
     */
    private static boolean statement(String what, String value) {
        return value != null && value.matches("\\(" + java.util.regex.Pattern.quote(what) + ", \\d{1,9} characters\\)");
    }

    /** True when a value reads like an identifier or a date, or holds this run's member ID. */
    private static boolean identifying(String value, String sought) {
        return value != null && (IDENTIFIER_OR_DATE.matcher(value).find() || holdsMember(value, sought));
    }

    /**
     * True when a value holds this run's member ID, however it is punctuated. A service that
     * writes S-123456789 or S12345-6789 where a code or a path belongs has written the ID, so both
     * sides are compared with everything but letters and digits removed.
     */
    private static boolean holdsMember(String value, String sought) {
        return sought != null && value != null && alphanumerics(value).contains(sought);
    }

    private static String alphanumerics(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * True when a part of a questionnaire address reads like an identifier or a date. The scheme
     * and the host are the service's address and are not looked at; every segment of the path, the
     * name inside a urn and the version are. An OID and a UUID are left alone, since their long
     * runs of digits are what they are made of.
     */
    private static boolean identifyingQuestionnaire(String questionnaires) {
        for (String canonical : questionnaires.split(" ")) {
            if (canonical.matches(NOT_A_URL)) {
                continue;
            }
            int bar = canonical.indexOf('|');
            String address = bar < 0 ? canonical : canonical.substring(0, bar);
            if (bar >= 0 && IDENTIFIER_OR_DATE.matcher(canonical.substring(bar + 1)).find()) {
                return true;
            }
            List<String> parts = new java.util.ArrayList<>();
            String lower = address.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("urn:")) {
                int colon = address.indexOf(':', 4);
                String namespace = lower.substring(4, colon);
                String name = address.substring(colon + 1);
                if (namespace.equals("oid") && OID_SHAPE.matcher(name).matches() || namespace.equals("uuid") && UUID_SHAPE.matcher(name).matches()) {
                    continue;
                }
                parts.add(name);
            } else {
                int path = address.indexOf('/', address.indexOf("//") + 2);
                if (path >= 0) {
                    parts.addAll(List.of(address.substring(path + 1).split("/")));
                }
            }
            for (String part : parts) {
                if (!UUID_SHAPE.matcher(part).matches() && IDENTIFIER_OR_DATE.matcher(part).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String notKept(String what, String value) {
        return "(" + what + ", " + (value == null ? 0 : value.length()) + " characters)";
    }

    /** Free text a tester typed, kept up to its limit and otherwise replaced by its length. */
    private static String bounded(String value, int longest, String what) {
        return value == null || value.length() <= longest || statement(what, value) ? value : notKept(what, value);
    }

    /**
     * A line of business, kept when it is a short label that names nobody. Whether it is a line
     * the tool knows is decided by the ledger, which can see the code list, and its statement is
     * left as it is.
     */
    private static String line(String value, String sought) {
        if (value == null || statement("not a line of business", value) || statement(LINE_NOT_KNOWN, value)
                || statement(READS_LIKE_AN_IDENTIFIER, value)) {
            return value;
        }
        if (!SHORT_LABEL.matcher(value).matches()) {
            return notKept("not a line of business", value);
        }
        return identifying(value, sought) ? notKept(READS_LIKE_AN_IDENTIFIER, value) : value;
    }

    /** One coverage code entry, kept when it has the shape of one, otherwise replaced by what can safely be said about it. */
    private static String codeEntry(String entry, String sought) {
        if (entry == null) {
            return "(not a code entry, 0 characters)";
        }
        java.util.regex.Matcher code = CODE_ENTRY.matcher(entry);
        if (code.matches()) {
            // The subject and the element name are the tool's own. The code came from the response.
            return !identifying(code.group(3), sought) ? entry
                    : (code.group(1) == null ? "" : code.group(1) + ": ") + code.group(2) + "=" + notKept(READS_LIKE_AN_IDENTIFIER, code.group(3));
        }
        if (NO_ANSWER_ENTRY.matcher(entry).matches() || statement("not a code entry", entry)) {
            return entry;
        }
        java.util.regex.Matcher named = NAMED_ENTRY.matcher(entry);
        if (named.matches() && (statement("not a code", named.group(3)) || statement(READS_LIKE_AN_IDENTIFIER, named.group(3)))) {
            return entry;
        }
        if (named.matches()) {
            // The element name is the tool's own. What followed it was not a code, so only its length is kept.
            return (named.group(1) == null ? "" : named.group(1) + ": ") + named.group(2) + "=(not a code, " + named.group(3).length()
                    + " characters)";
        }
        return "(not a code entry, " + entry.length() + " characters)";
    }

    /**
     * The vendor names to write: each one this tool knows, in its own spelling. A name it does not
     * know is not written. Under a vendor code a service can return any text, and the one place a
     * person's name must never land is a file that is kept.
     */
    private static String knownVendors(String vendors) {
        if (Texts.blank(vendors)) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : vendors.split(";")) {
            if (Texts.blank(part)) {
                continue;
            }
            out.add(DelegatedVendors.known(part) ? DelegatedVendors.canonical(part)
                    : statement("vendor not on the known list", part.strip()) ? part.strip()
                    : "(vendor not on the known list, " + part.strip().length() + " characters)");
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    /**
     * The correlation entries, each checked for what its key can hold. Every one but the run ids
     * can come from a response or from what a tester typed: a claim id (typed on an inquiry or in a
     * suite row, or minted by the tool), a ClaimResponse id, a subscription id and an authorization
     * number (from a response), and the questionnaire addresses (from a response, or asked for by
     * the tester). A run id is minted by the tool and carried by the browser. None of them is free
     * text, so a value of any other shape is replaced by its length.
     */
    private static Map<String, String> checkedCorrelation(Map<String, String> given, String sought) {
        if (given == null || given.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        given.forEach((key, value) -> {
            if (!CORRELATION_KEYS.contains(key)) {
                throw new IllegalArgumentException("A run record can be correlated only under its own CORR keys.");
            }
            // A blank value identifies nothing, so there is nothing to write for it.
            if (Texts.blank(value)) {
                return;
            }
            if (statement("not recorded", value)) {
                out.put(key, value);
            } else if (holdsMember(value, sought)) {
                out.put(key, notKept("not recorded", value));
            } else if (CORR_QUESTIONNAIRE.equals(key)) {
                // The shape alone lets a path through that spells a name and a date of birth, so
                // each part of the address is also held to the rule codes are held to.
                out.put(key, QUESTIONNAIRES.matcher(value).matches() && !identifyingQuestionnaire(value) ? value
                        : notKept("not recorded", value));
            } else if (CORR_AUTHORIZATION_REF.equals(key)) {
                // An authorization number can be written in up to four groups with spaces between
                // them, as a member ID can, and the ledger keeps it without them. A tab, a line
                // break or more groups than that is not one identifier.
                out.put(key, SPACED_IDENTIFIER.matcher(value).matches() && MemberIds.noSpace(value).length() <= 128
                        ? value : notKept("not recorded", value));
            } else {
                out.put(key, kept(IDENTIFIER, value, "not recorded"));
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The case line of a chain case: its test result as a whole, written once its steps have run.
     * It holds only what the tool decided and what the suite row declared, never member data.
     *
     * @param lastRunId the run id of the last step the case made, which the line is applied to
     * @param expected  what the case declared for the chain as a whole, or null
     */
    public static RunRecord caseLine(String lastRunId, String timestamp, String chainId, String suiteRunId, String caseId,
                                     String expected, TestResult result) {
        return new RunRecord(TYPE_CASE, lastRunId, timestamp, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, expected, result, null, null, chainId, suiteRunId, caseId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** True for a case line, which records the result of a chain case and no call. */
    public boolean caseLine() {
        return TYPE_CASE.equals(recordType);
    }

    /**
     * This run with the result of its case applied from the case line that names it. Only a run
     * line takes one, and only the case line's own result and expectation are taken from it.
     */
    public RunRecord withCase(RunRecord line) {
        if (line == null || !line.caseLine() || !TYPE_RUN.equals(recordType) || !runId.equals(line.runId())) {
            return this;
        }
        return toBuilder().caseExpected(line.expected()).caseResult(line.result()).build();
    }

    /**
     * Prints what identifies the run and nothing else. A draft holds the real member ID, the date
     * of service and, for PAS, a real authorization number.
     */
    @Override
    public String toString() {
        return "RunRecord[" + runId + ", " + protocol + ", " + operation + ", " + outcome + "]";
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.runId = runId;
        b.timestamp = timestamp;
        b.protocol = protocol;
        b.operation = operation;
        b.target = target;
        b.profile = profile;
        b.environment = environment;
        b.environmentKind = environmentKind;
        b.scenarioId = scenarioId;
        b.procedureCodes = procedureCodes;
        b.lineOfBusiness = lineOfBusiness;
        b.outcome = outcome;
        b.coverageCodes = coverageCodes;
        b.delegatedVendor = delegatedVendor;
        b.httpStatus = httpStatus;
        b.elapsedMillis = elapsedMillis;
        b.errorType = errorType;
        b.expected = expected;
        b.result = result;
        b.note = note;
        b.testerRef = testerRef;
        b.chainId = chainId;
        b.suiteRunId = suiteRunId;
        b.caseId = caseId;
        b.replayOfRunId = replayOfRunId;
        b.hook = hook;
        b.attachmentMode = attachmentMode;
        b.nextAction = nextAction;
        b.targetSource = targetSource;
        b.memberId = memberId;
        b.memberToken = memberToken;
        b.memberIdLast4 = memberIdLast4;
        b.npi = npi;
        b.dateOfService = dateOfService;
        b.correlation = correlation;
        b.findings = findings;
        b.unrecordedInputs = unrecordedInputs;
        b.slow = slow;
        b.caseExpected = caseExpected;
        b.caseResult = caseResult;
        return b;
    }

    public static final class Builder {
        private String runId;
        private String timestamp;
        private Protocol protocol;
        private EpaOperation operation;
        private String target;
        private String profile;
        private String environment;
        private EnvironmentKind environmentKind;
        private String scenarioId;
        private List<String> procedureCodes = List.of();
        private String lineOfBusiness;
        private String outcome;
        private List<String> coverageCodes = List.of();
        private String delegatedVendor;
        private Integer httpStatus;
        private long elapsedMillis;
        private String errorType = OnyxFailureType.NONE.name();
        private String expected;
        private TestResult result;
        private String note;
        private String testerRef;
        private String chainId;
        private String suiteRunId;
        private String caseId;
        private String replayOfRunId;
        private String hook;
        private String attachmentMode;
        private String nextAction;
        private String targetSource;
        private String memberId;
        private String memberToken;
        private String memberIdLast4;
        private String npi;
        private String dateOfService;
        private Map<String, String> correlation = Map.of();
        private List<String> findings = List.of();
        private List<String> unrecordedInputs = List.of();
        private boolean slow;
        private String caseExpected;
        private TestResult caseResult;

        private Builder() {
        }

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder timestamp(String value) {
            this.timestamp = value;
            return this;
        }

        public Builder protocol(Protocol value) {
            this.protocol = value;
            return this;
        }

        public Builder operation(EpaOperation value) {
            this.operation = value;
            return this;
        }

        public Builder target(String value) {
            this.target = value;
            return this;
        }

        public Builder profile(String value) {
            this.profile = value;
            return this;
        }

        public Builder environment(EnvironmentRef value) {
            this.environment = value == null ? null : value.name();
            this.environmentKind = value == null ? null : value.kind();
            return this;
        }

        public Builder environment(String name, EnvironmentKind kind) {
            this.environment = name;
            this.environmentKind = kind;
            return this;
        }

        public Builder scenarioId(String value) {
            this.scenarioId = value;
            return this;
        }

        public Builder procedureCodes(List<String> value) {
            this.procedureCodes = value;
            return this;
        }

        public Builder lineOfBusiness(String value) {
            this.lineOfBusiness = value;
            return this;
        }

        public Builder outcome(String value) {
            this.outcome = value;
            return this;
        }

        public Builder outcome(OutcomeCode value) {
            this.outcome = value == null ? null : value.name();
            return this;
        }

        public Builder coverageCodes(List<String> value) {
            this.coverageCodes = value;
            return this;
        }

        public Builder delegatedVendor(String value) {
            this.delegatedVendor = value;
            return this;
        }

        public Builder httpStatus(Integer value) {
            this.httpStatus = value;
            return this;
        }

        public Builder elapsedMillis(long value) {
            this.elapsedMillis = value;
            return this;
        }

        public Builder errorType(OnyxFailureType value) {
            this.errorType = value == null ? OnyxFailureType.NONE.name() : value.name();
            return this;
        }

        public Builder errorType(String value) {
            this.errorType = value;
            return this;
        }

        public Builder expected(String value) {
            this.expected = value;
            return this;
        }

        public Builder result(TestResult value) {
            this.result = value;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
            return this;
        }

        public Builder testerRef(String value) {
            this.testerRef = value;
            return this;
        }

        public Builder chainId(String value) {
            this.chainId = value;
            return this;
        }

        public Builder suiteRunId(String value) {
            this.suiteRunId = value;
            return this;
        }

        public Builder caseId(String value) {
            this.caseId = value;
            return this;
        }

        public Builder replayOfRunId(String value) {
            this.replayOfRunId = value;
            return this;
        }

        public Builder hook(String value) {
            this.hook = value;
            return this;
        }

        public Builder attachmentMode(String value) {
            this.attachmentMode = value;
            return this;
        }

        public Builder nextAction(String value) {
            this.nextAction = value;
            return this;
        }

        public Builder nextAction(NextAction value) {
            this.nextAction = value == null ? null : value.name();
            return this;
        }

        public Builder targetSource(String value) {
            this.targetSource = value;
            return this;
        }

        public Builder memberId(String value) {
            this.memberId = value;
            return this;
        }

        public Builder memberToken(String value) {
            this.memberToken = value;
            return this;
        }

        public Builder memberIdLast4(String value) {
            this.memberIdLast4 = value;
            return this;
        }

        public Builder npi(String value) {
            this.npi = value;
            return this;
        }

        public Builder dateOfService(String value) {
            this.dateOfService = value;
            return this;
        }

        public Builder correlation(Map<String, String> value) {
            this.correlation = value;
            return this;
        }

        public Builder findings(List<String> value) {
            this.findings = value;
            return this;
        }

        public Builder unrecordedInputs(List<String> value) {
            this.unrecordedInputs = value;
            return this;
        }

        public Builder slow(boolean value) {
            this.slow = value;
            return this;
        }

        public Builder caseExpected(String value) {
            this.caseExpected = value;
            return this;
        }

        public Builder caseResult(TestResult value) {
            this.caseResult = value;
            return this;
        }

        /** Builds the record, refusing one that could not be traced or compared later. */
        public RunRecord build() {
            return new RunRecord(TYPE_RUN, runId, timestamp, protocol, operation, target, profile, environment,
                    environmentKind, scenarioId, procedureCodes, lineOfBusiness, outcome, coverageCodes, delegatedVendor,
                    httpStatus, elapsedMillis, errorType, expected, result, note, testerRef, chainId, suiteRunId, caseId,
                    replayOfRunId, hook, attachmentMode, nextAction, targetSource, memberId, memberToken, memberIdLast4, npi, dateOfService, correlation,
                    findings, unrecordedInputs, slow, caseExpected, caseResult);
        }

    }
}
