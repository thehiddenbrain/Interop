package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A questionnaire whose items each wait on the next one through enableWhen. The items sit in a flat
 * list, so the parser's limit of 500 nesting levels does not bound how long such a chain is, and a
 * service's generated questionnaire may hold one. Working it out once took a frame of the thread's
 * stack per item and threw StackOverflowError on Java 25 from 1,500 items at the default stack, from
 * 400 at 256 KB, through the package fetch and through the completion of a response alike.
 */
class EnableWhenChainTest {

    /** A quarter of a request thread's stack, as in the pattern guard: what passes here passes there with room. */
    private static final int STACK_BYTES = 256 * 1024;

    private static final int CHAIN = 5_000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Item i is enabled when item i+1 is answered true; the last item has no condition. */
    private static ObjectNode reverseChain(int length) {
        ObjectNode questionnaire = NODES.objectNode().put("resourceType", "Questionnaire").put("id", "q-chain")
                .put("url", "http://example.invalid/Questionnaire/q-chain").put("status", "active");
        ArrayNode items = questionnaire.putArray("item");
        for (int i = 0; i < length; i++) {
            ObjectNode item = items.addObject().put("linkId", "q" + i).put("text", "Question " + i).put("type", "boolean");
            if (i < length - 1) {
                item.putArray("enableWhen").addObject().put("question", "q" + (i + 1)).put("operator", "=").put("answerBoolean", true);
            }
        }
        return questionnaire;
    }

    private static Map<String, List<JsonNode>> allTrue(int length) {
        Map<String, List<JsonNode>> answers = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            answers.put("q" + i, List.of(NODES.objectNode().put("valueBoolean", true)));
        }
        return answers;
    }

    /** Runs the work on a thread of a small stack and hands back what it returned, or rethrows what it threw, Errors included. */
    private static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "small-stack", STACK_BYTES);
        thread.start();
        thread.join();
        if (thrown.get() != null) {
            throw new AssertionError("threw " + thrown.get().getClass().getName() + " on a " + STACK_BYTES / 1024 + " KB stack", thrown.get());
        }
        return result.get();
    }

    @Test
    void aChainOfThousandsOfConditionsIsWorkedOutOnASmallStack() throws InterruptedException {
        ObjectNode questionnaire = reverseChain(CHAIN);

        EnableWhenEvaluator.Form unanswered = onSmallStack(() -> EnableWhenEvaluator.evaluate(questionnaire, Map.of()));
        // Only the last item has no condition. Each before it waits on an answer of true that never came.
        assertThat(unanswered.state("item[" + (CHAIN - 1) + "]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(unanswered.all()).hasSize(CHAIN);
        assertThat(unanswered.all().subList(0, CHAIN - 1)).allMatch(state -> state.enabled() == ItemEnabled.DISABLED);
        assertThat(unanswered.asked()).extracting(QuestionRef::linkId).containsExactly("q" + (CHAIN - 1));

        EnableWhenEvaluator.Form answered = onSmallStack(() -> EnableWhenEvaluator.evaluate(questionnaire, allTrue(CHAIN)));
        assertThat(answered.all()).allMatch(state -> state.enabled() == ItemEnabled.ENABLED);
        assertThat(answered.answered()).hasSize(CHAIN);
    }

    @Test
    void aRingOfThousandsOfConditionsIsReportedAsAConditionOnItselfNotAnError() throws InterruptedException {
        ObjectNode questionnaire = reverseChain(CHAIN);
        ((ObjectNode) questionnaire.get("item").get(CHAIN - 1)).putArray("enableWhen").addObject().put("question", "q0").put("operator", "=")
                .put("answerBoolean", true);

        EnableWhenEvaluator.Form form = onSmallStack(() -> EnableWhenEvaluator.evaluate(questionnaire, Map.of()));
        // The first item is entered first, so the condition that comes back round to it is the one that cannot be evaluated.
        assertThat(form.state("item[" + (CHAIN - 1) + "]").conditions().get(0).explanation())
                .isEqualTo("Not evaluated: the condition depends on its own item, directly or through other conditions.");
        assertThat(form.state("item[" + (CHAIN - 1) + "]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
    }

    @Test
    void thePackageFetchAndTheCompletionOfAResponseGiveAResultForALongChainOnASmallStack() throws InterruptedException {
        ObjectNode questionnaire = reverseChain(CHAIN);
        ObjectNode packageAnswer = NODES.objectNode().put("resourceType", "Parameters");
        ObjectNode bundle = packageAnswer.putArray("parameter").addObject().put("name", "return").putObject("resource")
                .put("resourceType", "Bundle").put("type", "collection");
        bundle.putArray("entry").addObject().set("resource", questionnaire);
        InMemoryLedger ledger = new InMemoryLedger();
        DtrService service = service(ledger, DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, packageAnswer.toString()));

        RunEnvelope<DtrDetail> fetched = onSmallStack(() -> service.runPackage(DtrFixtures.packageRequest("S123456789").build(),
                RunOrigin.manual(), null));
        assertThat(fetched.error()).isNull();
        assertThat(fetched.verdict().outcomeCode()).isEqualTo("PACKAGE_LOADED");
        assertThat(fetched.detail().questionnaires()).hasSize(1);
        assertThat(fetched.ledger().recorded()).isTrue();

        // The screen sends the questionnaire back with the answers; the tool works the conditions out again from it.
        List<DtrAnswerInput> answers = new ArrayList<>();
        for (int i = 0; i < CHAIN; i++) {
            answers.add(DtrAnswerInput.of("q" + i, "true"));
        }
        RunEnvelope<DtrDetail> completed = onSmallStack(() -> service.runResponse(DtrResponseRequest.builder()
                .member(DtrFixtures.member("S123456789")).provider(DtrFixtures.provider()).procedureCodes(List.of(CodeInput.of("95810")))
                .carry(fetched.nextStep().carry()).chainId(fetched.chainId()).questionnaire(fetched.detail().questionnaires().get(0).json())
                .answers(answers).build(), RunOrigin.manual()));
        assertThat(completed.error()).isNull();
        assertThat(completed.verdict().outcomeCode()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(ledger.last().runId()).isEqualTo(completed.runId());
    }

    private static DtrService service(InMemoryLedger ledger, VendorExchange answer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        OnyxDtrClient client = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                return answer;
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                return answer;
            }
        };
        return new DtrService(support.epa(), FakeGateway.unconfigured(), client, new FakeCodeList(), support);
    }

    // ---- the same answer as the recursive evaluator, wherever that one could give one ----

    private static final String[] OPERATORS = {"=", "!=", "exists", ">", "<=", "~", null};

    /**
     * Small questionnaires drawn at random from a fixed seed: groups and nested items, linkIds that
     * repeat, conditions on the item itself, on a missing linkId, in rings, of every outcome, with
     * and without enableBehavior, and with the rules this tool does not apply that change how an
     * item is judged. Every state, reason and condition must come out as the recursive evaluator
     * gave it, and so must every list the screen and the ledger are built from.
     */
    @Test
    void everyQuestionnaireIsWorkedOutAsTheRecursiveEvaluatorWorkedItOut() {
        Random random = new Random(20261121L);
        for (int round = 0; round < 20_000; round++) {
            List<String> pool = new ArrayList<>();
            int poolSize = 1 + random.nextInt(8);
            for (int i = 0; i < poolSize; i++) {
                pool.add("l" + i);
            }
            ObjectNode questionnaire = NODES.objectNode().put("resourceType", "Questionnaire");
            fill(questionnaire.putArray("item"), random, pool, 0);
            Map<String, List<JsonNode>> answers = new LinkedHashMap<>();
            for (String linkId : pool) {
                if (random.nextInt(3) > 0) {
                    answers.put(linkId, List.of(randomAnswer(random)));
                }
            }
            String now = describe(EnableWhenEvaluator.evaluate(questionnaire, answers));
            String before = describe(RecursiveEnableWhenEvaluator.evaluate(questionnaire, answers));
            assertThat(now).as("round %d: %s with %s", round, questionnaire, answers).isEqualTo(before);
        }
    }

    private static void fill(ArrayNode items, Random random, List<String> pool, int depth) {
        int count = 1 + random.nextInt(depth == 0 ? 6 : 3);
        for (int i = 0; i < count; i++) {
            ObjectNode item = items.addObject();
            if (random.nextInt(12) > 0) {
                item.put("linkId", pool.get(random.nextInt(pool.size())));
            }
            boolean group = depth < 3 && random.nextInt(4) == 0;
            item.put("type", group ? "group" : random.nextInt(2) == 0 ? "boolean" : "integer");
            item.put("text", "Item");
            if (random.nextBoolean()) {
                item.put("required", true);
            }
            int conditions = random.nextInt(4);
            if (conditions > 0) {
                ArrayNode enableWhen = item.putArray("enableWhen");
                for (int c = 0; c < conditions; c++) {
                    ObjectNode condition = enableWhen.addObject();
                    if (random.nextInt(10) > 0) {
                        condition.put("question", random.nextInt(10) > 0 ? pool.get(random.nextInt(pool.size())) : "absent");
                    }
                    String operator = OPERATORS[random.nextInt(OPERATORS.length)];
                    if (operator != null) {
                        condition.put("operator", operator);
                    }
                    switch (random.nextInt(5)) {
                        case 0 -> condition.put("answerBoolean", random.nextBoolean());
                        case 1 -> condition.put("answerInteger", random.nextInt(3));
                        case 2 -> condition.putNull("answerBoolean");
                        case 3 -> condition.put("answerString", "x");
                        default -> { }
                    }
                }
                switch (random.nextInt(4)) {
                    case 0 -> item.put("enableBehavior", "any");
                    case 1 -> item.put("enableBehavior", "all");
                    default -> { }
                }
            }
            switch (random.nextInt(8)) {
                case 0 -> item.putArray("extension").addObject().put("url", "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden")
                        .put("valueBoolean", true);
                case 1 -> item.putArray("extension").addObject()
                        .put("url", "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression")
                        .putObject("valueExpression").put("language", "text/cql").put("expression", "X");
                case 2 -> item.putArray("extension").addObject()
                        .put("url", "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression")
                        .putObject("valueExpression").put("language", "text/cql").put("expression", "X");
                default -> { }
            }
            if (group) {
                fill(item.putArray("item"), random, pool, depth + 1);
            }
        }
    }

    private static JsonNode randomAnswer(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> NODES.objectNode().put("valueBoolean", true);
            case 1 -> NODES.objectNode().put("valueBoolean", false);
            case 2 -> NODES.objectNode().put("valueInteger", random.nextInt(3));
            default -> NODES.objectNode().put("valueString", "x");
        };
    }

    private static String describe(EnableWhenEvaluator.Form form) {
        StringBuilder out = new StringBuilder();
        for (EnableWhenEvaluator.ItemState state : form.all()) {
            out.append(state.item().path()).append(' ').append(state.enabled()).append(' ').append(state.reason()).append(' ')
                    .append(state.notApplied()).append(' ').append(state.given()).append('\n');
            for (EnableWhenEvaluator.Condition condition : state.conditions()) {
                out.append("  ").append(condition).append('\n');
            }
        }
        return out.append(refs(form.asked())).append(refs(form.requiredUnanswered())).append(refs(form.undetermined()))
                .append(refs(form.answersLeftOut())).toString();
    }

    private static String describe(RecursiveEnableWhenEvaluator.Form form) {
        StringBuilder out = new StringBuilder();
        for (RecursiveEnableWhenEvaluator.ItemState state : form.all()) {
            out.append(state.item().path()).append(' ').append(state.enabled()).append(' ').append(state.reason()).append(' ')
                    .append(state.notApplied()).append(' ').append(state.given()).append('\n');
            for (RecursiveEnableWhenEvaluator.Condition condition : state.conditions()) {
                out.append("  ").append(condition).append('\n');
            }
        }
        return out.append(refs(form.asked())).append(refs(form.requiredUnanswered())).append(refs(form.undetermined()))
                .append(refs(form.answersLeftOut())).toString();
    }

    private static String refs(List<QuestionRef> refs) {
        return refs.stream().map(ref -> ref.linkId() + "|" + ref.text() + "|" + ref.note() + "|" + ref.values()).toList() + "\n";
    }
}
