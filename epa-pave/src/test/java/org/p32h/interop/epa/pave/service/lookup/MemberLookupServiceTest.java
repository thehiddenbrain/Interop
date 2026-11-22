package org.p32h.interop.epa.pave.service.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockMemberLookupClient;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireResponse;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/** Member lookup as a service: the ID as typed, the no-space form that is sent, and both on every result. */
class MemberLookupServiceTest {

    /** A client that records what it was asked and answers with a result that carries no forms at all. */
    private static final class RecordingClient implements MemberLookupClient {
        private final List<MemberIdForms> asked = new ArrayList<>();

        @Override
        public MemberLookupResult lookup(MemberIdForms memberId) {
            asked.add(memberId);
            WireView wire = new WireView(new WireRequest("GET", "https://members.example.invalid/members/" + memberId.sent(),
                    "https://members.example.invalid/members/{id}", List.of(), null, true, null),
                    new WireResponse(200, List.of(), "{}", 2L, true, null), null);
            return new MemberLookupResult(LookupStatus.FOUND, null, MemberDetail.builder().memberId(memberId.sent()).build(), List.of(),
                    List.of(), List.of(), wire, null, "Member found.").withMaskedReading(MASKED);
        }
    }

    /** The line a mapping sets when the result was read from a mask; the service must not drop it. */
    private static final String MASKED = "Read from a mask, not from what the service sent: coverageStatus was read as [secret statusWord].";

    private final RecordingClient client = new RecordingClient();
    private final MemberLookupService service = new MemberLookupService(client);

    @Test
    void aSpacedIdAndAnUnspacedIdGoThroughTheSamePathAndBothFormsAreOnTheResult() {
        MemberLookupResult spaced = service.lookup("S12345678   01");
        // The no-space form is what the client is given to send.
        assertThat(client.asked).containsExactly(new MemberIdForms("S12345678   01", "S1234567801", true));
        assertThat(spaced.memberId().typed()).isEqualTo("S12345678   01");
        assertThat(spaced.memberId().sent()).isEqualTo("S1234567801");
        assertThat(spaced.memberId().changed()).isTrue();
        // In the wire view too, beside the request that carried the sent form.
        assertThat(spaced.wire().memberId()).isEqualTo(spaced.memberId());
        assertThat(spaced.wire().request().url()).endsWith("/S1234567801");
        assertThat(spaced.wire().response().status()).isEqualTo(200);

        MemberLookupResult unspaced = service.lookup("S123456789");
        assertThat(client.asked.get(1)).isEqualTo(new MemberIdForms("S123456789", "S123456789", false));
        assertThat(unspaced.memberId().typed()).isEqualTo("S123456789");
        assertThat(unspaced.memberId().sent()).isEqualTo("S123456789");
        assertThat(unspaced.memberId().changed()).isFalse();
        assertThat(unspaced.wire().memberId()).isEqualTo(unspaced.memberId());
    }

    @Test
    void theIdIsKeptExactlyAsTypedAndOnlyWhitespaceIsRemovedFromWhatIsSent() {
        MemberLookupResult result = service.lookup("  s12345678\t01 ");
        assertThat(result.memberId().typed()).isEqualTo("  s12345678\t01 ");
        // Letter case and every other character are left alone: changing them would be guessing.
        assertThat(result.memberId().sent()).isEqualTo("s1234567801");
        assertThat(result.memberId().changed()).isTrue();
    }

    @Test
    void theRestOfWhatTheClientReturnedIsPassedOnUntouched() {
        MemberLookupResult result = service.lookup("S123456789");
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.member().memberId()).isEqualTo("S123456789");
        assertThat(result.message()).isEqualTo("Member found.");
        assertThat(result.error()).isNull();
        assertThat(result.maskedReading()).isEqualTo(MASKED);
    }

    @Test
    void aBlankIdIsRefusedBeforeAnyLookup() {
        for (String blank : Arrays.asList(null, "", "   ", "\t\n", "\u00a0\u2003")) {
            assertThatThrownBy(() -> service.lookup(blank)).isInstanceOfSatisfying(ValidationException.class, e -> {
                assertThat(e.fieldErrors()).containsExactly(new FieldError("memberId", "Enter a member ID. Nothing was looked up."));
                assertThat(e.getMessage()).isEqualTo("There is no member ID to look up. Nothing was sent.");
            });
        }
        assertThat(client.asked).isEmpty();
    }

    @Test
    void nothingIsRememberedBetweenLookups() {
        service.lookup("S123456789");
        service.lookup("S123456789");
        // Asked twice, answered twice: there is no cache in front of the client.
        assertThat(client.asked).hasSize(2);
        // And nowhere to keep one. The service holds the client, and the gateway it reads the environment's rule from.
        Field[] fields = MemberLookupService.class.getDeclaredFields();
        assertThat(fields).extracting(Field::getType).containsExactly(MemberLookupClient.class, ObjectProvider.class);
        assertThat(fields).allSatisfy(field -> {
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
        });
    }

    @Test
    void aLookupThatIsNotConfiguredSendsNothingSoItSaysNoFormTheIdWasSentIn() {
        // live-content-2: the card said "Sent as ..., rule: packed" when nothing was sent.
        MemberLookupService disabled = new MemberLookupService(memberId -> new MemberLookupResult(LookupStatus.DISABLED, memberId, null,
                List.of(), List.of(), List.of(), null, null, "Member lookup is switched off."), ruled(MemberIdRule.SPACED));
        MemberLookupResult result = disabled.lookup("S12345678   01");
        assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(result.wire()).isNull();
        assertThat(result.memberId()).isNull();
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain("memberId").doesNotContain("S123");
    }

    @Test
    void aLookupThatIsSentFormsTheIdByTheActiveEnvironmentsRule() {
        List<MemberIdForms> asked = new ArrayList<>();
        MemberLookupService spaced = new MemberLookupService(memberId -> {
            asked.add(memberId);
            return new MemberLookupResult(LookupStatus.NOT_FOUND, memberId, null, List.of(), List.of(), List.of(), null, null, "Not found.");
        }, ruled(MemberIdRule.SPACED));
        MemberLookupResult result = spaced.lookup("S1234567801");
        assertThat(result.memberId()).isEqualTo(MemberIds.forms("S1234567801", MemberIdRule.SPACED));
        assertThat(result.memberId().rule()).isEqualTo("spaced");
        assertThat(asked).singleElement().extracting(MemberIdForms::sent).isEqualTo(MemberIds.sent("S1234567801", MemberIdRule.SPACED));
        // With no gateway, the packed rule, as before rules could be chosen.
        assertThat(new MemberLookupService(memberId -> new MemberLookupResult(LookupStatus.NOT_FOUND, memberId, null, List.of(), List.of(),
                List.of(), null, null, "Not found.")).lookup("S12345678 01").memberId().rule()).isEqualTo("packed");
    }

    /** A gateway that answers only the member ID rule: nothing else is asked of it by a lookup. */
    private static ObjectProvider<OperationGateway> ruled(MemberIdRule rule) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gatewayWith(rule));
        return beans.getBeanProvider(OperationGateway.class);
    }

    private static OperationGateway gatewayWith(MemberIdRule rule) {
        return new OperationGateway() {
            @Override
            public PreparedCall prepare(CallIntent intent) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedText resolve(String template, Map<String, String> runValues) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EnvironmentRef activeEnvironment() {
                throw new UnsupportedOperationException();
            }

            @Override
            public WriteAccess writeAccess(ProdUnlock unlock) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long epoch() {
                return 0;
            }

            @Override
            public MemberIdRule memberIdRule() {
                return rule;
            }
        };
    }

    @Test
    void aClientThatReturnsNothingIsADefectAndIsNotPassedOffAsAnAnswer() {
        MemberLookupService broken = new MemberLookupService(memberId -> null);
        assertThatThrownBy(() -> broken.lookup("S123456789")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("S123456789");
    }

    @Test
    void theSyntheticMedicareMemberIsFoundHoweverTheIdIsTyped() {
        MemberLookupService onMock = new MemberLookupService(new MockMemberLookupClient(new LookupFixtures(), LookupProperties.defaults(),
                Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"))));
        MemberLookupResult spaced = onMock.lookup("S12345678   01");
        MemberLookupResult unspaced = onMock.lookup("S1234567801");
        assertThat(spaced.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(spaced.member()).isEqualTo(unspaced.member());
        assertThat(spaced.member().lineOfBusiness()).isEqualTo("Medicare");
        assertThat(spaced.memberId().changed()).isTrue();
        assertThat(unspaced.memberId().changed()).isFalse();
        assertThat(spaced.wire().memberId().typed()).isEqualTo("S12345678   01");
        // An unspaced public plan ID through the same path.
        assertThat(onMock.lookup("S123456789").member().lineOfBusiness()).isEqualTo("Medicaid");
    }
}
