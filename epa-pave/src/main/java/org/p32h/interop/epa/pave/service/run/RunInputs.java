package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The form checks every protocol shares. A run that fails one is refused with a 422 naming each
 * field, before anything is sent and before anything is recorded.
 *
 * <p>Each check appends to a list, so a tester sees every problem with the form at once and not
 * one per attempt.
 */
public final class RunInputs {

    /**
     * What an expectation term is about: one word, or several joined by a comma and a space, as the
     * ledger writes them. The one subject with a space is how PAS names an item it cannot tie to a
     * code: "item" and its sequence number.
     */
    private static final Pattern SUBJECT = Pattern.compile("(?:item \\d{1,4}|[^\\s,]+)(?:, ?(?:item \\d{1,4}|[^\\s,]+))*");
    private static final Pattern CODE = Pattern.compile("\\S+");

    private final List<FieldError> errors = new ArrayList<>();
    private final Clock clock;

    public RunInputs(Clock clock) {
        this.clock = clock;
    }

    /** A member ID is required and must not be blank once whitespace is removed. */
    public RunInputs member(MemberDetail member) {
        if (member == null || MemberIds.noSpace(member.memberId()).isEmpty()) {
            errors.add(new FieldError("member.memberId", "Enter a member ID."));
            return this;
        }
        if (Texts.present(member.dateOfBirth())) {
            LocalDate dateOfBirth = IsoDates.parse(member.dateOfBirth());
            if (dateOfBirth == null) {
                errors.add(new FieldError("member.dateOfBirth", "Enter the date of birth as YYYY-MM-DD, for example 1970-01-31."));
            } else if (dateOfBirth.isAfter(LocalDate.now(clock))) {
                errors.add(new FieldError("member.dateOfBirth", "The date of birth is in the future."));
            }
        }
        return this;
    }

    /** An NPI is required and its check digit must be right. */
    public RunInputs provider(ProviderDetail provider) {
        // The provider lookup says the same sentence for the same mistake.
        String problem = Npi.problem(provider == null ? null : provider.npi());
        if (problem != null) {
            errors.add(new FieldError("provider.npi", problem));
        }
        return this;
    }

    /** At least one procedure code is required, and none may be blank. */
    public RunInputs codes(List<CodeInput> codes) {
        if (codes == null || codes.isEmpty()) {
            errors.add(new FieldError("procedureCodes", "Choose at least one procedure code."));
            return this;
        }
        for (int i = 0; i < codes.size(); i++) {
            if (codes.get(i) == null || Texts.blank(codes.get(i).code())) {
                errors.add(new FieldError("procedureCodes[" + i + "].code", "This procedure code is blank."));
            }
        }
        return this;
    }

    /** An optional date that must be a real calendar date when given. */
    public RunInputs date(String field, String value) {
        if (Texts.present(value) && !IsoDates.valid(value)) {
            errors.add(new FieldError(field, "Enter the date as YYYY-MM-DD, for example 2026-01-31."));
        }
        return this;
    }

    /**
     * A declared expectation must be something this operation can be checked against: outcomes it
     * can produce and elements it returns. A misspelt one is refused here, before sending, because
     * afterwards it could only be reported as a failed test with a misleading reason. One longer
     * than the record keeps is reported by {@link #common} by its length alone.
     */
    public RunInputs expectation(String expected, OutcomeCode[] vocabulary, Collection<String> elements) {
        if (expected != null && expected.length() > RunRecord.LONGEST_EXPECTED) {
            // Its length is reported by common(). The terms are not read: a problem with one quotes
            // it, and text that long is not an expectation but something pasted, which can name a member.
            return this;
        }
        for (String problem : Expectations.problems(expected, vocabulary, elements)) {
            errors.add(new FieldError("expected", problem));
        }
        return this;
    }

    /**
     * A scenario can be chosen on the mock profile only. On live it is refused, not ignored:
     * letting a tester force an outcome against the real service would produce a result that did
     * not come from the service, and ignoring the choice silently would leave them believing it had.
     */
    public RunInputs scenario(String scenarioId, EpaProperties epa) {
        if (Texts.present(scenarioId) && !epa.mock()) {
            errors.add(new FieldError("scenarioId", "A scenario can be chosen on the mock profile only. On live the answer comes from the service."));
        }
        return this;
    }

    /**
     * The checks every request shares beyond its own fields: no field the tool does not know, a
     * chain id that is blank, a request for a new one, or an id this tool minted, and the free
     * text a run carries to the ledger within its limits and free of the member data the request
     * itself carries ({@link MemberInText}).
     */
    public RunInputs common(RunRequest request) {
        for (String key : request.unrecognised().keySet()) {
            errors.add(new FieldError(key, "This tool does not know the field \"" + key + "\". Check its spelling. Nothing was sent, "
                    + "because a field that is quietly dropped can change what a run means."));
        }
        Map<String, String> text = textForTheLedger(request.note(), request.testerRef(), request.expected());
        text.forEach((field, problem) -> errors.add(new FieldError(field, problem)));
        // Text over its limit is reported by its length and not read again, so a problem is never said twice.
        String note = text.containsKey("note") ? null : request.note();
        String testerRef = text.containsKey("testerRef") ? null : request.testerRef();
        String expected = text.containsKey("expected") ? null : request.expected();
        Map<String, String> member = MemberInText.problems(note, testerRef, expected, request.member(), "Nothing was sent.");
        member.forEach((field, problem) -> errors.add(new FieldError(field, problem)));
        if (Texts.present(request.rawBody())) {
            // A body of the tester's own goes out in place of the one built from the form, and the
            // screen sends no member with it, so the body is where this run's member is read from,
            // as for a request sent as written. A field already refused for the form's member is
            // not refused twice. The body is read as it will be sent, placeholders filled, because a
            // body loaded from a collection names its member as a placeholder.
            String sent = OwnBodyPlaceholders.asSent(request.rawBody(), request.variables());
            MemberInText.problemsInBody(note, testerRef, expected, MembersInBody.readWithPatientIds(sent), "Nothing was sent.")
                    .forEach((field, problem) -> {
                        if (!member.containsKey(field)) {
                            errors.add(new FieldError(field, problem));
                        }
                    });
        }
        String chainId = Texts.trimToNull(request.chainId());
        if (chainId != null && !RunRequest.NEW_CHAIN.equals(chainId) && !RunStamps.isChainId(chainId)) {
            errors.add(new FieldError("chainId", "The chain id must be blank, \"" + RunRequest.NEW_CHAIN + "\" to start a chain, or an "
                    + "id this tool returned for one."));
        }
        return this;
    }

    /**
     * What is wrong with the free text a run writes to the ledger as typed, by field: the note, the
     * tester reference and the expectation. Empty when nothing is. The limits are the record's own
     * ({@link RunRecord#LONGEST_NOTE} and the two beside it), so a run form, a suite row and a note
     * added later are held to the same ones and the record never has to cut anything. No message
     * repeats the text, which can name a member.
     */
    public static Map<String, String> textForTheLedger(String note, String testerRef, String expected) {
        return textForTheLedger(note, testerRef, expected, true);
    }

    /**
     * The same, with the shape of the expectation's terms checked only when asked. A chain's
     * expectation names a step before each term, and each step's part is checked when that step
     * is run.
     */
    public static Map<String, String> textForTheLedger(String note, String testerRef, String expected, boolean termShapes) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (note != null && note.length() > RunRecord.LONGEST_NOTE) {
            problems.put("note", "A note can be at most " + RunRecord.LONGEST_NOTE + " characters, and this one has " + note.length()
                    + ". A note is kept as written, so do not paste a request or a response into it.");
        }
        if (testerRef != null && testerRef.length() > RunRecord.LONGEST_TESTER_REF) {
            problems.put("testerRef", "A tester reference can be at most " + RunRecord.LONGEST_TESTER_REF + " characters, and this one has "
                    + testerRef.length() + ". It names a ticket or a test step, and is kept as written.");
        }
        if (expected != null && expected.length() > RunRecord.LONGEST_EXPECTED) {
            problems.put("expected", "An expectation can be at most " + RunRecord.LONGEST_EXPECTED + " characters, and this one has "
                    + expected.length() + ". It holds outcome codes and name=code terms, and is kept as written.");
        } else if (termShapes) {
            String shape = expectationShape(expected);
            if (shape != null) {
                problems.put("expected", shape);
            }
        }
        return problems;
    }

    /**
     * What is wrong with the shape of an expectation's terms, or null. A term may name what it is
     * about before a colon: a procedure code that was asked about, several joined by a comma, or a
     * place in the response. None of those holds a space, and a code never does, so a term that
     * does holds something else, and the expectation is written to the ledger as typed.
     */
    static String expectationShape(String expected) {
        if (Texts.blank(expected)) {
            return null;
        }
        String[] terms = expected.split("[;\\r\\n]");
        int number = 0;
        for (String part : terms) {
            if (Texts.blank(part)) {
                continue;
            }
            number++;
            String term = part.strip();
            int equals = term.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String left = term.substring(0, equals).strip();
            String code = term.substring(equals + 1).strip();
            int colon = left.lastIndexOf(':');
            String subject = colon < 0 ? null : left.substring(0, colon).strip();
            if (subject != null && !subject.isEmpty() && !SUBJECT.matcher(subject).matches()) {
                return "Term " + number + " of the expectation names what it is about with a space in it. That part, before the colon, is "
                        + "a procedure code that was asked about or a place in the response, such as 95810 or ServiceRequest/sr-1, and "
                        + "never holds a space.";
            }
            if (!code.isEmpty() && !CODE.matcher(code).matches()) {
                return "Term " + number + " of the expectation expects a code with a space in it. A code is one word, such as auth-needed.";
            }
        }
        return null;
    }

    public RunInputs require(boolean condition, String field, String message) {
        if (!condition) {
            errors.add(new FieldError(field, message));
        }
        return this;
    }

    public List<FieldError> errors() {
        return List.copyOf(errors);
    }

    /** Refuses the run when any check failed. */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
