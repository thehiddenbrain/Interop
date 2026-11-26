package org.p32h.interop.epa.pave.service.codelist;

import java.util.ArrayList;
import java.util.List;
import org.p32h.interop.epa.pave.service.suite.CsvRows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Puts the code list's CSV reader behind the interface the suite reader reads through, so the tool
 * has one CSV reader and a suite file with a quoted comma is read by the same rules as the list.
 *
 * <p>The suite side matches cells to columns by position and reports a row whose cell count is
 * wrong, so a row is handed over with exactly the cells it has: never padded and never cut.
 *
 * <p>Anything the reader reports about the quoting is handed over as a problem with the text as a
 * whole, and the suite reader then runs none of the file. That is deliberate. Where a quote is
 * wrong the reader keeps going by a fixed rule, which is good enough to show a code list and not
 * good enough to send a member ID that may have come from the wrong cell.
 */
@Component
public class CsvRowsAdapter implements CsvRows {

    /** The most quoting problems named one by one. A file with the same mistake on every line has as many as it has lines. */
    static final int PROBLEMS_NAMED = 10;

    private final CsvReader reader;

    /** A suite file is comma separated, whatever delimiter the code list is configured with. */
    @Autowired
    public CsvRowsAdapter() {
        this(CsvReader.commaSeparated());
    }

    public CsvRowsAdapter(CsvReader reader) {
        this.reader = reader;
    }

    @Override
    public Table read(String text) {
        CsvContent content = reader.read(text);
        List<String> problems = new ArrayList<>();
        content.problems().stream().limit(PROBLEMS_NAMED).forEach(problem -> problems.add(problem.message()));
        if (content.problems().size() > PROBLEMS_NAMED) {
            int more = content.problems().size() - PROBLEMS_NAMED;
            problems.add("The quoting is wrong in " + more + " more " + (more == 1 ? "place" : "places") + " further down the file.");
        }
        if (content.records().isEmpty()) {
            return new Table(List.of(), List.of(), problems);
        }
        // The first record is the header whatever it holds. If it is blank the suite reader says
        // that the first row names no columns, which is a better answer than taking the first
        // case for a header and reporting its member ID as a column nobody knows.
        List<String> header = content.records().get(0).cells();
        List<List<String>> rows = content.records().stream().skip(1).filter(record -> !record.blank()).map(CsvRecord::cells).toList();
        return new Table(header, rows, problems);
    }
}
