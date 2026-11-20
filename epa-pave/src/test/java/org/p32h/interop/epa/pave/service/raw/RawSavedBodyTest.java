package org.p32h.interop.epa.pave.service.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.client.raw.MockRawRequestClient;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.raw.RawDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A collection request sent as written, through the real gateway: the body the vendor saved with
 * it, the parts of its url filled for one run, and what the result says on each profile.
 */
class RawSavedBodyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("UTC"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String BASE = "https://onyx.example.invalid/fhir";
    /** A saved body with sample member data, a credential typed into it and a placeholder the environment fills. */
    private static final String SAVED = "{\"memberId\": \"S123456789\", \"client_secret\": \"ZZTYPEDSECRET-1\", \"tenant\": \"{{tenant}}\"}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final AtomicReference<ResolvedText> sentBody = new AtomicReference<>();
    private final AtomicReference<PreparedCall> sentCall = new AtomicReference<>();

    /** A live client that refuses what the live client refuses and records what it would send. */
    private RawRequestClient capturing() {
        return (call, body) -> {
            Exchanges.requireSendable(call, body, null);
            sentCall.set(call);
            sentBody.set(body);
            return TestExchanges.of(EpaOperation.UNMAPPED, 200, "{\"ok\":true}", false);
        };
    }

    private ConfigFixture fixture(boolean live, String url, String savedBody) {
        // Each profile has a store of its own, so one test can look at both.
        Path own = directory.resolve(live ? "live" : "mock");
        ConfigFixture fixture = live ? ConfigFixture.on(own).live().build() : ConfigFixture.mock(own);
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE, "tenant", "T-1");
        ConfigFixture.Item item = ConfigFixture.item("vendor thing", "POST", url);
        fixture.collectionOf("Vendor", ConfigFixture.items(savedBody == null ? item : item.body(savedBody)));
        return fixture;
    }

    private RawService service(ConfigFixture fixture, boolean live, RawRequestClient client) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", live ? "live" : "mock"));
        return new RawService(epa, fixture.gateway, client, new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK));
    }

    private static RawRunRequest request(RequestRef ref, String body) {
        return RawRunRequest.of(ref, body, Map.of(), null);
    }

    @Test
    void withNoBodySuppliedTheSavedBodyIsSentResolvedWithItsTypedCredentialMaskedAndNothingOfItIsRecorded() {
        ConfigFixture fixture = fixture(true, "{{baseUrl}}/Thing", SAVED);
        RequestRef ref = fixture.ref(0, "vendor thing");

        RunEnvelope<RawDetail> result = service(fixture, true, capturing()).run(request(ref, null), RunOrigin.manual(), null);

        // What goes out is the saved body, every placeholder filled, the credential as typed.
        assertThat(sentBody.get().value()).isEqualTo("{\"memberId\": \"S123456789\", \"client_secret\": \"ZZTYPEDSECRET-1\", \"tenant\": \"T-1\"}");
        // Everything that is shown masks the credential.
        assertThat(sentBody.get().display()).doesNotContain("ZZTYPEDSECRET-1").contains("T-1");
        assertThat(sentBody.get().template()).doesNotContain("ZZTYPEDSECRET-1");
        assertThat(result.detail().bodySource()).isEqualTo(RawDetail.BODY_SAVED);
        assertThat(result.detail().requestNotes()).containsExactly("The body is the one saved with the collection request, with its "
                + "placeholders filled in, because none was supplied for this run.");
        assertThat(result.verdict().supporting()).contains("This request was sent as written. The tool does not interpret its answer: "
                + "everything that came back is listed below.");
        // The ledger line holds neither the member data of the body nor the credential.
        String line = JsonSafe.mapper().writeValueAsString(ledger.only());
        assertThat(line).doesNotContain("S123456789").doesNotContain("ZZTYPEDSECRET-1").doesNotContain("T-1");
        // No body was typed for this run, so the line does not say one was.
        assertThat(ledger.only().unrecordedInputs()).doesNotContain(org.p32h.interop.epa.pave.model.RunRecord.INPUT_BODY);
    }

    @Test
    void aBodySuppliedForTheRunIsSentInPlaceOfTheSavedOne() {
        ConfigFixture fixture = fixture(true, "{{baseUrl}}/Thing", SAVED);
        RunEnvelope<RawDetail> result = service(fixture, true, capturing()).run(request(fixture.ref(0, "vendor thing"), "{\"a\": \"{{tenant}}\"}"),
                RunOrigin.manual(), null);
        assertThat(sentBody.get().value()).isEqualTo("{\"a\": \"T-1\"}");
        assertThat(result.detail().bodySource()).isEqualTo(RawDetail.BODY_SUPPLIED);
        assertThat(result.detail().requestNotes()).containsExactly("The body is the one supplied for this run, with its placeholders filled in.");
    }

    @Test
    void aRequestWithNoSavedBodySendsNoneAndSaysSo() {
        ConfigFixture fixture = fixture(true, "{{baseUrl}}/Thing", null);
        RunEnvelope<RawDetail> result = service(fixture, true, capturing()).run(request(fixture.ref(0, "vendor thing"), " "), RunOrigin.manual(),
                null);
        assertThat(sentBody.get()).isNull();
        assertThat(result.detail().bodySource()).isEqualTo(RawDetail.BODY_NONE);
        assertThat(result.detail().requestNotes()).containsExactly("There is no body: none was supplied for this run, and the collection "
                + "request has none saved.");
    }

    @Test
    void aSavedBodyWithAPlaceholderNoLayerFillsIsRefusedNamingItOnBothProfilesAndNothingIsRecorded() {
        for (boolean live : List.of(true, false)) {
            ConfigFixture fixture = fixture(live, "{{baseUrl}}/Thing", "{\"x\": \"{{notDefinedAnywhere}}\"}");
            RawRequestClient client = live ? capturing() : new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP));
            assertThatThrownBy(() -> service(fixture, live, client).run(request(fixture.ref(0, "vendor thing"), null), RunOrigin.manual(), null))
                    .as(live ? "live" : "mock").isInstanceOfSatisfying(UnresolvedVariablesException.class,
                            e -> assertThat(e.variables()).containsExactly("notDefinedAnywhere"));
            assertThat(ledger.records()).as(live ? "live" : "mock").isEmpty();
        }
    }

    @Test
    void onTheMockProfileTheResultNeverSaysTheRequestWasSent() {
        ConfigFixture fixture = fixture(false, "{{baseUrl}}/Thing", SAVED);
        RunEnvelope<RawDetail> result = service(fixture, false, new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP)))
                .run(request(fixture.ref(0, "vendor thing"), null), RunOrigin.manual(), null);
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.verdict().supporting()).contains("On the mock profile nothing was sent: a scenario answered in place of the "
                + "service. The tool does not interpret the answer: everything that came back is listed below.");
        assertThat(String.join(" ", result.verdict().supporting())).doesNotContain("was sent as written");
        // The saved body is what the wire view shows, the credential masked.
        assertThat(result.wire().request().body()).contains("S123456789").contains("T-1").doesNotContain("ZZTYPEDSECRET-1");
        assertThat(result.detail().bodySource()).isEqualTo(RawDetail.BODY_SAVED);
    }

    @Test
    void thePreviewShowsTheSavedBodyMaskedAndSaysWhichBodyWouldGo() {
        ConfigFixture fixture = fixture(true, "{{baseUrl}}/Thing", SAVED);
        RawService service = service(fixture, true, capturing());
        RequestPreview saved = service.preview(request(fixture.ref(0, "vendor thing"), null), null);
        assertThat(saved.bodySource()).isEqualTo(RawDetail.BODY_SAVED);
        assertThat(saved.body()).contains("T-1").contains("S123456789").doesNotContain("ZZTYPEDSECRET-1");
        assertThat(saved.notes()).containsExactly("The body is the one saved with the collection request, with its placeholders filled in, "
                + "because none was supplied for this run.");
        RequestPreview supplied = service.preview(request(fixture.ref(0, "vendor thing"), "{}"), null);
        assertThat(supplied.bodySource()).isEqualTo(RawDetail.BODY_SUPPLIED);
        assertThat(supplied.body()).isEqualTo("{}");
        assertThat(ledger.records()).isEmpty();
        assertThat(sentBody.get()).isNull();
    }

    @Test
    void aUrlPartWithNoValueIsRefusedNamingItOnBothProfilesAndAValueForItIsEncodedIntoItsPlace() {
        for (boolean live : List.of(true, false)) {
            int before = ledger.records().size();
            ConfigFixture fixture = fixture(live, "{{baseUrl}}/Patient/{id}/Thing", null);
            RequestRef ref = fixture.ref(0, "vendor thing");
            RawRequestClient client = live ? capturing() : new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP));
            RawService service = service(fixture, live, client);
            String profile = live ? "live" : "mock";
            assertThatThrownBy(() -> service.run(request(ref, null), RunOrigin.manual(), null)).as(profile)
                    .isInstanceOfSatisfying(ValidationException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("The request cannot be sent: the url still has a part with no value, {id}. "
                                + "Nothing was sent.");
                        assertThat(e.fieldErrors()).extracting(org.p32h.interop.epa.pave.model.FieldError::field).containsExactly("urlValues.id");
                    });
            assertThat(ledger.records()).as(profile).hasSize(before);
            // The live gateway cannot prepare a url that is not complete, for a preview or for a send, so the preview is refused
            // with the same answer on both profiles.
            assertThatThrownBy(() -> service.preview(request(ref, null), null)).as(profile).isInstanceOfSatisfying(ValidationException.class,
                    e -> assertThat(e.fieldErrors()).extracting(org.p32h.interop.epa.pave.model.FieldError::field)
                            .containsExactly("urlValues.id"));

            // A value that would change the shape of the url is percent-encoded into its one place.
            RunEnvelope<RawDetail> filled = service.run(request(ref, null).withUrlValues(Map.of("id", "P32H-ONE?x=y#z")), RunOrigin.manual(),
                    null);
            // The stand-in live client answers with a canned exchange, so the url it was handed is the one compared.
            String sent = live ? sentCall.get().url().value() : filled.wire().request().url();
            assertThat(sent).as(profile).isEqualTo(BASE + "/Patient/P32H-ONE%3Fx%3Dy%23z/Thing");
            assertThat(ledger.records()).as(profile).hasSize(before + 1);
            // One that holds a slash would leave an encoded slash in the path, which a server in front of the service may
            // decode: it is refused, naming the url part, and nothing is sent or recorded.
            assertThatThrownBy(() -> service.run(request(ref, null).withUrlValues(Map.of("id", "P32H-ONE/../x?y")), RunOrigin.manual(), null))
                    .as(profile).isInstanceOf(org.p32h.interop.epa.pave.service.error.BadInputException.class)
                    .hasMessageContaining("which came from the url part {id}");
            assertThat(ledger.records()).as(profile).hasSize(before + 1);
        }
    }

    @Test
    void aValueForAUrlPartCannotMoveAWriteAndALockedProductionWriteWithOneIsBlocked() {
        ConfigFixture fixture = ConfigFixture.on(directory.resolve("live")).live().allowProdWrites().build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("vendor thing", "POST", "{{baseUrl}}/{tenant}/Thing")));
        RequestRef ref = fixture.ref(0, "vendor thing");
        RawService service = service(fixture, true, capturing());

        // A value of "." or ".." is the one value encoding leaves as it is, and it would take the request out of the path the
        // configuration wrote before it. It is refused as a form error before the gateway is asked (which refuses it for a
        // write as well, UrlPartGuardrailTest), and nothing is sent or recorded.
        for (String dots : List.of(".", "..")) {
            assertThatThrownBy(() -> service.run(request(ref, null).withUrlValues(Map.of("tenant", dots)), RunOrigin.manual(), null))
                    .as(dots).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                            .extracting(org.p32h.interop.epa.pave.model.FieldError::field).containsExactly("urlValues.tenant"));
        }
        assertThat(sentCall.get()).isNull();
        assertThat(ledger.records()).isEmpty();
        // A value that stays inside its segment goes.
        service.run(request(ref, null).withUrlValues(Map.of("tenant", "T-1")), RunOrigin.manual(), null);
        assertThat(sentCall.get().url().value()).isEqualTo(BASE + "/T-1/Thing");
        // An address typed as the value would sit in its one segment with encoded slashes, which no request carries in its path:
        // refused, and nothing more is sent.
        sentCall.set(null);
        assertThatThrownBy(() -> service.run(request(ref, null).withUrlValues(Map.of("tenant", "https://elsewhere.invalid")),
                RunOrigin.manual(), null)).isInstanceOf(org.p32h.interop.epa.pave.service.error.BadInputException.class);
        assertThat(sentCall.get()).isNull();

        // On a production environment nobody unlocked, a write with a value for its url is refused like any other write.
        fixture.activeEnvironmentOf("Onyx Production", EnvironmentKind.PROD, "baseUrl", "https://onyx-prod.example.invalid/fhir");
        sentCall.set(null);
        RunEnvelope<RawDetail> production = service.run(request(ref, null).withUrlValues(Map.of("tenant", "T-1")), RunOrigin.manual(), null);
        assertThat(RunSupport.isBlocked(production)).isTrue();
        assertThat(production.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(sentCall.get()).isNull();
        // A replay of it would be told it never carries the unlock, in the gateway's words and in its next action.
        RunEnvelope<RawDetail> replayed = service.run(request(ref, null).withUrlValues(Map.of("tenant", "T-1")),
                new RunOrigin(null, null, "run-00000000-0000-4000-8000-000000000001"), null);
        assertThat(replayed.nextStep().action()).isEqualTo(NextAction.SEND_ON_ITS_OWN);
        assertThat(replayed.verdict().headline()).contains("a replay never carries the production unlock");
        assertThat(replayed.error().action()).isEqualTo("Nothing was sent, and unlocking does not change this. To send this write to "
                + "production, send it on its own from the Config tab, with writes unlocked there, or switch to an environment that is "
                + "not production.");
        assertThat(replayed.nextStep().headline()).isEqualTo(NextAction.SEND_ON_ITS_OWN.label());
        assertThat(sentCall.get()).isNull();
    }

    @Test
    void aDotSegmentAsAUrlPartIsRefusedForARead() {
        ConfigFixture fixture = ConfigFixture.on(directory.resolve("live")).live().build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        fixture.collectionOf("Vendor", ConfigFixture.items(ConfigFixture.item("read a claim", "GET", "{{baseUrl}}/Claim/{claimId}")));
        RawService service = service(fixture, true, capturing());
        assertThatThrownBy(() -> service.run(request(fixture.ref(0, "read a claim"), null).withUrlValues(Map.of("claimId", "..")),
                RunOrigin.manual(), null)).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors())
                .extracting(org.p32h.interop.epa.pave.model.FieldError::message).containsExactly("A part of the url cannot be \".\" or "
                        + "\"..\": it would move the request to another path. Give the value the part is waiting for."));
        assertThat(sentCall.get()).isNull();
        // Dots inside a value are part of it, and go encoded into the one segment.
        service.run(request(fixture.ref(0, "read a claim"), null).withUrlValues(Map.of("claimId", "c..1")), RunOrigin.manual(), null);
        assertThat(sentCall.get().url().value()).isEqualTo(BASE + "/Claim/c..1");
    }

    @Test
    void aUrlWithAPlaceholderNoLayerFillsBesideAPartIsRefusedNamingEachWhereItIsGivenOnBothProfiles() {
        for (boolean live : List.of(true, false)) {
            String profile = live ? "live" : "mock";
            int before = ledger.records().size();
            ConfigFixture fixture = fixture(live, "{{baseUrl}}/{{undefinedSeg}}/Claim/{claimId}", null);
            RequestRef ref = fixture.ref(0, "vendor thing");
            RawRequestClient client = live ? capturing() : new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP));
            RawService service = service(fixture, live, client);

            for (java.util.function.Supplier<Object> attempt : List.<java.util.function.Supplier<Object>>of(
                    () -> service.run(request(ref, null), RunOrigin.manual(), null),
                    () -> service.preview(request(ref, null), null))) {
                assertThatThrownBy(attempt::get).as(profile).isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("The request cannot be sent: the url still has no value for {claimId}, "
                            + "{{undefinedSeg}}. Nothing was sent.");
                    assertThat(e.fieldErrors()).extracting(org.p32h.interop.epa.pave.model.FieldError::field)
                            .containsExactly("urlValues.claimId", "variables.undefinedSeg");
                    // The part is named where a raw send gives it, never as an identifier the call is for.
                    assertThat(e.fieldErrors().get(0).message()).isEqualTo("No value for {claimId}. Give the value this part of the url "
                            + "is waiting for.");
                });
            }
            // With the part given, the placeholder is still missing: still refused, on mock as on live, and nothing recorded.
            assertThatThrownBy(() -> service.run(request(ref, null).withUrlValues(Map.of("claimId", "c1")), RunOrigin.manual(), null))
                    .as(profile).isInstanceOfSatisfying(UnresolvedVariablesException.class,
                            e -> assertThat(e.variables()).containsExactly("undefinedSeg"));
            assertThat(ledger.records()).as(profile).hasSize(before);
            assertThat(sentCall.get()).as(profile).isNull();
        }
    }

    @Test
    void onTheMockProfileTheHeadlineSaysAScenarioAnswered() {
        ConfigFixture fixture = fixture(false, "{{baseUrl}}/Thing", null);
        RunEnvelope<RawDetail> result = service(fixture, false, new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP)))
                .run(request(fixture.ref(0, "vendor thing"), null), RunOrigin.manual(), null);
        assertThat(result.verdict().headline()).isEqualTo("A mock scenario, standing in for the service, answered (HTTP 200).");
        ConfigFixture liveFixture = fixture(true, "{{baseUrl}}/Thing", null);
        assertThat(service(liveFixture, true, capturing()).run(request(liveFixture.ref(0, "vendor thing"), null), RunOrigin.manual(), null)
                .verdict().headline()).isEqualTo("The service answered (HTTP 200).");
    }

    @Test
    void whenNoUsableAnswerCameBackAWriteThatMayHaveArrivedIsCheckedOnBeforeItIsSentAgain() {
        PreparedCall post = TestPreparedCalls.to(EpaOperation.UNMAPPED, "POST", BASE + "/Thing", List.of(),
                org.p32h.interop.epa.pave.model.EnvironmentRef.none());
        PreparedCall get = TestPreparedCalls.to(EpaOperation.UNMAPPED, "GET", BASE + "/Thing", List.of(),
                org.p32h.interop.epa.pave.model.EnvironmentRef.none());
        VendorExchange timedOut = TestExchanges.timeout(EpaOperation.UNMAPPED);
        VendorExchange refused = TestExchanges.unavailable(EpaOperation.UNMAPPED);
        VendorExchange failed = TestExchanges.of(EpaOperation.UNMAPPED, 503, null, false);

        NextStepView mayHave = RawService.noUsableAnswer(post, timedOut);
        assertThat(mayHave.headline()).isEqualTo("Next: check the environment, then find out whether the request took effect before "
                + "sending it again.");
        assertThat(mayHave.reason()).isEqualTo("No usable answer came back, and the service may have received the POST and acted on it. "
                + "Sending it again without checking could do the same thing twice. This is a finding about the environment.");
        assertThat(RawService.noUsableAnswer(post, failed).headline()).isEqualTo(mayHave.headline());
        assertThat(RawService.noUsableAnswer(post, refused).headline()).isEqualTo("Next: check the environment, then send again.");
        assertThat(RawService.noUsableAnswer(post, refused).reason()).contains("did not reach the service, so nothing was changed");
        assertThat(RawService.noUsableAnswer(get, timedOut).headline()).isEqualTo("Next: check the environment, then send again.");
        assertThat(List.of(mayHave, RawService.noUsableAnswer(get, timedOut))).extracting(NextStepView::action)
                .containsOnly(NextAction.CHECK_ENVIRONMENT);
    }
}
