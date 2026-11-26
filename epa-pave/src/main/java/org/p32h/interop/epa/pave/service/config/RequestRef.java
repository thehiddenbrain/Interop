package org.p32h.interop.epa.pave.service.config;

/**
 * Names one request in an uploaded collection.
 *
 * @param collectionId the id the tool gave the collection at upload
 * @param requestId    the id the tool gave the request within that collection
 */
public record RequestRef(String collectionId, String requestId) {
}
