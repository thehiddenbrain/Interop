package com.thehiddenbrain.interop.cms1500.service;

/** A file in the attachments folder that follows the claim's naming convention. */
public record AttachmentInfo(int index, String fileName, String extension, String type, boolean supported, long sizeBytes) {
}
