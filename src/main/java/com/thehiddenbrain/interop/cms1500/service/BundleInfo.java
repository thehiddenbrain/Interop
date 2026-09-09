package com.thehiddenbrain.interop.cms1500.service;

import java.time.OffsetDateTime;

/** A bundle that exists in the output folder. */
public record BundleInfo(String claimNumber, String fileName, long sizeBytes, OffsetDateTime modifiedAt) {
}
