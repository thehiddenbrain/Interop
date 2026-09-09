package com.thehiddenbrain.interop.cms1500.attachments;

import java.nio.file.Path;

/**
 * A file on the shared drive that belongs to a claim.
 *
 * @param index     the sequence number from the file name ({@code <claimNumber>_<index>.<ext>})
 * @param path      absolute path of the file
 * @param extension lower-case extension without the dot
 */
public record Attachment(int index, Path path, String extension) {

    public String fileName() {
        return path.getFileName().toString();
    }

    public boolean isPdf() {
        return "pdf".equals(extension);
    }
}
