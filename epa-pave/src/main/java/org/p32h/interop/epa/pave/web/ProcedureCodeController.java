package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.codelist.CodeSearch;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The procedure code picker and the self-check of the list behind it.
 *
 * <p>Both endpoints only read the list that was loaded at startup. Any text is a legal query, so
 * neither has a validation error of its own: a query that matches nothing is answered with an
 * empty list and the reason, where there is one.
 */
@RestController
@RequestMapping("/api/procedure-codes")
public class ProcedureCodeController {

    private final ProcedureCodeService codes;
    private final AppProperties app;

    public ProcedureCodeController(ProcedureCodeService codes, AppProperties app) {
        this.codes = codes;
        this.app = app;
    }

    /**
     * What the type-ahead answers.
     *
     * @param placeholder    true when the list in use is synthetic: the placeholder that ships with
     *                       the tool, which is loaded while {@code codelist.file} is blank, or a
     *                       configured list whose rows say of themselves that they are synthetic.
     *                       Left out for any other list. The codes still carry what the list states,
     *                       so the picker shows its tags; this is what lets the screen say beside
     *                       every tag that it is not from a P32H list
     * @param query          the query as it was searched, without the white space around it. Left out when none was given
     * @param lineOfBusiness the line of business that was asked for. Left out when none was given
     * @param narrowed       true when the codes were narrowed to that line and to the rows that state no line
     * @param notice         a sentence saying why they were not narrowed although a line was given. Left out otherwise
     * @param otherLines     a sentence naming the other lines of business the list has matching
     *                       rows under, when the code that was typed is listed under other lines
     *                       only, or narrowing left nothing to offer. Left out otherwise. Without
     *                       it an empty answer would read as "this code is not in the list"
     * @param limit          the most codes one answer holds, {@code app.autocomplete-limit}
     * @param matched        how many rows matched in all, after any narrowing. More than {@code codes}
     *                       holds when the limit cut the answer, so the picker can say "25 of 61"
     * @param codes          the rows, best match first. Each always carries {@code authRequired}:
     *                       {@code UNKNOWN} means the list does not say, which the picker shows as
     *                       no tag at all and never as "No PA"
     * @param exactCode      when the query is a whole code on the list, the code as the list writes
     *                       it, whatever case the query was typed in. Left out when it is not, so
     *                       the picker can say "not on the list" without comparing text itself
     * @param shapeNote      when the query is not on the list and does not have the shape of a CPT
     *                       or HCPCS code, a sentence that says so. It is still sent as chosen: an
     *                       unknown code is a negative case, and this only says what it is not.
     *                       Left out otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"placeholder", "query", "lineOfBusiness", "narrowed", "notice", "otherLines", "limit", "matched", "exactCode", "shapeNote",
            "codes"})
    public record Answer(Boolean placeholder, String query, String lineOfBusiness, boolean narrowed, String notice, String otherLines,
                         int limit, int matched, List<ProcedureCode> codes, String exactCode, String shapeNote) {

        public Answer(Boolean placeholder, String query, String lineOfBusiness, boolean narrowed, String notice, String otherLines,
                      int limit, int matched, List<ProcedureCode> codes) {
            this(placeholder, query, lineOfBusiness, narrowed, notice, otherLines, limit, matched, codes, null, null);
        }
    }

    /**
     * The shapes of a procedure code: a CPT code is five digits, or four digits and a letter (the
     * category II and III codes), and a HCPCS Level II code is a letter and four digits.
     */
    private static final Pattern CODE_SHAPE = Pattern.compile("\\d{5}|\\d{4}[A-Za-z]|[A-Za-z]\\d{4}");

    /** The sentence for a query that is not on the list and is not shaped as a code, or null. */
    static String shapeNote(String query, String exactCode) {
        String typed = Texts.trimToNull(query);
        if (typed == null || exactCode != null || CODE_SHAPE.matcher(typed).matches()) {
            return null;
        }
        return "\"" + typed + "\" does not have the shape of a CPT or HCPCS code (five digits, four digits and a letter, or a letter and four "
                + "digits). It is sent as you chose it.";
    }

    /**
     * @param q   matched against the start of the code and anywhere in the description, ignoring case. Blank offers the first rows
     * @param lob the member's line of business, when a member has been resolved
     */
    @GetMapping
    public Answer search(@RequestParam(name = "q", required = false) String q, @RequestParam(name = "lob", required = false) String lob) {
        int limit = app.autocompleteLimit();
        CodeSearch found = codes.find(q, lob, limit);
        // True or left out, never false. A false would read as this tool vouching that the list is
        // P32H's own, and it cannot know that of any file it was pointed at.
        Boolean placeholder = found.placeholder() ? Boolean.TRUE : null;
        // Any line and any date: the question is whether the list has the code at all, which the
        // narrowing above must not hide.
        List<ProcedureCode> exact = Texts.blank(q) ? List.of() : codes.rowsFor(q, null, null);
        String exactCode = exact.isEmpty() ? null : exact.get(0).code();
        return new Answer(placeholder, found.query(), Texts.trimToNull(lob), found.narrowed(), found.notice(), found.otherLines(), limit,
                found.matched(), found.codes(), exactCode, shapeNote(q, exactCode));
    }

    /** What the tool made of the code list. The first thing to call after dropping in a new export. */
    @GetMapping("/self-check")
    public CodeListSelfCheck selfCheck() {
        return codes.selfCheck();
    }
}
