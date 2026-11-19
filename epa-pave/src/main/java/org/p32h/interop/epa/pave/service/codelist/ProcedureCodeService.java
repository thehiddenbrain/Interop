package org.p32h.interop.epa.pave.service.codelist;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The procedure code list: loaded once when the application starts, then searched by the picker
 * and consulted by the conformance checks.
 *
 * <p>The list is read in the constructor, so this object cannot exist without one. A file that
 * cannot be used stops the application from starting, with a message that names it. Nothing
 * changes after that, which is why no method here needs a lock.
 *
 * <p>It is the same bean on the mock and the live profile: what the code list says does not
 * depend on where the requests go.
 */
@Service
public class ProcedureCodeService implements ProcedureCodeDirectory {

    private static final Logger log = LoggerFactory.getLogger(ProcedureCodeService.class);

    private final LoadedCodeList list;
    private final LineMatcher lines;
    private final Map<String, List<LoadedCodeList.Entry>> byCode = new LinkedHashMap<>();

    /** @throws UnusableCodeListException when the configured list cannot be used. The message names the file and says what to change */
    public ProcedureCodeService(CodelistProperties properties) {
        LoadedCodeList loaded;
        try {
            loaded = CodeListLoader.load(properties);
        } catch (UnusableCodeListException e) {
            // The framework reports a failed start as a stack trace with this reason at the bottom
            // of it. One line that says it outright is what the person who set codelist.file needs.
            // The message is the loader's own: fixed sentences, the path, and for a file with no
            // code column the first row of that file. No member data unless the path points at some.
            log.error("The procedure code list cannot be used, so the application will not start. {}", e.getMessage());
            throw e;
        }
        this.list = loaded;
        this.lines = new LineMatcher(properties.lineOfBusinessAliases());
        list.entries().forEach(entry -> byCode.computeIfAbsent(entry.codeKey(), key -> new ArrayList<>()).add(entry));

        CodeListSelfCheck check = list.selfCheck();
        // A code list holds no member data. These lines still give only where the list came from
        // and how much of it there is, never the text of a cell.
        log.info("Procedure code list loaded from {} as {}: {} rows, {} state a prior authorization requirement, {} name a vendor",
                check.source(), check.format(), check.rowCount(), check.statingAuthRequirement(), check.namingVendor());
        int warnings = check.warnings().size() - (check.placeholder() ? 1 : 0);
        if (check.placeholder()) {
            // Outcome only: which list is in use.
            log.info("The bundled placeholder code list is in use. Every row in it is synthetic. Set codelist.file to the real export.");
        }
        if (warnings > 0) {
            // A count and nothing else. The warnings themselves are served by the self-check.
            log.warn("The procedure code list was loaded with {} warning(s). GET /api/procedure-codes/self-check lists them.", warnings);
        }
    }

    @Override
    public List<ProcedureCode> search(String query, String lineOfBusiness, int limit) {
        return find(query, lineOfBusiness, limit).codes();
    }

    /**
     * The type-ahead search with its explanation. Rows are matched on the code, by prefix, and on
     * the description, by containment, both ignoring case. A code that is the query comes first,
     * then codes that start with it, then descriptions that hold it, each in the order of the file.
     * A blank query matches every row.
     *
     * <p>When a line of business is given and the list names it, only its rows and the rows that
     * state no line are offered. When the list does not name it anywhere, nothing is narrowed and
     * the notice says so: holding back every named row would leave a tester with an empty picker
     * and no reason for it.
     */
    public CodeSearch find(String query, String lineOfBusiness, int limit) {
        String cleaned = CodeListText.clean(query);
        String wantedText = cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
        boolean lineGiven = Texts.present(lineOfBusiness);
        Set<String> wantedLines = lineGiven ? lines.wanted(lineOfBusiness) : Set.of();
        boolean narrowed = lineGiven && named(wantedLines);

        List<List<ProcedureCode>> ranked = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        int matched = 0;
        boolean theCodeIsOffered = false;
        HeldBack heldBack = new HeldBack();
        HeldBack heldBackForTheCode = new HeldBack();
        for (LoadedCodeList.Entry entry : list.entries()) {
            int rank = rank(entry, wantedText);
            if (rank < 0) {
                continue;
            }
            boolean isTheCode = wantedText != null && rank == 0;
            if (narrowed && !entry.statesNoLine() && !entry.names(wantedLines)) {
                heldBack.add(entry);
                if (isTheCode) {
                    heldBackForTheCode.add(entry);
                }
                continue;
            }
            matched++;
            theCodeIsOffered |= isTheCode;
            if (ranked.get(rank).size() < limit) {
                ranked.get(rank).add(entry.row());
            }
        }
        List<ProcedureCode> codes = new ArrayList<>();
        ranked.forEach(codes::addAll);
        if (codes.size() > Math.max(limit, 0)) {
            codes = codes.subList(0, Math.max(limit, 0));
        }
        String line = lineGiven ? CodeListText.show(lineOfBusiness.strip()) : null;
        // Rows for other lines are held back on nearly every narrowed query, and saying so each
        // time would be noise. It is said in the two cases where silence misleads: the code that
        // was typed is in the list under other lines only, or nothing at all is left to offer.
        String otherLines = null;
        if (heldBackForTheCode.rows > 0 && !theCodeIsOffered) {
            otherLines = "The list has " + CodeListText.count(heldBackForTheCode.rows, "row", "rows") + " for "
                    + CodeListText.show(heldBackForTheCode.firstCode) + " under " + heldBackForTheCode.lines() + ". "
                    + (heldBackForTheCode.rows == 1 ? "It is" : "They are") + " not offered for " + line + ".";
        } else if (matched == 0 && heldBack.rows > 0) {
            otherLines = "Nothing is offered for " + line + ". " + CodeListText.count(heldBack.rows, "row matches", "rows match")
                    + " under " + heldBack.lines() + ".";
        }
        return new CodeSearch(cleaned, codes, matched, narrowed, lineGiven && !narrowed ? notNarrowed(line) : null, otherLines);
    }

    /** The rows a line of business filter kept out of one answer, and the lines they are for. */
    private final class HeldBack {

        private final Set<String> names = new LinkedHashSet<>();
        private int rows;
        private String firstCode;

        void add(LoadedCodeList.Entry entry) {
            rows++;
            if (firstCode == null) {
                firstCode = entry.row().code();
            }
            entry.lines().forEach(line -> names.add(list.lineNames().get(line)));
        }

        /** "another line of business (Medicare)" or "other lines of business (Medicare and Medicaid)". */
        String lines() {
            return (names.size() == 1 ? "another line" : "other lines") + " of business (" + CodeListText.join(new ArrayList<>(names)) + ")";
        }
    }

    @Override
    public List<ProcedureCode> rowsFor(String code, String lineOfBusiness, LocalDate onDate) {
        String wanted = CodeListText.clean(code);
        if (wanted == null) {
            return List.of();
        }
        // The whole code and never a prefix. Case is ignored: no code set tells two procedure
        // codes apart by case, and a code typed in lower case would otherwise be reported as
        // missing from a list that has it.
        List<LoadedCodeList.Entry> entries = byCode.getOrDefault(wanted.toLowerCase(Locale.ROOT), List.of());
        boolean lineGiven = Texts.present(lineOfBusiness);
        Set<String> wantedLines = lineGiven ? lines.wanted(lineOfBusiness) : Set.of();
        // No fallback here, unlike the picker. A caller comparing an answer for a member of one
        // line must never be handed another line's row, also when the list does not know the line.
        return entries.stream()
                .filter(entry -> !lineGiven || entry.statesNoLine() || entry.names(wantedLines))
                .filter(entry -> entry.appliesOn(onDate))
                .map(LoadedCodeList.Entry::row).toList();
    }

    @Override
    public boolean statesLine(String lineOfBusiness) {
        return Texts.present(lineOfBusiness) && named(lines.wanted(lineOfBusiness));
    }

    @Override
    public CodeListSelfCheck selfCheck() {
        return list.selfCheck();
    }

    private boolean named(Set<String> wantedLines) {
        Set<String> common = new HashSet<>(wantedLines);
        common.retainAll(list.lineNames().keySet());
        return !common.isEmpty();
    }

    /** 0 when the code is the query, 1 when it starts with it, 2 when the description holds it, -1 otherwise. */
    private static int rank(LoadedCodeList.Entry entry, String wantedText) {
        if (wantedText == null || entry.codeKey().equals(wantedText)) {
            return 0;
        }
        if (entry.codeKey().startsWith(wantedText)) {
            return 1;
        }
        return entry.descriptionKey().contains(wantedText) ? 2 : -1;
    }

    private String notNarrowed(String line) {
        if (list.lineNames().isEmpty()) {
            return "The code list states no line of business on any row, so every row is offered and the codes are not narrowed to "
                    + line + ".";
        }
        return "The code list has no row for the line of business " + line + ", so the codes are not narrowed to it. The list names: "
                + CodeListText.join(new ArrayList<>(list.lineNames().values())) + ". If one of those is the same line under another "
                + "name, add it to codelist.line-of-business-aliases.";
    }
}
