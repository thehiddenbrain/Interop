package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * N4 of the check of the guardrail fixes: a received notification says, as the wire view of a call
 * does, when a secret this tool could have sent was too short to be searched for, or could not be
 * worked out, so that a payload shown with such a secret in it does not pass for one found clean.
 */
class NotificationMaskNotesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));
    private static final String SUBSCRIPTION = "subscription-placeholder-0001";

    private final FakeGateway fake = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private NotificationReceiver receiver(OperationGateway gateway) {
        return new NotificationReceiver(gateway, new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.defaults(),
                ONYX, ledgerProvider(), CLOCK), ledgerProvider(), scenarios);
    }

    /** The fake gateway, giving these secrets to the receiver, or failing when asked for them. */
    private OperationGateway giving(SentSecrets secrets) {
        return new OperationGateway() {
            @Override
            public PreparedCall prepare(CallIntent intent) {
                return fake.prepare(intent);
            }

            @Override
            public ResolvedText resolve(String template, Map<String, String> runValues) {
                return fake.resolve(template, runValues);
            }

            @Override
            public EnvironmentRef activeEnvironment() {
                return fake.activeEnvironment();
            }

            @Override
            public WriteAccess writeAccess(ProdUnlock unlock) {
                return fake.writeAccess(unlock);
            }

            @Override
            public long epoch() {
                return fake.epoch();
            }

            @Override
            public SentSecrets secretsGivenToServices() {
                if (secrets == null) {
                    throw new IllegalStateException("the configuration cannot be read now");
                }
                return secrets;
            }
        };
    }

    private static final String BODY = bundle(status(SUBSCRIPTION, "event-notification"),
            NotificationJson.claimResponse("cr-short-1", null, "A1", "\"disposition\":\"echo ab12 and placeholder-long-secret-555555\""));

    @Test
    void aSecretTooShortToSearchForIsSaidInTheNotesAsTheWireViewSaysIt() {
        SentSecrets secrets = SentSecrets.builder().variable("shorty", "ab12").variable("longer", "placeholder-long-secret-555555").build();
        NotificationView view = receiver(giving(secrets)).receive(NotificationReceiver.Inbound.of(BODY));
        assertThat(view.payload()).doesNotContain("placeholder-long-secret-555555");
        assertThat(view.payloadNotes()).contains("This tool could have sent the service 1 secret shorter than 6 characters. A secret that "
                + "short is not searched for, because it would be found by accident, so this payload is not masked where it repeats that one.");

        SentSecrets two = SentSecrets.builder().variable("a", "ab12").variable("b", "cd3").build();
        assertThat(receiver(giving(two)).receive(NotificationReceiver.Inbound.of(BODY)).payloadNotes())
                .contains("This tool could have sent the service 2 secrets shorter than 6 characters. A secret that short is not searched "
                        + "for, because it would be found by accident, so this payload is not masked where it repeats one of those.");
    }

    @Test
    void aSecretThatCouldNotBeWorkedOutIsSaidInTheNotes() {
        String note = "At least one secret this tool could have sent to the service could not be worked out, so this payload was not "
                + "searched for it. Check what is shown here before you share it.";
        SentSecrets incomplete = SentSecrets.builder().incomplete().variable("longer", "placeholder-long-secret-555555").build();
        assertThat(receiver(giving(incomplete)).receive(NotificationReceiver.Inbound.of(BODY)).payloadNotes()).contains(note);
        // A configuration that cannot be read now: nothing was searched for, and the notes say so.
        NotificationView unread = receiver(giving(null)).receive(NotificationReceiver.Inbound.of(BODY));
        assertThat(unread.payloadNotes()).contains(note);
        // Nothing to search for and nothing unknown: no word about masking.
        assertThat(receiver(giving(SentSecrets.none())).receive(NotificationReceiver.Inbound.of(BODY)).payloadNotes())
                .noneMatch(each -> each.contains("secret"));
    }
}
