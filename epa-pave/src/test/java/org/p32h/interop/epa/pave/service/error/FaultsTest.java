package org.p32h.interop.epa.pave.service.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Which messages a description of a failure may carry. Each throwable that may give a detail does
 * so because the Java runtime or the IDE's compiler writes its text about the program; every other
 * message stays out, because it can quote member data. S123456789 stands for that data here.
 */
class FaultsTest {

    @Test
    void theRuntimesWordsAboutTheProgramAreGiven() {
        assertThat(Faults.cause(new NoSuchMethodError("'void org.example.Reader.read(java.lang.String)'")))
                .isEqualTo("java.lang.NoSuchMethodError; java.lang.NoSuchMethodError says: 'void org.example.Reader.read(java.lang.String)'");
        assertThat(Faults.cause(new UnsatisfiedLinkError("no zipfix in java.library.path")))
                .endsWith("java.lang.UnsatisfiedLinkError says: no zipfix in java.library.path");
        assertThat(Faults.cause(new ClassNotFoundException("org.example.Reader")))
                .isEqualTo("java.lang.ClassNotFoundException; java.lang.ClassNotFoundException says: org.example.Reader");
        assertThat(Faults.cause(new OutOfMemoryError("Java heap space")))
                .isEqualTo("java.lang.OutOfMemoryError; java.lang.OutOfMemoryError says: Java heap space");
    }

    @Test
    void theFirstDetailInTheChainIsGivenAfterTheWholeChain() {
        NoClassDefFoundError missing = new NoClassDefFoundError("org/example/Reader");
        missing.initCause(new ClassNotFoundException("org.example.Reader"));
        Throwable error = new IllegalStateException("member S123456789", missing);
        String cause = Faults.cause(error);
        assertThat(cause).startsWith("java.lang.IllegalStateException <- ").contains(" <- java.lang.ClassNotFoundException; ")
                .endsWith(" says: org/example/Reader").doesNotContain("S123456789");
    }

    @Test
    void aMessageThatCouldQuoteAnythingIsNeverGiven() {
        // An initializer's failure quotes what the initializer threw.
        assertThat(Faults.cause(new ExceptionInInitializerError(new IllegalArgumentException("member S123456789"))))
                .isEqualTo("java.lang.ExceptionInInitializerError <- java.lang.IllegalArgumentException");
        // An InternalError given only a cause takes the cause's text as its own message.
        InternalError internal = new InternalError(new IOException("member S123456789"));
        assertThat(internal.getMessage()).contains("S123456789");
        assertThat(Faults.cause(internal)).isEqualTo("java.lang.InternalError <- java.io.IOException");
        // An Error of exactly that class gives a detail only when the compiler wrote it.
        assertThat(Faults.cause(new Error("member S123456789"))).isEqualTo("java.lang.Error");
        assertThat(Faults.cause(new AssertionError("Unresolved compilation problem: S123456789"))).isEqualTo("java.lang.AssertionError");
        assertThat(Faults.describe(new RuntimeException("member S123456789", new IOException("S123456789"))))
                .doesNotContain("S123456789").contains("java.lang.RuntimeException <- java.io.IOException");
    }

    @Test
    void aDetailIsOneLineAndCutAtThreeHundredCharacters() {
        String problems = "Unresolved compilation problems: \n\t" + "x cannot be resolved to a variable\n\t".repeat(40);
        String detail = Faults.detail(new Error(problems));
        assertThat(detail).hasSize(Faults.DETAIL_LIMIT).endsWith("...").doesNotContain("\n").doesNotContain("\t")
                .startsWith("Unresolved compilation problems: x cannot be resolved to a variable x cannot");
        assertThat(Faults.cause(new Error(problems)).lines().count()).isEqualTo(1);
    }

    @Test
    void aChainThatLoopsOrRunsLongIsBounded() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);
        assertThat(Faults.cause(first)).isEqualTo("java.lang.IllegalStateException <- java.lang.IllegalArgumentException");
        Throwable deep = new IllegalStateException("bottom");
        for (int i = 0; i < 20; i++) {
            deep = new RuntimeException("level", deep);
        }
        assertThat(Faults.cause(deep)).endsWith(" <- ...").doesNotContain("IllegalStateException");
    }

    @Test
    void theDescriptionBeginsWithTheCauseAndThenGivesTheFramesAsBefore() {
        Throwable error = new RuntimeException("x", new NoClassDefFoundError("org/example/Reader"));
        String[] lines = Faults.describe(error).split("\\R");
        assertThat(lines[0]).isEqualTo(Faults.cause(error));
        assertThat(lines[1]).isEqualTo("java.lang.RuntimeException");
        assertThat(lines[2]).startsWith("    at ");
        assertThat(Faults.describe(error)).contains(" <- caused by java.lang.NoClassDefFoundError");
    }
}
