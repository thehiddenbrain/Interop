package org.p32h.interop.epa.pave.service.pas;

/**
 * What the PAS tab posts to have a sample notification delivered, on the mock profile only.
 *
 * <p>On mock nothing calls the receiver, so this is how a tester sees a notification arrive: the
 * notification a scenario file holds is made to be about a submission the tester names and is
 * handed to the same receiving code a real one reaches.
 *
 * @param scenarioId     the scenario whose notification to deliver. Blank uses the scenario that
 *                       answered the run that is named
 * @param runId          the submission the notification should be about. Needed only for a
 *                       notification that is about a request: a handshake and a heartbeat are not
 * @param subscriptionId the subscription it should arrive on. Blank uses the one this tool created
 *                       most recently
 */
public record NotificationSampleRequest(String scenarioId, String runId, String subscriptionId) {
}
