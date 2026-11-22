package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.LookupField;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.ScenarioSummary;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.pas.NotificationList;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LedgerHealth;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.service.config.ConfigViews;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * The screens read the words and values the server serves, and keep no copy of them. Two things
 * break that without any test of the server failing: the server renames a field a script reads,
 * and the script then shows nothing; or a page's own copy of the server's words comes back.
 * <ul>
 *   <li>every field the frame and the shared modules read is a property of the record the server
 *       answers with, and the script still reads it</li>
 *   <li>the page's own copies that were removed do not come back in any script</li>
 *   <li>the short words (GET /api/vocabulary) are read once for each configuration epoch, and a
 *       failed read is not kept, so a view never draws from a stale or missing copy</li>
 * </ul>
 * The views add their own reads to this list as they are built.
 */
class StaticServerWordsTest {

    /** One field a script reads: the script, the text of the read, the record, and the property. */
    private record Read(String script, String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("app.js", "mode.name", ModeView.class, "name"),
            new Read("app.js", "mode.version", ModeView.class, "version"),
            new Read("app.js", "mode.mode", ModeView.class, "mode"),
            new Read("app.js", "state.mode.epoch", ModeView.class, "epoch"),
            new Read("app.js", "state.mode.environment", ModeView.class, "environment"),
            new Read("app.js", "state.mode.writeAccess", ModeView.class, "writeAccess"),
            new Read("app.js", "environment.active", EnvironmentRef.class, "active"),
            new Read("app.js", "environment.name", EnvironmentRef.class, "name"),
            new Read("app.js", "environment.kind", EnvironmentRef.class, "kind"),
            new Read("app.js", "access.production", WriteAccess.class, "production"),
            new Read("app.js", "access.writesAllowed", WriteAccess.class, "writesAllowed"),
            new Read("app.js", "access.reason", WriteAccess.class, "reason"),
            new Read("app.js", "health.enabled", LedgerHealth.class, "enabled"),
            new Read("app.js", "health.writable", LedgerHealth.class, "writable"),
            new Read("app.js", "health.runsToday", LedgerHealth.class, "runsToday"),
            new Read("app.js", "health.failedWrites", LedgerHealth.class, "failedWrites"),
            new Read("app.js", "health.lastError", LedgerHealth.class, "lastError"),
            new Read("app.js", "state.environments.environments", ConfigViews.EnvironmentList.class, "environments"),
            new Read("app.js", "each.id", ConfigViews.EnvironmentView.class, "id"),
            new Read("app.js", "each.name", ConfigViews.EnvironmentView.class, "name"),
            new Read("app.js", "answer.data.addressWarnings", ConfigViews.Activated.class, "addressWarnings"),
            new Read("app.js", "answer.data.epoch", ConfigViews.Activated.class, "epoch"),
            new Read("app.js", "answer.error.title", ErrorView.class, "title"),
            new Read("app.js", "answer.error.action", ErrorView.class, "action"),
            new Read("fault.js", "error.heading", ErrorView.class, "heading"),
            new Read("fault.js", "error.title", ErrorView.class, "title"),
            new Read("fault.js", "error.action", ErrorView.class, "action"),
            new Read("fault.js", "error.httpStatus", ErrorView.class, "httpStatus"),
            new Read("fault.js", "error.fieldErrors", ErrorView.class, "fieldErrors"),
            new Read("fault.js", "error.issues", ErrorView.class, "issues"),
            new Read("fault.js", "error.reference", ErrorView.class, "reference"),
            new Read("fault.js", "error.cause", ErrorView.class, "cause"),
            new Read("fault.js", "each.field", FieldError.class, "field"),
            new Read("fault.js", "each.message", FieldError.class, "message"),
            new Read("fault.js", "issue.severity", IssueView.class, "severity"),
            new Read("fault.js", "issue.code", IssueView.class, "code"),
            new Read("fault.js", "issue.diagnostics", IssueView.class, "diagnostics"),
            new Read("fault.js", "issue.detailsText", IssueView.class, "detailsText"),
            new Read("fault.js", "issue.detailsCodes", IssueView.class, "detailsCodes"),
            new Read("fault.js", "issue.detailsPresence", IssueView.class, "detailsPresence"),
            new Read("fault.js", "issue.expression", IssueView.class, "expression"),
            new Read("fault.js", "issue.expressionPresence", IssueView.class, "expressionPresence"),
            new Read("fault.js", "issue.location", IssueView.class, "location"),
            new Read("fault.js", "issue.locationPresence", IssueView.class, "locationPresence"),
            new Read("fault.js", "value.presence", Shown.class, "presence"),
            new Read("fault.js", "value.value", Shown.class, "value"),
            new Read("result.js", "verdict.headline", VerdictView.class, "headline"),
            new Read("result.js", "verdict.maskedReading", VerdictView.class, "maskedReading"),
            new Read("result.js", "verdict.supporting", VerdictView.class, "supporting"),
            new Read("result.js", "envelope.answeredBy", RunEnvelope.class, "answeredBy"),
            new Read("result.js", "envelope.determination", RunEnvelope.class, "determination"),
            new Read("result.js", "envelope.additionalFields", RunEnvelope.class, "additionalFields"),
            new Read("result.js", "expectation.expectedLabel", ExpectationView.class, "expectedLabel"),
            new Read("result.js", "expectation.explanation", ExpectationView.class, "explanation"),
            new Read("result.js", "timing.slow", Timing.class, "slow"),
            new Read("result.js", "ledger.recorded", LedgerReceipt.class, "recorded"),
            new Read("result.js", "ledger.message", LedgerReceipt.class, "message"),
            new Read("result.js", "step.headline", NextStepView.class, "headline"),
            new Read("result.js", "step.reason", NextStepView.class, "reason"),
            new Read("result.js", "forms.sent", MemberIdForms.class, "sent"),
            new Read("result.js", "forms.rule", MemberIdForms.class, "rule"),
            new Read("result.js", "forms.note", MemberIdForms.class, "note"),
            new Read("result.js", "row.display", DeterminationRow.class, "display"),
            new Read("result.js", "row.recognised", DeterminationRow.class, "recognised"),
            new Read("result.js", "coverage.delegatedVendor", CoverageInformationView.class, "delegatedVendor"),
            new Read("result.js", "decision.procedureCode", PasDecision.class, "procedureCode"),
            new Read("result.js", "decision.outcome", PasDecision.class, "outcome"),
            new Read("wb-lookup.js", "answer.statusLabel", MemberLookupResult.class, "statusLabel"),
            new Read("wb-lookup.js", "answer.statusLabel", ProviderLookupResult.class, "statusLabel"),
            new Read("wb-lookup.js", "answer.attention", MemberLookupResult.class, "attention"),
            new Read("wb-lookup.js", "answer.maskedReading", MemberLookupResult.class, "maskedReading"),
            new Read("wb-lookup.js", "field.sourceField", LookupField.class, "sourceField"),
            new Read("wb-member.js", "answer.memberId", MemberLookupResult.class, "memberId"),
            new Read("wb-member.js", "scenario.sentinelMemberId", ScenarioSummary.class, "sentinelMemberId"),
            new Read("wb-member.js", "scenario.sampleCase", ScenarioSummary.class, "sampleCase"),
            new Read("wb-preview.js", "preview.blocked", RequestPreview.class, "blocked"),
            new Read("wb-preview.js", "preview.notes", RequestPreview.class, "notes"),
            new Read("wb-preview.js", "preview.toComplete", RequestPreview.class, "toComplete"),
            new Read("wb-preview.js", "url.unresolved", ResolvedUrl.class, "unresolved"),
            new Read("wb-preview.js", "url.source", ResolvedUrl.class, "source"),
            new Read("app.js", "mode.build.version", ModeView.Build.class, "version"),
            new Read("app.js", "mode.build.time", ModeView.Build.class, "time"),
            new Read("app.js", "mode.build.changedOnDisk", ModeView.Build.class, "changedOnDisk"),
            new Read("app.js", "mode.build", ModeView.class, "build"),
            new Read("app.js", "mode.signIn", ModeView.class, "signIn"),
            // The chip's words: the record names the component shortWords and sends it as "short".
            new Read("app.js", "signIn.short", ModeView.SignIn.class, "shortWords"),
            new Read("app.js", "signIn.state", ModeView.SignIn.class, "state"),
            new Read("app.js", "signIn.detail", ModeView.SignIn.class, "detail"),
            new Read("wb-status.js", "mode.bindAddress", ModeView.class, "bindAddress"),
            new Read("wb-status.js", "mode.bindNote", ModeView.class, "bindNote"),
            new Read("wb-status.js", "answer.data.latest", NotificationList.class, "latest"),
            new Read("wb-status.js", "got.notifications", NotificationList.class, "notifications"),
            new Read("wb-status.js", "got.held", NotificationList.class, "held"),
            new Read("wb-status.js", "counts.received", NotificationList.class, "received"),
            new Read("wb-status.js", "item.receivedAt", NotificationView.class, "receivedAt"),
            new Read("wb-status.js", "item.correlatedRunId", NotificationView.class, "correlatedRunId"),
            new Read("wb-status.js", "item.faultReference", NotificationView.class, "faultReference"),
            new Read("wb-status.js", "item.faultCause", NotificationView.class, "faultCause"),
            new Read("wb-status.js", "item.payload", NotificationView.class, "payload"),
            new Read("wb-status.js", "item.bytesReceived", NotificationView.class, "bytesReceived"),
            new Read("wb-status.js", "item.verdict", NotificationView.class, "verdict"),
            new Read("wb-status.js", "item.nextStep", NotificationView.class, "nextStep"),
            new Read("wb-status.js", "newest.id", NotificationView.class, "id"),
            new Read("wb-status.js", "envelope.protocol", RunEnvelope.class, "protocol"),
            new Read("wb-status.js", "envelope.ledger", RunEnvelope.class, "ledger"),
            new Read("wb-status.js", "step.carry", NextStepView.class, "carry"));

    /** A copy the page kept of the server's words, which the server now serves: where to look, the pattern that finds it, and why it must not come back. */
    private record Copy(String script, String pattern, String why) {
    }

    private static final List<Copy> REMOVED = List.of(
            new Copy("*", "\\bFINDING_GROUPS\\b", "finding groups are Finding.group and groupLabel, and ScenarioList.findingKinds"),
            new Copy("*", "\\bfindingGroup\\s*\\(", "a finding's heading is its groupLabel"),
            new Copy("*", "\\bSTARTS_CLOSED\\b", "a comparison group says whether it starts open (GroupCount.startsOpen)"),
            new Copy("*", "\\bheldBefore\\b", "a dropped answer carries its values (QuestionRef.values)"),
            new Copy("*", "'Complete the response'|'Send answers and get the next question'",
                    "the DTR step buttons are named by the server's next actions, SUBMIT_RESPONSE and ANSWER_NEXT_QUESTION"),
            new Copy("*", "the bundled placeholder, not a P32H list",
                    "a list that says it is synthetic can be the sample or a tester's own: the label says placeholder and the self-check's source"),
            new Copy("*", "Locking ends the unlock|locks writes for every browser session at once|No unlock is possible|an owner has locked this installation",
                    "what the guardrail allows and why is said by the server, in WriteAccess.reason, shown whole"),
            new Copy("*", "\\bERROR_KINDS\\b|A fault in the environment:",
                    "an error's heading is ErrorView.heading, which says a timeout a mock scenario played is not a fault in the environment"),
            new Copy("*", "What went wrong",
                    "a run's error is headed by ErrorView.heading: a refusal by the guardrail is not something that went wrong"));

    @Test
    void everyFieldAScriptReadsInPlaceOfItsOwnWordsIsOnTheRecordTheServerSends() throws IOException {
        for (Read read : READS) {
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which " + read.script() + " reads")
                    .isTrue();
            assertThat(StaticSources.script(read.script())).as(read.script() + " still reads " + read.text()).contains(read.text());
        }
    }

    @Test
    void noCopyOfTheServersWordsThatWasRemovedComesBack() throws IOException {
        List<String> found = new ArrayList<>();
        for (Copy copy : REMOVED) {
            Pattern pattern = Pattern.compile(copy.pattern());
            for (Source script : StaticSources.sources(".js")) {
                if (("*".equals(copy.script()) || script.name().equals("js/" + copy.script())) && pattern.matcher(script.text()).find()) {
                    found.add(script.name() + ": " + copy.pattern() + " (" + copy.why() + ")");
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void aPropertyTheRecordDoesNotHaveIsCaught() {
        assertThat(hasProperty(WriteAccess.class, "reason")).isTrue();
        assertThat(hasProperty(WriteAccess.class, "reasons")).isFalse();
        assertThat(hasProperty(EnvironmentRef.class, "active")).isTrue();
    }

    @Test
    void aRemovedCopyIsFoundWhenItIsThere() {
        Pattern pattern = Pattern.compile(REMOVED.get(0).pattern());
        assertThat(pattern.matcher("import {FINDING_GROUPS} from './marks.js';").find()).isTrue();
        assertThat(pattern.matcher("import {findingGroupsOf} from './marks.js';").find()).isFalse();
        Pattern guardrail = Pattern.compile(REMOVED.get(6).pattern());
        assertThat(guardrail.matcher("el('p', null, 'No unlock is possible.')").find()).isTrue();
        assertThat(guardrail.matcher("el('span', {class: 'line__reason'}, access.reason)").find()).isFalse();
        assertThatThrownBy(() -> StaticSources.script("no-such-script.js")).isInstanceOf(IOException.class);
    }

    /** words.js run with Node against a stand-in for the server: what is read, how often, and what is kept. */
    @Test
    void theShortWordsAreReadOncePerEpochAndAFailedReadIsNotKept(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the short words", """
                import {vocabulary, useEpoch} from './js/words.js';
                const say = value => console.log(JSON.stringify(value));
                let epoch = null;
                let calls = 0;
                let answer = 'json';
                globalThis.fetch = async address => {
                    calls++;
                    if (answer === 'html') {
                        return new Response('<html><body>Error</body></html>', {status: 500, headers: {'Content-Type': 'text/html'}});
                    }
                    if (answer === 'missing') {
                        return new Response(JSON.stringify({kind: 'NOT_FOUND', title: 'Nothing is at this address.'}), {status: 404});
                    }
                    return new Response(JSON.stringify({address, steps: [{step: 'COVERAGE', name: 'Coverage check', protocol: 'CRD'}]}), {status: 200});
                };
                await vocabulary();
                await vocabulary();
                say(['no epoch yet: every call reads', calls]);
                useEpoch(() => epoch);
                epoch = 7;
                const first = await vocabulary();
                const second = await vocabulary();
                say([first.address, first.steps[0].name, first === second, calls]);
                epoch = 8;
                await vocabulary();
                say(['a new epoch reads again', calls]);
                answer = 'missing';
                epoch = 9;
                say(await vocabulary());
                answer = 'html';
                say(await vocabulary());
                answer = 'json';
                const after = await vocabulary();
                say(['a failed read is not kept', after.steps.length, calls]);
                await vocabulary();
                say(['the good one is', calls]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"no epoch yet: every call reads\",2]",
                "[\"/api/vocabulary\",\"Coverage check\",true,3]",
                "[\"a new epoch reads again\",4]",
                // Before the server serves the words, and when it answers with something that is not them: nothing to draw from.
                "null",
                "null",
                "[\"a failed read is not kept\",1,7]",
                "[\"the good one is\",7]");
    }

    /** A record component, or a public method with no argument that Jackson writes as a property. */
    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
