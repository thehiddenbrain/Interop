package com.thehiddenbrain.interop.cms1500.service;

import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.contract.ClaimBundleResult;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ResultStatus;
import com.thehiddenbrain.interop.cms1500.contract.ViolationDetail;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidationException;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TestFiles;
import com.thehiddenbrain.interop.cms1500.support.TestSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimBundleServiceTest {

    @TempDir
    Path attachments;
    @TempDir
    Path output;

    @TempDir
    Path settingsDir;

    private ClaimBundleService service(Cms1500Properties.UnsupportedPolicy unsupported,
                                       Cms1500Properties.WhenNonePolicy whenNone, boolean overwrite) {
        return TestSettings.service(TestSettings.properties(attachments, output,
                settingsDir.resolve("settings.json"), unsupported, whenNone, overwrite));
    }

    @BeforeEach
    void files() throws IOException {
        TestFiles.pdf(attachments.resolve("CLM-7_1.pdf"), 2, "EOB");
        TestFiles.image(attachments.resolve("CLM-7_2.png"), "png", 600, 800);
    }

    @Test
    void generatesTheBundleAndDescribesIt() throws IOException {
        ClaimBundleService service = service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, true);
        Cms1500Claim claim = ClaimFixtures.withServiceLines(ClaimFixtures.fullClaim("CLM-7"), 8);

        ClaimBundleResult result = service.generate(claim);

        assertThat(result.getStatus()).isEqualTo(ResultStatus.GENERATED);
        assertThat(result.getClaimNumber()).isEqualTo("CLM-7");
        assertThat(result.getFileName()).isEqualTo("CLM-7.pdf");
        assertThat(result.getBundlePath()).isEqualTo(output.resolve("CLM-7.pdf").toAbsolutePath().toString());
        assertThat(result.getFormPages()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2 + 2 + 1);
        assertThat(result.getAttachmentCount()).isEqualTo(2);
        assertThat(result.getAttachments()).extracting(a -> a.getFileName()).containsExactly("CLM-7_1.pdf", "CLM-7_2.png");
        assertThat(result.getWarnings()).isEmpty();
        assertThat(result.isReplacedExisting()).isFalse();
        assertThat(result.getGeneratedAt()).isNotNull();
        try (PDDocument bundle = Loader.loadPDF(Path.of(result.getBundlePath()).toFile())) {
            assertThat(bundle.getNumberOfPages()).isEqualTo(5);
        }

        ClaimBundleResult again = service.generate(claim);
        assertThat(again.isReplacedExisting()).isTrue();
        assertThat(service.existingBundle("CLM-7")).isEqualTo(output.resolve("CLM-7.pdf").toAbsolutePath());
    }

    @Test
    void invalidClaimIsRejectedBeforeAnythingIsWritten() {
        ClaimBundleService service = service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, true);
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-7");
        claim.getBillingProvider().setNpi("123");
        assertThatThrownBy(() -> service.generate(claim))
                .isInstanceOf(ClaimValidationException.class)
                .satisfies(e -> assertThat(((ClaimException) e).getDetails()).extracting(ViolationDetail::getField)
                        .containsExactly("billingProvider.npi"));
        assertThat(output.resolve("CLM-7.pdf")).doesNotExist();
        assertThatThrownBy(() -> service.generate(null)).isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
    }

    @Test
    void unsupportedAttachmentFailsOrIsSkippedPerPolicy() throws IOException {
        TestFiles.text(attachments.resolve("CLM-7_3.docx"), "word");
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-7");

        assertThatThrownBy(() -> service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, true).generate(claim))
                .isInstanceOf(ClaimException.class).hasMessageContaining("CLM-7_3.docx")
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.UNSUPPORTED_ATTACHMENT);
        assertThat(output.resolve("CLM-7.pdf")).doesNotExist();

        ClaimBundleResult result = service(Cms1500Properties.UnsupportedPolicy.SKIP, Cms1500Properties.WhenNonePolicy.WARN, true).generate(claim);
        assertThat(result.getAttachmentCount()).isEqualTo(2);
        assertThat(result.getWarnings()).singleElement().asString().contains("CLM-7_3.docx").contains("skipped");
    }

    @Test
    void missingAttachmentsWarnOrFailPerPolicy() {
        Cms1500Claim claim = ClaimFixtures.minimalClaim("CLM-NONE");

        ClaimBundleResult result = service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, true).generate(claim);
        assertThat(result.getAttachmentCount()).isZero();
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("no attachments found"));

        assertThatThrownBy(() -> service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.FAIL, true).generate(claim))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.NO_ATTACHMENTS);
    }

    @Test
    void existingBundleIsAConflictWhenOverwriteIsOff() {
        ClaimBundleService service = service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, false);
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-7");
        service.generate(claim);
        assertThatThrownBy(() -> service.generate(claim))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.BUNDLE_EXISTS);
    }

    @Test
    void bundleLookupRejectsUnknownAndUnsafeClaimNumbers() throws IOException {
        ClaimBundleService service = service(Cms1500Properties.UnsupportedPolicy.FAIL, Cms1500Properties.WhenNonePolicy.WARN, true);
        assertThatThrownBy(() -> service.existingBundle("NOPE"))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.BUNDLE_NOT_FOUND);
        Files.writeString(output.resolve("secret.pdf"), "x");
        assertThatThrownBy(() -> service.existingBundle("../" + output.getFileName() + "/secret"))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
