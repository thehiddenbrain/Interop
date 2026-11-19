package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether tracking is alive. Shown permanently on screen, because the failure that matters is
 * tracking having silently stopped.
 *
 * @param enabled         false when tracking is switched off in configuration
 * @param writable        false when the directory cannot be written to
 * @param runsToday       runs in today's file. Null when the file cannot be read, so that "cannot say" is not shown as
 *                        none
 * @param unreadableLines lines in the files read so far that could not be parsed
 * @param lastWriteAt     when a run was last written successfully, or null when none has been
 * @param lastError       the most recent write failure, or null when there has been none since
 *                        startup. It is kept after a later write succeeds: a run that went
 *                        unrecorded an hour ago is still a fact the tester needs
 * @param lastErrorAt     when that failure happened, so it can be ordered against {@code lastWriteAt}
 * @param failedWrites    how many writes have failed since startup
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerHealth(boolean enabled, String directory, boolean writable, String currentFile, Integer runsToday,
                           int unreadableLines, String lastWriteAt, String lastError, String lastErrorAt, int failedWrites) {
}
