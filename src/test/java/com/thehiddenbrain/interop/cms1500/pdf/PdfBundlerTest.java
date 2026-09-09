package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.attachments.Attachment;
import com.thehiddenbrain.interop.cms1500.contract.AttachmentType;
import com.thehiddenbrain.interop.cms1500.contract.BundledAttachment;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TestFiles;
import com.thehiddenbrain.interop.cms1500.support.TestTemplate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBundlerTest {

    private final PdfBundler bundler = new PdfBundler();

    @Test
    void mergesPdfsAndRendersImagesBehindTheForm(@TempDir Path dir) throws IOException {
        List<Attachment> attachments = List.of(
                new Attachment(1, TestFiles.pdf(dir.resolve("C_1.pdf"), 2, "EOB"), "pdf"),
                new Attachment(2, TestFiles.image(dir.resolve("C_2.png"), "png", 800, 1000), "png"),
                new Attachment(3, TestFiles.image(dir.resolve("C_3.jpg"), "jpg", 1200, 600), "jpg"),
                new Attachment(4, TestFiles.multiPageTiff(dir.resolve("C_4.tif"), 3), "tif"),
                new Attachment(5, TestFiles.image(dir.resolve("C_5.bmp"), "bmp", 300, 300), "bmp"),
                new Attachment(6, TestFiles.image(dir.resolve("C_6.gif"), "gif", 300, 400), "gif"));
        Path target = dir.resolve("out").resolve("C.pdf");

        PdfBundler.Outcome outcome;
        try (PDDocument form = TestTemplate.filler().fill(ClaimFixtures.fullClaim("C"))) {
            outcome = bundler.bundle("C", form, attachments, target, true);
        }

        assertThat(outcome.formPages()).isEqualTo(1);
        assertThat(outcome.totalPages()).isEqualTo(1 + 2 + 1 + 1 + 3 + 1 + 1);
        assertThat(outcome.attachments()).extracting(BundledAttachment::getIndex).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(outcome.attachments()).extracting(BundledAttachment::getPages).containsExactly(2, 1, 1, 3, 1, 1);
        assertThat(outcome.attachments()).extracting(BundledAttachment::getType)
                .containsExactly(AttachmentType.PDF, AttachmentType.IMAGE, AttachmentType.IMAGE, AttachmentType.IMAGE,
                        AttachmentType.IMAGE, AttachmentType.IMAGE);
        assertThat(outcome.attachments().get(0).getFileName()).isEqualTo("C_1.pdf");

        try (PDDocument bundle = Loader.loadPDF(target.toFile())) {
            assertThat(bundle.getNumberOfPages()).isEqualTo(outcome.totalPages());
            assertThat(bundle.getDocumentInformation().getTitle()).isEqualTo("Claim C");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            assertThat(stripper.getText(bundle)).contains("DOE, JOHN A");
            stripper.setStartPage(3);
            stripper.setEndPage(3);
            assertThat(stripper.getText(bundle)).contains("EOB page 2");
            // landscape jpeg gets a landscape page; portrait png a portrait page
            assertThat(bundle.getPage(3).getMediaBox().getWidth()).isLessThan(bundle.getPage(3).getMediaBox().getHeight());
            assertThat(bundle.getPage(4).getMediaBox().getWidth()).isGreaterThan(bundle.getPage(4).getMediaBox().getHeight());
        }
        assertThat(Files.list(target.getParent()).map(p -> p.getFileName().toString()))
                .as("no temp files left behind").containsExactly("C.pdf");
    }

    @Test
    void bundleWithoutAttachmentsIsJustTheForm(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("C.pdf");
        try (PDDocument form = TestTemplate.filler().fill(ClaimFixtures.minimalClaim("C"))) {
            PdfBundler.Outcome outcome = bundler.bundle("C", form, List.of(), target, true);
            assertThat(outcome.totalPages()).isEqualTo(1);
            assertThat(outcome.attachments()).isEmpty();
        }
        assertThat(target).exists();
    }

    @Test
    void passwordProtectedAttachmentIsRejected(@TempDir Path dir) throws IOException {
        Attachment locked = new Attachment(1, TestFiles.encryptedPdf(dir.resolve("C_1.pdf")), "pdf");
        try (PDDocument form = TestTemplate.filler().fill(ClaimFixtures.minimalClaim("C"))) {
            assertThatThrownBy(() -> bundler.bundle("C", form, List.of(locked), dir.resolve("C.pdf"), true))
                    .isInstanceOf(ClaimException.class)
                    .hasMessageContaining("C_1.pdf")
                    .hasMessageContaining("password")
                    .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.ATTACHMENT_UNREADABLE);
        }
        assertThat(dir.resolve("C.pdf")).doesNotExist();
    }

    @Test
    void corruptAttachmentIsRejected(@TempDir Path dir) throws IOException {
        Attachment fakePdf = new Attachment(1, TestFiles.text(dir.resolve("C_1.pdf"), "not a pdf"), "pdf");
        Attachment fakePng = new Attachment(2, TestFiles.text(dir.resolve("C_2.png"), "not a png"), "png");
        try (PDDocument form = TestTemplate.filler().fill(ClaimFixtures.minimalClaim("C"))) {
            assertThatThrownBy(() -> bundler.bundle("C", form, List.of(fakePdf), dir.resolve("C.pdf"), true))
                    .isInstanceOf(ClaimException.class).hasMessageContaining("C_1.pdf")
                    .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.ATTACHMENT_UNREADABLE);
            assertThatThrownBy(() -> bundler.bundle("C", form, List.of(fakePng), dir.resolve("C.pdf"), true))
                    .isInstanceOf(ClaimException.class).hasMessageContaining("C_2.png")
                    .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.ATTACHMENT_UNREADABLE);
        }
    }

    @Test
    void existingBundleIsReplacedOnlyWhenOverwriteIsOn(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("C.pdf");
        Files.writeString(target, "old");
        try (PDDocument form = TestTemplate.filler().fill(ClaimFixtures.minimalClaim("C"))) {
            assertThatThrownBy(() -> bundler.bundle("C", form, List.of(), target, false))
                    .isInstanceOf(ClaimException.class)
                    .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.BUNDLE_EXISTS);
            assertThat(Files.readString(target)).isEqualTo("old");

            bundler.bundle("C", form, List.of(), target, true);
        }
        try (PDDocument replaced = Loader.loadPDF(target.toFile())) {
            assertThat(replaced.getNumberOfPages()).isEqualTo(1);
        }
    }
}
