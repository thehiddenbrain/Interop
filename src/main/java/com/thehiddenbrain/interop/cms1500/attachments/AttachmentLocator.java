package com.thehiddenbrain.interop.cms1500.attachments;

import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.domain.Patterns;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a claim's attachments in the shared-drive folder by the naming convention
 * {@code <claimNumber>_<n>.<ext>} (an optional description may follow the number, e.g.
 * {@code CLM123_2_EOB.pdf}). Matching ignores case; results are ordered by {@code n}, then name.
 * Files of other claims, including ones whose number merely starts with this claim number
 * (CLM12 vs CLM123), never match.
 */
@Component
public class AttachmentLocator {

    /** Every file that follows the convention, whatever its extension (the caller applies the policy). */
    public List<Attachment> locate(Path root, String claimNumber) {
        if (!Patterns.CLAIM_NUMBER.matcher(claimNumber).matches()) {
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, claimNumber, "invalid claim number");
        }
        Path dir = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, claimNumber,
                    "attachments folder does not exist or is not a directory: " + dir);
        }
        Pattern pattern = Pattern.compile(
                "(?i)" + Pattern.quote(claimNumber) + "_(\\d{1,6})(?:[-_ .].*)?\\.([A-Za-z0-9]+)");
        List<Attachment> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) {
                    return;
                }
                Matcher m = pattern.matcher(name);
                if (!m.matches() || !file.toAbsolutePath().normalize().startsWith(dir)) {
                    return;
                }
                found.add(new Attachment(Integer.parseInt(m.group(1)), file.toAbsolutePath().normalize(),
                        m.group(2).toLowerCase(Locale.ROOT)));
            });
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, claimNumber,
                    "cannot list attachments folder " + dir + ": " + e.getMessage(), e);
        }
        found.sort(Comparator.comparingInt(Attachment::index).thenComparing(Attachment::fileName,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(found);
    }
}
