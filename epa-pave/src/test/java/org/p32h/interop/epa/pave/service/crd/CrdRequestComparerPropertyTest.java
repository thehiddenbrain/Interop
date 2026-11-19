package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.compare.CaseValue;
import org.p32h.interop.epa.pave.model.compare.Difference;
import org.p32h.interop.epa.pave.model.compare.NotFound;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.service.compare.Paths;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * No single change to a pasted request passes without a trace.
 *
 * <p>The comparison builds the tool's request for the case it reads out of the pasted one, so
 * that two requests for the same member are compared. That is the one way a value of the pasted
 * request can fail to show as a difference: it was taken over. So these tests change every value
 * of a request in turn, one at a time, and hold the answer to this: the change is a difference at
 * that place, or the answer names the changed value as one it read and used, or the answer says
 * the two were not compared. Nothing else is allowed, because anything else is a difference the
 * tester would never be shown.
 */
class CrdRequestComparerPropertyTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String HOOK_INSTANCE = "11111111-1111-4111-8111-111111111111";
    private static final String ONLY_HOOK_INSTANCE = "hookInstance | DIFFERENT_VALUE | EVERY_CALL";

    private final FakeGateway gateway = new FakeGateway();
    private final FakeCodeList codeList = new FakeCodeList();

    private CrdRequestBuilder builder() {
        return new CrdRequestBuilder(codeList);
    }

    private Function<String, ResolvedText> variables(Map<String, String> typedForThisRun) {
        return template -> gateway.resolve(template, typedForThisRun);
    }

    private RequestComparison compare(CrdRunRequest form) {
        RequestComparison comparison = new CrdRequestComparer(builder()).compare(form, variables(form.variables()));
        CompareWords.assertWords(comparison, CrdRequestComparerTest.MEMBER_DATA);
        return comparison;
    }

    private String sent(CrdRunRequest request) {
        return builder().build(request, HOOK_INSTANCE, variables(Map.of())).json();
    }

    private void configureEveryVariable() {
        gateway.variable("fhirServer", "https://ehr.example.invalid/fhir").variable("memberIdSystem", "https://example.org/p32h-placeholder/member-id")
                .variable("payerName", "Placeholder payer").variable("payerIdentifier", "PAYER-PLACEHOLDER")
                .variable("payerIdentifierSystem", "https://example.org/p32h-placeholder/payer-id");
    }

    // ---- every value of a request, and one change to it ----

    /** One value of a request that is not a list or an object, with its place. */
    private record Leaf(String pointer, JsonNode value) {

        /** Prints the place and never the value, which is part of a request. */
        @Override
        public String toString() {
            return "Leaf[" + pointer + "]";
        }
    }

    private enum Change {
        /** Another value of the same JSON type. */
        CHANGED,
        /** An explicit null in its place. */
        NULLED,
        /** An empty text in its place. */
        EMPTIED,
        /** Taken out, so that what followed it in a list moves up. */
        REMOVED;

        boolean inPlace() {
            return this != REMOVED;
        }
    }

    private static List<Leaf> leaves(String request) {
        List<Leaf> out = new ArrayList<>();
        leaves(JsonSafe.parse(request).node(), "", out);
        return out;
    }

    private static void leaves(JsonNode node, String pointer, List<Leaf> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                leaves(member.getValue(), Paths.member(pointer, member.getKey()), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                leaves(node.get(i), Paths.element(pointer, i), out);
            }
        } else {
            out.add(new Leaf(pointer, node));
        }
    }

    /** The value a change puts in the place of a leaf, or null when the change would change nothing. */
    private static JsonNode replacement(Leaf leaf, Change how) {
        JsonNode value = leaf.value();
        return switch (how) {
            case CHANGED -> value.isString() ? JSON.stringNode(value.stringValue() + "-x")
                    : value.isNumber() ? JSON.numberNode(value.decimalValue().add(BigDecimal.ONE))
                    : value.isBoolean() ? JSON.booleanNode(!value.booleanValue()) : JSON.stringNode("x");
            case NULLED -> value.isNull() ? null : JSON.nullNode();
            case EMPTIED -> value.isString() && value.stringValue().isEmpty() ? null : JSON.stringNode("");
            case REMOVED -> JSON.missingNode();
        };
    }

    /** The request with one leaf changed, or null when the change would change nothing. */
    private static String changed(String request, Leaf leaf, Change how) {
        JsonNode value = replacement(leaf, how);
        if (value == null) {
            return null;
        }
        JsonNode root = JsonSafe.parse(request).node();
        int cut = leaf.pointer().lastIndexOf('/');
        JsonNode parent = root.at(leaf.pointer().substring(0, cut));
        String last = leaf.pointer().substring(cut + 1).replace("~1", "/").replace("~0", "~");
        if (parent instanceof ObjectNode object) {
            if (how == Change.REMOVED) {
                object.remove(last);
            } else {
                object.set(last, value);
            }
        } else {
            ArrayNode list = (ArrayNode) parent;
            if (how == Change.REMOVED) {
                list.remove(Integer.parseInt(last));
            } else {
                list.set(Integer.parseInt(last), value);
            }
        }
        String text = root.toString();
        assertThat(text).as("the change at " + leaf.pointer() + " changes the text").isNotEqualTo(JsonSafe.parse(request).node().toString());
        return text;
    }

    // ---- what an answer shows ----

    private static List<String> lines(RequestComparison comparison) {
        return comparison.differences().stream().map(difference -> difference.path() + " | " + difference.kind() + " | " + difference.group()).toList();
    }

    /** True when a difference marks this place in the pasted request, or a place that holds it. */
    private static boolean marked(RequestComparison comparison, String pointer) {
        for (Difference difference : comparison.differences()) {
            String at = difference.yourPointer();
            if (at != null && (pointer.equals(at) || pointer.startsWith(at + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** The value the answer says it read from this place of the pasted request and used, or null. */
    private static CaseValue readFrom(RequestComparison comparison, String pointer) {
        return comparison.caseSource().stream()
                .filter(value -> value.source() == CaseValue.Source.YOUR_REQUEST && pointer.equals(value.pointer())).findFirst().orElse(null);
    }

    private static String shown(Shown value) {
        return value == null ? "-" : value.presence() + ":" + value.value();
    }

    /** Everything an answer says, except the two texts themselves: a change to the pasted text always shows there. */
    private static String said(RequestComparison answer) {
        StringBuilder out = new StringBuilder().append(answer.comparable()).append('|').append(answer.whyNot()).append('|').append(answer.hook())
                .append('|').append(answer.headline()).append('\n');
        for (Difference difference : answer.differences()) {
            out.append(difference.path()).append('|').append(difference.kind()).append('|').append(difference.group()).append('|')
                    .append(shown(difference.toolValue())).append('|').append(shown(difference.yourValue())).append('|')
                    .append(difference.toolPointer()).append('|').append(difference.yourPointer()).append('|')
                    .append(difference.referencesFollowing()).append('|').append(difference.explanation()).append('\n');
        }
        for (CaseValue value : answer.caseSource()) {
            out.append(value.field()).append('|').append(value.source()).append('|').append(value.pointer()).append('|')
                    .append(shown(value.value())).append('|').append(value.note()).append('\n');
        }
        for (NotFound missing : answer.notFound()) {
            out.append(missing.field()).append('|').append(missing.lookedIn()).append('|').append(missing.note()).append('|');
            missing.candidates().forEach(candidate -> out.append(candidate.pointer()).append('=').append(shown(candidate.value())).append(','));
            out.append('\n');
        }
        answer.notes().forEach(note -> out.append(note).append('\n'));
        return out.toString();
    }

    // ---- the properties ----

    @Test
    void withTheCaseOnTheFormEveryChangeToThePastedRequestIsADifferenceAtThatPlace() {
        int checked = 0;
        for (boolean configured : List.of(false, true)) {
            if (configured) {
                configureEveryVariable();
            }
            for (String hook : List.of("order-sign", "order-select")) {
                for (boolean prefetch : List.of(true, false)) {
                    String base = sent(CrdRequestComparerTest.form(hook, 3, prefetch).build());
                    assertThat(lines(compare(CrdRequestComparerTest.form(hook, 3, prefetch).rawBody(base).build()))).containsExactly(ONLY_HOOK_INSTANCE);
                    for (Leaf leaf : leaves(base)) {
                        // The one value that differs on every call, changed or not.
                        if (leaf.pointer().equals("/hookInstance")) {
                            continue;
                        }
                        for (Change how : Change.values()) {
                            String pasted = changed(base, leaf, how);
                            if (pasted == null) {
                                continue;
                            }
                            String described = hook + ", prefetch " + prefetch + ", variables set " + configured + ": " + leaf.pointer() + " " + how;
                            // The form holds the whole case, so nothing of the pasted request is taken over.
                            RequestComparison answer = compare(CrdRequestComparerTest.form(hook, 3, prefetch).rawBody(pasted).build());
                            assertThat(answer.comparable()).as(described).isTrue();
                            assertThat(lines(answer)).as(described).isNotEqualTo(List.of(ONLY_HOOK_INSTANCE));
                            if (how.inPlace()) {
                                assertThat(marked(answer, leaf.pointer())).as(described + " is marked by a difference, among " + lines(answer)).isTrue();
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        assertThat(checked).as("changes that were checked").isGreaterThan(1500);
    }

    @Test
    void withAnEmptyFormEveryChangeIsADifferenceOrAValueTheAnswerSaysItReadOrAnAnswerThatTheTwoWereNotCompared() {
        int marked = 0;
        int taken = 0;
        int notCompared = 0;
        for (boolean configured : List.of(false, true)) {
            if (configured) {
                configureEveryVariable();
            }
            for (String hook : List.of("order-sign", "order-select")) {
                for (int codes : List.of(1, 3)) {
                    String base = sent(CrdRequestComparerTest.form(hook, codes, true).build());
                    String before = said(compare(CrdRunRequest.builder().rawBody(base).build()));
                    for (Leaf leaf : leaves(base)) {
                        if (leaf.pointer().equals("/hookInstance")) {
                            continue;
                        }
                        for (Change how : Change.values()) {
                            String pasted = changed(base, leaf, how);
                            if (pasted == null) {
                                continue;
                            }
                            String described = hook + ", " + codes + " code(s), variables set " + configured + ": " + leaf.pointer() + " " + how;
                            RequestComparison answer = compare(CrdRunRequest.builder().rawBody(pasted).build());
                            // Whatever else holds, the answer is not the answer the unchanged request gets.
                            assertThat(said(answer)).as(described + " leaves a trace outside the text").isNotEqualTo(before);
                            if (!answer.comparable()) {
                                assertThat(answer.whyNot()).as(described).isNotBlank();
                                assertThat(answer.differences()).as(described).isEmpty();
                                notCompared++;
                            } else if (!how.inPlace() || marked(answer, leaf.pointer())) {
                                marked++;
                            } else {
                                CaseValue read = readFrom(answer, leaf.pointer());
                                assertThat(read).as(described + " is neither marked by a difference nor named as a value that was read, among "
                                        + lines(answer)).isNotNull();
                                // Taken over as it is written, which is why the two requests agree there.
                                assertThat(how).as(described).isEqualTo(Change.CHANGED);
                                assertThat(read.value().value()).as(described).isEqualTo(leaf.value().stringValue() + "-x");
                                taken++;
                            }
                        }
                    }
                }
            }
        }
        // All three outcomes are reached, so none of the branches above is there for nothing.
        assertThat(marked).as("changes that are a difference").isGreaterThan(500);
        assertThat(taken).as("changes that were taken over and named").isGreaterThan(20);
        assertThat(notCompared).as("changes after which the two were not compared").isGreaterThan(5);
    }

    @Test
    void theValuesAPastedRequestCanChangeWithoutADifferenceAreTheOnesTheAnswerListsAsRead() {
        // One order, so that the date of service is written once. Every place whose change is taken over, and no other.
        String base = sent(CrdRequestComparerTest.form("order-sign", 1, true).build());
        TreeSet<String> takenOver = new TreeSet<>();
        for (Leaf leaf : leaves(base)) {
            String pasted = leaf.pointer().equals("/hookInstance") ? null : changed(base, leaf, Change.CHANGED);
            if (pasted == null) {
                continue;
            }
            RequestComparison answer = compare(CrdRunRequest.builder().rawBody(pasted).build());
            if (answer.comparable() && !marked(answer, leaf.pointer())) {
                takenOver.add(leaf.pointer());
            }
        }
        assertThat(takenOver).containsExactly(
                "/context/draftOrders/entry/0/resource/code/coding/0/code",
                "/context/draftOrders/entry/0/resource/code/coding/0/display",
                "/context/draftOrders/entry/0/resource/code/coding/0/system",
                "/prefetch/coverage/class/0/value",
                "/prefetch/coverage/subscriberId",
                "/prefetch/patient/birthDate",
                "/prefetch/patient/name/0/family",
                "/prefetch/patient/name/0/given/0",
                "/prefetch/practitioner/identifier/0/value",
                "/prefetch/practitioner/name/0/family",
                "/prefetch/practitioner/name/0/given/0");
    }

    @Test
    void everyChangeToARequestShapedTheWayAVendorMightShapeItChangesWhatTheAnswerSays() {
        String base = JsonSafe.parse(CrdRequestComparerTest.fixture("vendor-shaped-order-sign.json")).node().toString();
        RequestComparison unchanged = compare(CrdRunRequest.builder().rawBody(base).build());
        assertThat(unchanged.comparable()).isTrue();
        String before = said(unchanged);
        int checked = 0;
        for (Leaf leaf : leaves(base)) {
            for (Change how : Change.values()) {
                String pasted = changed(base, leaf, how);
                if (pasted == null) {
                    continue;
                }
                String described = leaf.pointer() + " " + how;
                RequestComparison answer = compare(CrdRunRequest.builder().rawBody(pasted).build());
                assertThat(said(answer)).as(described + " leaves a trace outside the text").isNotEqualTo(before);
                if (answer.comparable() && how.inPlace()) {
                    assertThat(marked(answer, leaf.pointer()) || readFrom(answer, leaf.pointer()) != null)
                            .as(described + " is marked by a difference or named as a value that was read, among " + lines(answer)).isTrue();
                }
                checked++;
            }
        }
        assertThat(checked).as("changes that were checked").isGreaterThan(300);
    }
}
