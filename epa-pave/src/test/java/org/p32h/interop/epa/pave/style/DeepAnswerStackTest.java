package org.p32h.interop.epa.pave.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.pas.OnyxPasClient;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Nesting;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.pas.PasInquireRequest;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.PasSubmitRequest;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * An answer of the service nested as deep as the parser reads, handled whole on a thread of the
 * smallest stack this tool supports.
 *
 * <p>The walks {@link RecursionGuardTest} lists as bounded by a JSON level take a frame per level,
 * and the parser allows {@value RecursionGuardTest#JACKSON_DEPTH}. A request thread has 1 MB on the
 * 64-bit Javas this tool runs on, Linux and Windows alike. Half of that, 512 KB, is the smallest stack
 * the tool supports: on Java 25 with the IDE's fast start (C1 only), each protocol's 500-level answer
 * runs at 512 KB and throws StackOverflowError at 384 KB. Each answer below is exactly 500 levels deep
 * where it is deepest, ends in a value that repeats a secret the request sent, so the masking of what
 * the service sends back walks it too, and runs through the service as a run from the screen does:
 * the client's answer read, masked, mapped, judged, recorded and written out as the browser gets it.
 */
class DeepAnswerStackTest {

    private static final int STACK_BYTES = 512 * 1024;

    private static final String TOKEN = "placeholder-access-token-DEEP42";

    private static final String URL = "https://example.org/p32h-placeholder/fhir";

    private static final String DEEP = "@DEEP@";

    private final InMemoryLedger ledger = new InMemoryLedger();

    /**
     * The text with {@link #DEEP} replaced by objects and lists that alternate down to the parser's
     * last level, the innermost holding the secret. Checked: it reads, and one level more would not.
     */
    private static String deepest(String shape) {
        int at = shape.indexOf(DEEP);
        int above = 0;
        for (char c : shape.substring(0, at).toCharArray()) {
            above += c == '{' || c == '[' ? 1 : c == '}' || c == ']' ? -1 : 0;
        }
        int levels = Nesting.limit() - above;
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            boolean object = i % 2 == 0;
            open.append(object ? "{\"x\":" : "[");
            close.insert(0, object ? "}" : "]");
        }
        String text = shape.replace(DEEP, open + "\"echo " + TOKEN + "\"" + close);
        assertThat(JsonSafe.parse(text).ok()).as("reads").isTrue();
        assertThat(Nesting.tooDeep(text)).isNull();
        assertThat(Nesting.tooDeep(shape.replace(DEEP, "[" + open + "1" + close + "]"))).as("one level more is past the limit").isNotNull();
        return text;
    }

    private static VendorExchange answered(String body) {
        WireRequest request = new WireRequest("POST", URL, URL, List.of(), "{}", true, null, SentSecrets.builder().accessToken(TOKEN).build());
        return Exchanges.fromResponse(request, 200, List.of(), body, 5L, true);
    }

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

    /** Runs one call on the small stack, writes the result as the browser gets it there too, and checks it was recorded and masked. */
    private void handled(Supplier<RunEnvelope<?>> run) throws InterruptedException {
        int before = ledger.records().size();
        String written = onSmallStack(() -> {
            RunEnvelope<?> result = run.get();
            assertThat(result.error()).as("the run completed").isNull();
            return JsonSafe.mapper().writeValueAsString(result);
        });
        assertThat(written).doesNotContain(TOKEN);
        assertThat(ledger.records()).hasSize(before + 1);
    }

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private RunSupport support() {
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                ledgerProvider(), java.time.Clock.fixed(java.time.Instant.parse("2026-03-10T15:00:00Z"), java.time.ZoneId.of("America/New_York")));
    }

    private static MemberDetail member() {
        return MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder").dateOfBirth("1970-01-31")
                .gender("female").lineOfBusiness("Medicare").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static List<CodeInput> codes() {
        return List.of(new CodeInput("95810", null, "Sleep study, attended"));
    }

    @Test
    void aCrdAnswerAsDeepAsTheParserReadsIsHandledOnTheSmallestSupportedStack() throws InterruptedException {
        String body = deepest("{\"cards\":[{\"uuid\":\"c1\",\"summary\":\"Prior authorization required\",\"indicator\":\"info\","
                + "\"source\":{\"label\":\"stand-in\"},\"extension\":{\"deep\":" + DEEP + "}}]}");
        CrdService service = new CrdService(EpaProperties.of(Map.of("mode", "mock")), FakeGateway.unconfigured(), new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText text, MockSelection selection) {
                return answered(body);
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return answered("{}");
            }
        }, new FakeCodeList(), support());
        handled(() -> service.run(CrdRunRequest.builder().member(member()).provider(provider()).procedureCodes(codes())
                .dateOfService("2026-03-20").build(), RunOrigin.manual(), null));
    }

    @Test
    void aDtrPackageWithItemsNestedAsDeepAsTheParserReadsIsHandledOnTheSmallestSupportedStack() throws InterruptedException {
        // Groups within groups: every walk of the items, the item views, the findings and the evaluator's tree goes all the way down.
        String shape = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"return\",\"resource\":{\"resourceType\":\"Bundle\","
                + "\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Questionnaire\",\"url\":\"" + URL + "/Questionnaire/q\","
                + "\"status\":\"active\",\"item\":" + DEEP + "}}]}}]}";
        int at = shape.indexOf(DEEP);
        int above = 0;
        for (char c : shape.substring(0, at).toCharArray()) {
            above += c == '{' || c == '[' ? 1 : c == '}' || c == ']' ? -1 : 0;
        }
        StringBuilder items = new StringBuilder();
        StringBuilder close = new StringBuilder();
        int groups = (Nesting.limit() - above - 2) / 2;
        for (int i = 0; i < groups; i++) {
            items.append("[{\"linkId\":\"g").append(i).append("\",\"type\":\"group\",\"text\":\"Group\",\"item\":");
            close.insert(0, "}]");
        }
        String body = shape.replace(DEEP, items + "[{\"linkId\":\"leaf\",\"type\":\"string\",\"text\":\"echo " + TOKEN + "\"}]" + close);
        assertThat(JsonSafe.parse(body).ok()).isTrue();
        assertThat(Nesting.limit() - above - 2 - 2 * groups).as("levels left unused below the leaf").isLessThan(2);
        DtrService service = new DtrService(EpaProperties.of(Map.of("mode", "mock")), FakeGateway.unconfigured(), new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText text, MockSelection selection) {
                return answered(body);
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText text, MockSelection selection) {
                return answered(body);
            }
        }, new FakeCodeList(), support());
        handled(() -> service.runPackage(DtrPackageRequest.builder().member(member()).provider(provider()).procedureCodes(codes())
                .dateOfService("2026-03-20").build(), RunOrigin.manual(), null));
    }

    private PasService pas(String body) {
        OnyxPasClient client = new OnyxPasClient() {
            @Override
            public VendorExchange submit(PreparedCall call, ResolvedText text, MockSelection selection) {
                return answered(body);
            }

            @Override
            public VendorExchange inquire(PreparedCall call, ResolvedText text, MockSelection selection) {
                return answered(body);
            }
        };
        return new PasService(OnyxProperties.defaults(), FakeGateway.unconfigured(), client, new FakeCodeList(), support(), ledgerProvider());
    }

    private static final String CLAIM_RESPONSE = "{\"resourceType\":\"ClaimResponse\",\"id\":\"cr\",\"status\":\"active\",\"use\":"
            + "\"preauthorization\",\"outcome\":\"complete\",\"preAuthRef\":\"PA-PLACEHOLDER-1\",\"extension\":[{\"url\":\"" + URL + "/x\","
            + "\"valueString\":\"v\"}],\"item\":[{\"itemSequence\":1,\"extension\":" + DEEP + "}]}";

    @Test
    void aPasAnswerAsDeepAsTheParserReadsIsHandledOnTheSmallestSupportedStack() throws InterruptedException {
        String inAnExtension = deepest("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + CLAIM_RESPONSE + "}]}");
        handled(() -> pas(inAnExtension).runSubmit(PasSubmitRequest.builder().member(member()).provider(provider()).procedureCodes(codes())
                .dateOfService("2026-03-20").build(), RunOrigin.manual(), null));

        // Bundles within bundles: the reading of a bundle calls itself for each one.
        String deepestBundle = CLAIM_RESPONSE.replace(DEEP, "[]");
        while (true) {
            String wrapped = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + deepestBundle + "}]}";
            if (Nesting.tooDeep(wrapped) != null) {
                break;
            }
            deepestBundle = wrapped;
        }
        assertThat(JsonSafe.parse(deepestBundle).ok()).isTrue();
        String bundleAnswer = deepestBundle;
        handled(() -> pas(bundleAnswer).runSubmit(PasSubmitRequest.builder().member(member()).provider(provider()).procedureCodes(codes())
                .dateOfService("2026-03-20").build(), RunOrigin.manual(), null));
    }

    @Test
    void anInquiryAnswerAsDeepAsTheParserReadsIsHandledOnTheSmallestSupportedStack() throws InterruptedException {
        String body = deepest("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + CLAIM_RESPONSE + "}]}");
        handled(() -> pas(body).runInquire(PasInquireRequest.builder().member(member()).provider(provider()).claimId("claim-test-0001").build(),
                RunOrigin.manual(), null));
    }
}
