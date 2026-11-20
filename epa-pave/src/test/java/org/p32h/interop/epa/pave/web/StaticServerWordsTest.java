package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.FindingKindOption;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ScenarioList;
import org.p32h.interop.epa.pave.model.ScenarioSummary;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.model.compare.CaseValue;
import org.p32h.interop.epa.pave.model.compare.GroupCount;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.service.config.ConfigViews;
import org.p32h.interop.epa.pave.service.raw.RawRunRequest;

/**
 * The screens read words and values the server serves, so the browser keeps no copy of them. Two
 * things break that without any test of the server failing: the server renames a field a script
 * reads, and the script then shows nothing; or a page's own copy of the server's words comes back.
 * <ul>
 *   <li>every field a script reads in place of a copy of its own is a property of the record the
 *       server answers with, and the script still reads it</li>
 *   <li>the page's own copies that were removed (finding groups, the comparison's source words and
 *       closed groups, the PAS decision sources, the DTR answers held on the page's side, the
 *       placeholder source words) do not come back</li>
 * </ul>
 * The last tests hand each rule a case that breaks it, so a rule that has stopped looking fails.
 */
class StaticServerWordsTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    /** One field a script reads: the script, the text of the read, the record, and the property. */
    private record Read(String script, String text, Class<?> record, String property) {
    }

    private static final List<Read> READS = List.of(
            new Read("crd-compare.js", "answer.caveat", RequestComparison.class, "caveat"),
            new Read("crd-compare.js", "answer.maskedNote", RequestComparison.class, "maskedNote"),
            new Read("crd-compare.js", "source.sourceLabel", CaseValue.class, "sourceLabel"),
            new Read("crd-compare.js", "group.startsOpen", GroupCount.class, "startsOpen"),
            new Read("result.js", "item.groupLabel", Finding.class, "groupLabel"),
            new Read("result.js", "item.group", Finding.class, "group"),
            new Read("result.js", "issue.expressionPresent", IssueView.class, "expressionPresent"),
            new Read("result.js", "issue.locationPresent", IssueView.class, "locationPresent"),
            new Read("legend.js", "answer.body.findingKinds", ScenarioList.class, "findingKinds"),
            new Read("marks.js", "kind.groupMeaning", FindingKindOption.class, "groupMeaning"),
            new Read("marks.js", "kind.groupLabel", FindingKindOption.class, "groupLabel"),
            new Read("marks.js", "kind.group", FindingKindOption.class, "group"),
            new Read("marks.js", "kind.code", FindingKindOption.class, "code"),
            new Read("dtr-detail.js", "view.kindLabel", QuestionnaireView.class, "kindLabel"),
            new Read("dtr-items.js", "condition.stateLabel", EnableWhenView.class, "stateLabel"),
            new Read("dtr-detail.js", "ref.values", QuestionRef.class, "values"),
            new Read("workbench.js", "step.carryLabels", NextStepView.class, "carryLabels"),
            new Read("tab-dtr.js", "list.nextActions", ScenarioList.class, "nextActions"),
            new Read("pas-detail.js", "decision.sourceLabel", PasDecision.class, "sourceLabel"),
            new Read("pas-detail.js", "detail.attachmentNote", PasDetail.class, "attachmentNote"),
            new Read("pas-notifications.js", "mode.bindNote", ModeView.class, "bindNote"),
            new Read("config-collections.js", "row.defaultRule", ConfigViews.OperationRow.class, "defaultRule"),
            new Read("config-inforce.js", "row.defaultRule", ConfigViews.OperationRow.class, "defaultRule"),
            new Read("config-parts.js", "variable.sourceLabel", ConfigViews.VariableRow.class, "sourceLabel"),
            new Read("config-parts.js", "variable.sourceLabel", VariableUse.class, "sourceLabel"),
            new Read("code-picker.js", "answer.body.exactCode", ProcedureCodeController.Answer.class, "exactCode"),
            new Read("workbench.js", "scenario.sentinelMemberIds", ScenarioSummary.class, "sentinelMemberIds"),
            new Read("workbench.js", "scenario.sentinelNpis", ScenarioSummary.class, "sentinelNpis"),
            new Read("workbench.js", "scenario.crdSentinelMemberIds", ScenarioSummary.class, "crdSentinelMemberIds"),
            new Read("config-raw.js", "sent.urlValues", RawRunRequest.class, "urlValues"),
            new Read("config-environments.js", "environment.addressWarnings", ConfigViews.EnvironmentView.class, "addressWarnings"),
            new Read("config-environments.js", "answer.body.addressWarnings", ConfigViews.Activated.class, "addressWarnings"),
            new Read("config-inforce.js", "active.addressWarnings", ConfigViews.EnvironmentView.class, "addressWarnings"),
            new Read("workbench.js", "scenario.notes", ScenarioSummary.class, "notes"),
            new Read("workbench.js", "list.notes", ScenarioList.class, "notes"),
            new Read("app.js", "answer.body.source", CodeListSelfCheck.class, "source"),
            new Read("app.js", "answer.body.placeholder", CodeListSelfCheck.class, "placeholder"));

    /** A copy the page kept of the server's words, which the server now serves: the script and the pattern that finds it. */
    private record Copy(String script, String pattern, String why) {
    }

    private static final List<Copy> REMOVED = List.of(
            new Copy("*", "\\bFINDING_GROUPS\\b", "finding groups are Finding.group and groupLabel, and ScenarioList.findingKinds"),
            new Copy("*", "\\bfindingGroup\\s*\\(", "a finding's heading is its groupLabel"),
            new Copy("*", "\\bSTARTS_CLOSED\\b", "a comparison group says whether it starts open (GroupCount.startsOpen)"),
            new Copy("crd-compare.js", "\\bconst\\s+SOURCES\\b", "a value of the case says where it came from (CaseValue.sourceLabel)"),
            new Copy("crd-compare.js", "notes\\[0]", "the comparison's caveat is RequestComparison.caveat"),
            new Copy("pas-detail.js", "\\bconst\\s+SOURCES\\b", "a decision says where it was stated (PasDecision.sourceLabel)"),
            new Copy("pas-detail.js", "no attachment was sent", "how attachments travel is PasDetail.attachmentNote"),
            new Copy("pas-notifications.js", "No service can call this receiver back", "whether the receiver can be reached is ModeView.bindNote"),
            new Copy("config-parts.js", "\\bconst\\s+SOURCES\\b", "where a placeholder's value came from is its sourceLabel"),
            new Copy("*", "\\bheldBefore\\b", "a dropped answer carries its values (QuestionRef.values)"),
            new Copy("dtr-questionnaire.js", "On this tab that is", "the next step names the button by its action's label"),
            new Copy("*", "'Complete the response'|'Send answers and get the next question'",
                    "the DTR step buttons are named by the labels of SUBMIT_RESPONSE and ANSWER_NEXT_QUESTION"),
            new Copy("*", "the bundled placeholder, not a P32H list",
                    "a list that says it is synthetic can be the sample or a tester's own: the label says placeholder and the self-check's source"));

    @Test
    void everyFieldAScriptReadsInPlaceOfItsOwnWordsIsOnTheRecordTheServerSends() throws IOException {
        for (Read read : READS) {
            assertThat(hasProperty(read.record(), read.property()))
                    .as(read.record().getSimpleName() + " has " + read.property() + ", which " + read.script() + " reads")
                    .isTrue();
            assertThat(read(JS.resolve(read.script()))).as(read.script() + " still reads " + read.text()).contains(read.text());
        }
    }

    @Test
    void noCopyOfTheServersWordsThatWasRemovedComesBack() throws IOException {
        List<String> found = new ArrayList<>();
        for (Copy copy : REMOVED) {
            Pattern pattern = Pattern.compile(copy.pattern());
            for (Path script : scripts(copy.script())) {
                if (pattern.matcher(read(script)).find()) {
                    found.add(script.getFileName() + ": " + copy.pattern() + " (" + copy.why() + ")");
                }
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void aPropertyTheRecordDoesNotHaveIsCaught() {
        assertThat(hasProperty(RequestComparison.class, "caveat")).isTrue();
        assertThat(hasProperty(RequestComparison.class, "cavaet")).isFalse();
        assertThat(hasProperty(ConfigViews.VariableRow.class, "sourceLabel")).isTrue();
    }

    @Test
    void aRemovedCopyIsFoundWhenItIsThere() {
        Pattern pattern = Pattern.compile(REMOVED.get(0).pattern());
        assertThat(pattern.matcher("import {FINDING_GROUPS} from './marks.js';").find()).isTrue();
        assertThat(pattern.matcher("import {findingGroupsOf} from './marks.js';").find()).isFalse();
        assertThatThrownBy(() -> read(JS.resolve("no-such-script.js"))).isInstanceOf(IOException.class);
    }

    /** A record component, or a public method with no argument that Jackson writes as a property. */
    private static boolean hasProperty(Class<?> type, String name) {
        try {
            return type.getMethod(name).getParameterCount() == 0;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static List<Path> scripts(String which) throws IOException {
        if (!"*".equals(which)) {
            return List.of(JS.resolve(which));
        }
        try (Stream<Path> all = Files.list(JS)) {
            return all.filter(path -> path.toString().endsWith(".js")).sorted().toList();
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
