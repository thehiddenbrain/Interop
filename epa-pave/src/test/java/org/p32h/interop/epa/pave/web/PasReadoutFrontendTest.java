package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void afterAPlayedFaultTheWayToTheInquiryIsPartOfTheRehearsalAndIsLeftOffAPlayedUnreachable() throws IOException {
        String show = CaseCodesFrontendTest.body(read("tab-pas.js"), "function showRun(envelope) {");
        assertThat(show).contains("const played = envelope.answeredBy === 'SCENARIO';")
                .contains("&& !(played && unreached)) {")
                .contains("'Had this been a real service, Send inquiry would check whether the payer has it. It is filled in the form with the '")
                .contains("'Send inquiry, in the form, is already filled with the claim identifier of this submission. '");
    }
}
