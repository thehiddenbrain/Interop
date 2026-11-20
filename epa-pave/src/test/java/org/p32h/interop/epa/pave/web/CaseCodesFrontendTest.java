package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What the page sends as the procedure codes of the case is what the chips show, and nothing else.
 * The build has no script engine, so these read the two scripts that decide it; the behaviour
 * itself was driven in the real browser. Each check fails on the code as it was before the fix.
 */
class CaseCodesFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    /** The body of the first function or method named, up to its closing brace at the same indent. */
    private static String body(String source, String opening) {
        int start = source.indexOf(opening);
        assertThat(start).as(opening).isGreaterThanOrEqualTo(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}", start);
        assertThat(end).as("end of " + opening).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void textLeftInTheCodeBoxIsNeverMovedIntoTheCaseWhenTheRequestIsRead() throws IOException {
        String values = body(read("code-picker.js"), "values() {");
        assertThat(values).doesNotContain("commitTyped").doesNotContain("input.value");
    }

    @Test
    void theSameCodeIsRefusedByWhatEachEntryHoldsOnEveryWayIn() throws IOException {
        String picker = read("code-picker.js");
        String addEntry = body(picker, "function addEntry(");
        // An entry keeps its code under input. Comparing entry.code compared undefined and let every repeat in.
        assertThat(addEntry).contains("existing.input.code === String(candidate.code)").doesNotContain("existing.code ===");
        // Every way a code gets into the case goes through addEntry: no other place appends to the entries.
        Matcher appends = Pattern.compile("entries\\s*=\\s*entries\\.concat").matcher(picker);
        int count = 0;
        while (appends.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(addEntry).contains("entries = entries.concat");
    }

    @Test
    void aSendWithTextLeftInTheCodeBoxIsRefusedBeforeAnythingIsAsked() throws IOException {
        String call = body(read("workbench.js"), "async call(send, options) {");
        int refusal = call.indexOf("typedNotAdded()");
        int sending = call.indexOf("send()");
        assertThat(refusal).isGreaterThanOrEqualTo(0);
        assertThat(sending).isGreaterThan(refusal);
        assertThat(call).contains("was typed in the procedure code box and not added to the case.")
                .contains("Choose Add code to add it, or clear the box, then send again.")
                .contains("field: 'procedureCodes'");
    }
}
