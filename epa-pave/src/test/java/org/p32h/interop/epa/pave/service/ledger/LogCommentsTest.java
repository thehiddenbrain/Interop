package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every log statement in the ledger and the suites says, in a comment just above it, what it logs.
 * Found by the member data audit (m8): one in the suite service had none. These are the two
 * packages that handle member IDs on their way to a file, so a statement added without saying what
 * it prints is found here.
 */
class LogCommentsTest {

    private static final Path SOURCES = Path.of("src/main/java/org/p32h/interop/epa/pave");
    private static final Pattern LOG = Pattern.compile("^\\s*log\\.(trace|debug|info|warn|error)\\(");

    @Test
    void everyLogStatementInTheLedgerAndTheSuitesHasACommentSayingWhatItLogs() throws IOException {
        List<String> bare = new ArrayList<>();
        int statements = 0;
        for (String folder : List.of("service/ledger", "service/suite")) {
            try (Stream<Path> files = Files.list(SOURCES.resolve(folder))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (LOG.matcher(lines.get(i)).find()) {
                            statements++;
                            if (i == 0 || !lines.get(i - 1).strip().startsWith("//")) {
                                bare.add(file.getFileName() + ":" + (i + 1));
                            }
                        }
                    }
                }
            }
        }
        assertThat(statements).as("log statements found").isGreaterThanOrEqualTo(8);
        assertThat(bare).as("log statements without a comment just above them").isEmpty();
    }
}
