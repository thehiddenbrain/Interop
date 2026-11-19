package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.model.IsoDates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The names of the ledger's files: one per day, {@code runs-YYYY-MM-DD.jsonl}.
 *
 * <p>Anything else in the directory is not a ledger file and is never read, listed or touched.
 */
final class DayFiles {

    private static final Pattern NAME = Pattern.compile("runs-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

    private DayFiles() {
    }

    /** One day's file. */
    record DayFile(LocalDate day, Path path) {

        String name() {
            return path.getFileName().toString();
        }
    }

    static String nameFor(LocalDate day) {
        return "runs-" + day + ".jsonl";
    }

    /** The day a file name stands for, or null when the name is not a ledger file's. */
    static LocalDate dayOf(String fileName) {
        Matcher matcher = NAME.matcher(fileName == null ? "" : fileName);
        // Strict, so a file named for the 30th of February is not taken for a ledger file.
        return matcher.matches() ? IsoDates.parse(matcher.group(1)) : null;
    }

    /** Every ledger file in the directory, newest day first. Empty when the directory does not exist. */
    static List<DayFile> newestFirst(Path directory) throws IOException {
        List<DayFile> files = new ArrayList<>();
        if (directory == null) {
            return files;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(path -> {
                LocalDate day = dayOf(path.getFileName().toString());
                if (day != null) {
                    files.add(new DayFile(day, path));
                }
            });
        } catch (NoSuchFileException e) {
            return files;
        } catch (UncheckedIOException e) {
            // A listing can fail part way through, and the stream reports that unchecked.
            throw e.getCause();
        }
        files.sort(Comparator.comparing(DayFile::day).reversed());
        return files;
    }
}
