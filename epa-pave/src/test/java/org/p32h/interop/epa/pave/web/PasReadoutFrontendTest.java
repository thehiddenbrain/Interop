package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * On the PAS tab the verdict comes first and one screen gives one next step: the receiver's list
 * is in the readout only while its call is chosen, an arrival is still announced whichever call
 * is chosen, and a result is marked when a notification about its run arrived after it or when
 * another call is chosen. The build has no browser, so these read the scripts; the states were
 * driven in a real browser on mock.
 */
class PasReadoutFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theReceiversListIsInTheReadoutOnlyWhileItsCallIsChosen() throws IOException {
        String pas = read("tab-pas.js");
        String showMode = CaseCodesFrontendTest.body(pas, "function showMode(id) {");
        assertThat(showMode).contains("slots.notifications.hidden = id !== 'notifications';").contains("markOther();");
        assertThat(CaseCodesFrontendTest.body(pas, "function markOther() {")).contains("result__other")
                .contains("'This result is of another call than the one now chosen. Sending the chosen call replaces it.'")
                .contains("mark.remove();");
    }

    @Test
    void anArrivalIsAnnouncedOutsideThePanelThatIsHidden() throws IOException {
        String notifications = read("pas-notifications.js");
        String returned = notifications.substring(notifications.lastIndexOf("    return {\n        node,"));
        assertThat(returned).contains("statusNode: status,").contains("onArrived(callback) {");
        String panel = notifications.substring(notifications.indexOf("const body = el('details', {class: 'pas-notifications__body'},"),
                notifications.indexOf("const node = el('section', {class: 'pas-notifications'"));
        assertThat(panel).doesNotContain("status)").doesNotContain("status,");
        String pas = read("tab-pas.js");
        assertThat(pas).contains("controls.modeChoice.after(notifications.statusNode);").doesNotContain("slots.notifications.append(notifications.statusNode")
                .contains("notifications.onArrived(markNewer);");
    }

    @Test
    void aResultIsMarkedOnlyByANotificationAboutItsOwnRunAndInTheToolsOwnWords() throws IOException {
        String newer = CaseCodesFrontendTest.body(read("tab-pas.js"), "function markNewer(items) {");
        assertThat(newer).contains("item.correlatedRunId === shownRunId").contains("result__newer")
                .contains("was given before it").doesNotContain("item.nextStep").doesNotContain("item.verdict");
    }

    @Test
    void afterAPlayedFaultTheWayToTheInquiryIsPartOfTheRehearsal() throws IOException {
        String show = CaseCodesFrontendTest.body(read("tab-pas.js"), "function showRun(envelope) {");
        assertThat(show).contains("const played = envelope.answeredBy === 'SCENARIO';")
                .contains("if (row && offersInquiryWay(envelope, step, hasClaim)) {")
                .contains("'Had this been a real service, Send inquiry would check whether the payer has it. It is filled in the form with the '")
                .contains("'Send inquiry, in the form, is already filled with the claim identifier of this submission. '");
    }

    /**
     * A submission that did not reach the service offers no inquiry, on live as on mock: nothing was
     * submitted, and the server's step hands on no claim identifier for it. One whose connection broke
     * after it opened is UNREACHABLE too, may have arrived, and keeps the way to the inquiry. Run with
     * Node, so a condition that drops the way for every UNREACHABLE, or only for a played one, turns
     * this red. The browser decides from whether a claim is handed on (hasClaim), so the condition
     * itself now lives in the server's carry: PasVerdictsTest and PasServiceTest hold it for a live
     * submit, and PlayedFaultNextStepTest for the played P32H-PAS-DOWN.
     */
    @Test
    void aSubmissionThatDidNotReachTheServiceOffersNoInquiryPlayedOrLiveWhenNodeRunsIt() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the condition cannot be run; the text checks still apply.");
        String function = CaseCodesFrontendTest.body(read("tab-pas.js"), "export function offersInquiryWay(envelope, step, hasClaim) {")
                .replaceFirst("^export ", "") + "\n}";
        String check = "{action: 'CHECK_ENVIRONMENT'}";
        String cases = String.join(",\n",
                // Cannot have reached the service, live and played: the server hands on no claim identifier, so no inquiry.
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE', verdict: {outcomeCode: 'UNREACHABLE'}}, " + check + ", false]",
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SCENARIO', verdict: {outcomeCode: 'UNREACHABLE'}}, " + check + ", false]",
                // The connection broke after it opened, live and played: UNREACHABLE, may have arrived, the claim is handed on.
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE', verdict: {outcomeCode: 'UNREACHABLE'}}, " + check + ", true]",
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SCENARIO', verdict: {outcomeCode: 'UNREACHABLE'}}, " + check + ", true]",
                // A failure or a timeout, live and played: the submission may have arrived, so the inquiry is offered.
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE', verdict: {outcomeCode: 'SERVICE_ERROR'}}, " + check + ", true]",
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SCENARIO', verdict: {outcomeCode: 'TIMEOUT'}}, " + check + ", true]",
                // No claim identifier, another step, an inquiry: none.
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE', verdict: {outcomeCode: 'SERVICE_ERROR'}}, " + check + ", false]",
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE', verdict: {outcomeCode: 'PENDED'}}, {action: 'CHECK_STATUS'}, true]",
                "[{operation: 'PAS_INQUIRE', answeredBy: 'SERVICE', verdict: {outcomeCode: 'TIMEOUT'}}, " + check + ", true]",
                "[{operation: 'PAS_SUBMIT', answeredBy: 'SERVICE'}, " + check + ", true]");
        String script = function + "\nconsole.log(JSON.stringify([" + cases + "].map(c => offersInquiryWay(c[0], c[1], c[2]))));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[false,false,true,true,true,true,false,false,false,true]");
    }

    @Test
    void onMockTheStepAfterACreateLeadsToTheNotificationsCall() throws IOException {
        String show = CaseCodesFrontendTest.body(read("tab-pas.js"), "function showRun(envelope) {");
        assertThat(show).contains("if ((envelope.operation === 'SUBSCRIPTION_CREATE' || envelope.operation === 'SUBSCRIPTION_UPDATE') && envelope.answeredBy === 'SCENARIO'")
                .contains("&& shownStep.action === 'DELIVER_SAMPLE_NOTIFICATION') {")
                .contains("return {label: 'Choose the Notifications call', run: () => showMode('notifications')};");
    }

    @Test
    void aMarkAddedToTheResultIsScrolledIntoViewAndOffersNoCallAlreadyChosen() throws IOException {
        String pas = read("tab-pas.js");
        assertThat(CaseCodesFrontendTest.body(pas, "function markOther() {")).contains("scrollReadoutTop();");
        assertThat(CaseCodesFrontendTest.body(pas, "function showMode(id) {"))
                .contains("if (readoutResult().querySelector('.result__newer, .result__other')) {\n        scrollReadoutTop();");
        String newer = CaseCodesFrontendTest.body(pas, "function markNewer(items) {");
        assertThat(newer).contains("{class: 'button--text result__newer-go', hidden: mode === 'notifications'}").contains("scrollReadoutTop();");
        assertThat(CaseCodesFrontendTest.body(pas, "function showMode(id) {")).contains("go.hidden = id === 'notifications';");
        assertThat(read("pas-notifications.js")).contains("headline.startsWith('A notification arrived') ? headline : 'A notification arrived: ' + headline");
    }
}
