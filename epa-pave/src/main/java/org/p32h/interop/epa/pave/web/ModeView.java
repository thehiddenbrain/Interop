package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.service.config.WriteAccess;

/**
 * What {@code GET /api/mode} answers: everything the environment strip and the production banner
 * are drawn from, in one cheap call every tab can poll.
 *
 * @param name        the tool name
 * @param mode        mock or live. The badge shows this, and startup refuses a badge that
 *                    disagrees with the wiring
 * @param target      onyx or nehen
 * @param environment the active environment. Its {@code active} flag is false when there is none
 * @param epoch       the configuration epoch. A tab sends this back with each run as
 *                    {@code configEpoch}, and a run is refused if the configuration has changed
 *                    since, so a tab still showing a sandbox cannot send to whatever another tab
 *                    activated
 * @param writeAccess whether a write would be sent right now for this browser session, and why
 * @param bindAddress the address the server listens on, as configured in {@code server.address}.
 *                    Absent when none is configured, and the server then listens on every address
 *                    of this machine
 * @param bindNote    what that means for a service on another machine that would call the
 *                    notification receiver, in one sentence a business tester can act on. Always set
 * @param build       which build is running, for the stamp in the top bar, and whether its files
 *                    changed on disk since it started (R-24, R-31). Always set
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModeView(String name, String version, int port, String mode, String target, EnvironmentRef environment, long epoch,
                       WriteAccess writeAccess, String bindAddress, String bindNote, Build build) {

    /**
     * The build this copy of PAVE started from.
     *
     * @param version       the version application.yml names
     * @param time          when the build was made, as an ISO instant: the jar's time, or the newest
     *                      class file's when PAVE runs from a classes folder. Absent when unknown,
     *                      never a guess
     * @param changedOnDisk true when the classes or the page's files were rebuilt on disk while this
     *                      copy ran, so it keeps old classes under new pages until it is started again
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Build(String version, String time, boolean changedOnDisk) {
    }
}
