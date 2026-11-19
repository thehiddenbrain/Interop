package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether tracking is alive. Shown permanently on screen, because the failure that matters is
 * tracking having silently stopped.
 *
 * @param enabled         false when tracking is switched off in configuration
 * @param writable        false when the directory cannot be written to
 * @param runsToday       runs in today's file
 * @param unreadableLines lines in the files read so far that could not be parsed
 * @param lastError       the most recent write failure, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerHealth(boolean enabled, String directory, boolean writable, String currentFile, int runsToday,
                           int unreadableLines, String lastWriteAt, String lastError) {
}
