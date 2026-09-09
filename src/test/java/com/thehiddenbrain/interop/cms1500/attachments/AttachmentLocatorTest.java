package com.thehiddenbrain.interop.cms1500.attachments;

import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentLocatorTest {

    private final AttachmentLocator locator = new AttachmentLocator();

    @Test
    void findsFilesByClaimNumberAndSequenceInNumericOrder(@TempDir Path dir) throws IOException {
        for (String name : List.of("CLM1_1.pdf", "CLM1_2.PNG", "clm1_10.pdf", "CLM1_3_EOB.pdf", "CLM1_5.docx",
                "CLM10_1.pdf", "CLM1.pdf", "CLM1_x.pdf", ".CLM1_4.pdf", "OTHER_1.pdf", "CLM1_7.notes.tif")) {
            Files.writeString(dir.resolve(name), "x");
        }
        Files.createDirectories(dir.resolve("CLM1_6.pdf"));

        List<Attachment> found = locator.locate(dir, "CLM1");

        assertThat(found).extracting(Attachment::index).containsExactly(1, 2, 3, 5, 7, 10);
        assertThat(found).extracting(Attachment::fileName)
                .containsExactly("CLM1_1.pdf", "CLM1_2.PNG", "CLM1_3_EOB.pdf", "CLM1_5.docx", "CLM1_7.notes.tif", "clm1_10.pdf");
        assertThat(found).extracting(Attachment::extension).containsExactly("pdf", "png", "pdf", "docx", "tif", "pdf");
        assertThat(found.get(0).isPdf()).isTrue();
        assertThat(found.get(1).isPdf()).isFalse();
        assertThat(found).allSatisfy(a -> assertThat(a.path()).isAbsolute());
    }

    @Test
    void matchesCaseInsensitively(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("abc-9_1.PDF"), "x");
        assertThat(locator.locate(dir, "ABC-9")).extracting(Attachment::index).containsExactly(1);
    }

    @Test
    void emptyWhenNothingMatches(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("CLM2_1.pdf"), "x");
        assertThat(locator.locate(dir, "CLM1")).isEmpty();
    }

    @Test
    void missingFolderIsAStorageError(@TempDir Path dir) {
        assertThatThrownBy(() -> locator.locate(dir.resolve("nope"), "CLM1"))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    void rejectsUnsafeClaimNumbers(@TempDir Path dir) {
        assertThatThrownBy(() -> locator.locate(dir, "../CLM1"))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
