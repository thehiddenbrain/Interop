package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.FileGuard;

/**
 * R-36: every Postman file the upload hunts saw refused for a wrong reason, or accepted and then
 * fatal, is now read or refused for its true reason, before anything is stored.
 *
 * <p>The inputs are placeholder copies of the hunts' small corpus under
 * {@code src/test/resources/postman/refusals}. The files in another encoding are made here from
 * those ASCII files, because the repository holds only UTF-8 text.
 */
class PostmanParserRefusalsTest {

    private static final String REFUSALS = "refusals/";
    private static final String FLATTENED_ACTION = "Export it from Postman, or save it with -Depth 100.";

    private static String text(String name) {
        return PostmanFiles.text(REFUSALS + name);
    }

    /** The placeholder environment with its one letter outside ASCII put back, written as a number to keep this source ASCII. */
    private static String environmentWithUmlaut() {
        return text("env-placeholder.json").replace("PRUEFUNG", "PR" + (char) 0xDC + "FUNG");
    }

    private static byte[] withMark(byte[] mark, byte[] body) {
        byte[] out = Arrays.copyOf(mark, mark.length + body.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    private static BadInputException refusal(Runnable read) {
        try {
            read.run();
        } catch (BadInputException e) {
            return e;
        }
        throw new AssertionError("The file was not refused");
    }

    // ------------------------------------------------------------------------------------------
    // Encodings
    // ------------------------------------------------------------------------------------------

    @Test
    void aFileSavedAsUtf16IsReadByItsByteOrderMarkInEitherOrder() {
        String original = environmentWithUmlaut();
        byte[] littleEndian = withMark(new byte[] {(byte) 0xFF, (byte) 0xFE}, original.getBytes(StandardCharsets.UTF_16LE));
        byte[] bigEndian = withMark(new byte[] {(byte) 0xFE, (byte) 0xFF}, original.getBytes(StandardCharsets.UTF_16BE));

        for (byte[] content : new byte[][] {littleEndian, bigEndian}) {
            String decoded = PostmanParser.decode(content);
            assertThat(decoded).isEqualTo(original);
            PostmanEnvironment environment = PostmanParser.environment(decoded);
            assertThat(environment.name()).isEqualTo("Vendor Sandbox (placeholder)");
            assertThat(environment.inForce().get("city").value()).isEqualTo("PR" + (char) 0xDC + "FUNG");
        }
    }

    @Test
    void aFileSavedAsUtf16WithoutItsMarkIsToldByTheZeroBesideTheFirstCharacter() {
        String original = text("col-placeholder.json");
        for (Charset charset : new Charset[] {StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            PostmanCollection collection = PostmanParser.collection(PostmanParser.decode(original.getBytes(charset)));
            assertThat(collection.name()).as(charset.name()).isEqualTo("Vendor ePA Sandbox (placeholder)");
            assertThat(collection.requests()).as(charset.name()).hasSize(1);
        }
    }

    @Test
    void aUtf16FileReadAsUtf8ByAnotherReaderIsRefusedAsAnotherEncodingNotAsBrokenJson() {
        // What a reader that ignores zero bytes hands over: every character followed by a zero.
        String asUtf8 = new String(text("env-placeholder.json").getBytes(StandardCharsets.UTF_16LE), StandardCharsets.UTF_8);

        BadInputException refused = refusal(() -> PostmanParser.environment(asUtf8));
        assertThat(refused.getMessage()).isEqualTo("This file is saved in another encoding (for example by PowerShell or Notepad).");
        assertThat(refused.action()).isEqualTo("Save it as UTF-8, or export it again from Postman.");
    }

    @Test
    void aFileCutInsideACharacterIsCalledIncompleteNotAnotherEncoding() {
        byte[] whole = environmentWithUmlaut().getBytes(StandardCharsets.UTF_8);
        int umlaut = indexOf(whole, (byte) 0xC3);
        // Cut after the first of the letter's two bytes, as a download that stopped there leaves it.
        byte[] cut = Arrays.copyOf(whole, umlaut + 1);

        BadInputException refused = refusal(() -> PostmanParser.decode(cut));
        assertThat(refused.getMessage()).isEqualTo("This file is not complete: it stops part way through a character, as a truncated "
                + "download or a partial copy does.");
        assertThat(refused.action()).isEqualTo("Export it again from Postman and upload the whole file.");

        // A character of three and of four bytes, cut after each of its leading bytes.
        for (String tail : new String[] {"x" + (char) 0x20AC, "x" + new String(Character.toChars(0x1F600))}) {
            byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
            for (int keep = 2; keep < bytes.length; keep++) {
                byte[] partial = Arrays.copyOf(bytes, keep);
                assertThat(refusal(() -> PostmanParser.decode(partial)).getMessage()).as("kept " + keep).startsWith("This file is not complete");
            }
        }

        // UTF-16 cut inside a code unit, and after the first half of a surrogate pair.
        byte[] utf16 = withMark(new byte[] {(byte) 0xFF, (byte) 0xFE}, ("{}" + new String(Character.toChars(0x1F600)))
                .getBytes(StandardCharsets.UTF_16LE));
        assertThat(refusal(() -> PostmanParser.decode(Arrays.copyOf(utf16, utf16.length - 1))).getMessage()).startsWith("This file is not complete");
        assertThat(refusal(() -> PostmanParser.decode(Arrays.copyOf(utf16, utf16.length - 2))).getMessage()).startsWith("This file is not complete");
        assertThat(refusal(() -> PostmanParser.decode(Arrays.copyOf(utf16, utf16.length - 3))).getMessage()).startsWith("This file is not complete");
    }

    @Test
    void aFileSavedInAWindowsCodePageIsToldHowToSaveItAgain() {
        byte[] windows1252 = environmentWithUmlaut().getBytes(Charset.forName("windows-1252"));

        BadInputException refused = refusal(() -> PostmanParser.decode(windows1252));
        assertThat(refused.getMessage()).isEqualTo("This file is saved in another encoding (for example by PowerShell or Notepad).");
        assertThat(refused.action()).isEqualTo("Save it as UTF-8, or export it again from Postman.");
    }

    @Test
    void aFileThatIsNotTextKeepsItsOwnReason() {
        // The start of a zip archive: no encoding makes it a Postman export.
        byte[] archive = {0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, (byte) 0x9c, (byte) 0xff};

        assertThat(refusal(() -> PostmanParser.decode(archive)).getMessage())
                .isEqualTo("The uploaded file is not text in UTF-8, so it is not a Postman export.");
    }

    // ------------------------------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------------------------------

    @Test
    void aNameHoldingHalfACharacterIsRefusedBeforeAnythingIsStored() {
        BadInputException environment = refusal(() -> PostmanParser.environment(text("env-lone-surrogate-name.json")));
        assertThat(environment.getMessage()).isEqualTo("The name holds a broken character; rename it in Postman and export again.");
        assertThat(environment.action()).isNull();
        // The line the upload shows is that sentence alone.
        assertThat(FileGuard.read("env.json", () -> PostmanParser.environment(text("env-lone-surrogate-name.json"))).line())
                .isEqualTo("The name holds a broken character; rename it in Postman and export again.");

        assertThat(refusal(() -> PostmanParser.collection(text("col-lone-surrogate-name.json"))).getMessage())
                .isEqualTo("The name holds a broken character; rename it in Postman and export again.");
    }

    @Test
    void aWholeCharacterOutsideTheBasicPlaneIsNotBroken() {
        String emoji = new String(Character.toChars(0x1F600));
        String named = text("env-placeholder.json").replace("Vendor Sandbox (placeholder)", "Vendor Sandbox " + emoji);

        assertThat(PostmanParser.environment(named).name()).isEqualTo("Vendor Sandbox " + emoji);
        assertThat(PostmanParser.brokenCharacter("a" + emoji + "b")).isFalse();
        assertThat(PostmanParser.brokenCharacter("a" + (char) 0xD83D)).isTrue();
        assertThat(PostmanParser.brokenCharacter((char) 0xDE00 + "a")).isTrue();
        assertThat(PostmanParser.brokenCharacter("a" + (char) 0xD83D + (char) 0xD83D + (char) 0xDE00)).isTrue();
        assertThat(PostmanParser.brokenCharacter(null)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Files of other tools
    // ------------------------------------------------------------------------------------------

    @Test
    void anOpenApiFileIsNamedAsSuchUnderEitherUpload() {
        for (String name : new String[] {"openapi.json", "swagger.json"}) {
            BadInputException asEnvironment = refusal(() -> PostmanParser.environment(text(name)));
            assertThat(asEnvironment.getMessage()).as(name).isEqualTo("This is an OpenAPI file, not a Postman export.");
            assertThat(asEnvironment.action()).as(name).startsWith("In Postman, open Environments");

            BadInputException asCollection = refusal(() -> PostmanParser.collection(text(name)));
            assertThat(asCollection.getMessage()).as(name).isEqualTo("This is an OpenAPI file, not a Postman export.");
            assertThat(asCollection.action()).as(name).startsWith("In Postman, open the menu of the collection");
        }
    }

    @Test
    void aHarFileIsNamedAsSuchUnderEitherUpload() {
        assertThat(refusal(() -> PostmanParser.environment(text("har.json"))).getMessage()).isEqualTo("This is a HAR file, not a Postman export.");
        assertThat(refusal(() -> PostmanParser.collection(text("har.json"))).getMessage()).isEqualTo("This is a HAR file, not a Postman export.");
    }

    // ------------------------------------------------------------------------------------------
    // Exports flattened by a script
    // ------------------------------------------------------------------------------------------

    @Test
    void aCollectionWhoseItemsAreTextIsRefusedWithTheDepthToSaveItWith() {
        for (String name : new String[] {"col-powershell-depth2.json", "col-powershell-depth3.json"}) {
            BadInputException refused = refusal(() -> PostmanParser.collection(text(name)));
            assertThat(refused.getMessage()).as(name).isEqualTo("The items of this collection are text, not requests: a script saved it cut "
                    + "short (PowerShell ConvertTo-Json does at its default depth).");
            assertThat(refused.action()).as(name).isEqualTo(FLATTENED_ACTION);
        }
        assertThat(FileGuard.read("col.json", () -> PostmanParser.collection(text("col-powershell-depth2.json"))).line())
                .isEqualTo("The items of this collection are text, not requests: a script saved it cut short (PowerShell ConvertTo-Json "
                        + "does at its default depth). Export it from Postman, or save it with -Depth 100.");
    }

    @Test
    void anEnvironmentWhoseValuesAreTextIsRefusedWithTheDepthToSaveItWith() {
        BadInputException refused = refusal(() -> PostmanParser.environment(text("env-powershell-depth2.json")));
        assertThat(refused.getMessage()).isEqualTo("The values of this environment are text, not variables: a script saved it cut short "
                + "(PowerShell ConvertTo-Json does at its default depth).");
        assertThat(refused.action()).isEqualTo(FLATTENED_ACTION);
    }

    @Test
    void thePlaceholderFilesThemselvesAreRead() {
        assertThat(PostmanParser.environment(PostmanParser.decode(PostmanFiles.bytes(REFUSALS + "env-placeholder.json"))).variables()).hasSize(2);
        assertThat(PostmanParser.collection(PostmanParser.decode(PostmanFiles.bytes(REFUSALS + "col-placeholder.json"))).requests()).hasSize(1);
        assertThatThrownBy(() -> PostmanParser.decode(new byte[0])).isInstanceOf(BadInputException.class).hasMessage("The uploaded file is empty.");
    }

    private static int indexOf(byte[] bytes, byte wanted) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == wanted) {
                return i;
            }
        }
        throw new AssertionError("No such byte");
    }
}
