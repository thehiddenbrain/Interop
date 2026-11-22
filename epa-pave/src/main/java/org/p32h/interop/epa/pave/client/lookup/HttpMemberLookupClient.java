package org.p32h.interop.epa.pave.client.lookup;

import java.time.Clock;
import java.time.LocalDate;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.p32h.interop.epa.pave.service.lookup.SavedLookups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Member lookup on the live profile: one call to the member API for one member ID.
 *
 * <p>What comes back is a real member. It is returned for the screen and goes nowhere else: it is
 * not cached, not kept and not logged. The response is read by {@link MemberResponseMapping#map},
 * which the mock client uses too, and everything this tool assumes about the API is in that file.
 *
 * <p>A lookup saved on screen (R-11) replaces application.yml's wholly: its own method, address,
 * sign-in, headers, member ID form, timeouts and the places its answer holds a match and each field.
 */
@Component
@Profile("live")
public class HttpMemberLookupClient implements MemberLookupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMemberLookupClient.class);

    private final LookupSender sender;
    private final SavedLookups saved;
    private final Clock clock;

    @Autowired
    public HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                  RestClient.Builder builder, Clock clock, SavedLookups saved) {
        // The lookup timeouts, which are shorter than the protocol timeouts: a lookup sits in front
        // of a tester finishing a field, so it gives up quickly and lets them type.
        this.sender = new LookupSender(EpaOperation.MEMBER_LOOKUP, LookupWords.MEMBER, lookup.member(), executor, tokens, gateway,
                RestClients.withTimeouts(builder, lookup.member().connectTimeoutMs(), lookup.member().readTimeoutMs()), builder);
        this.saved = saved;
        this.clock = clock;
    }

    /** As the application's constructor with no lookup saved on screen, for tests that build the client the application's way. */
    public HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                  RestClient.Builder builder, Clock clock) {
        this(executor, tokens, gateway, lookup, builder, clock, SavedLookups.NONE);
    }

    /** For tests, which bind the client to a mock server. */
    HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                           RestClient client, Clock clock) {
        this(executor, tokens, gateway, lookup, client, clock, SavedLookups.NONE);
    }

    /** For tests of a lookup saved on screen, bound to one client. */
    HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                           RestClient client, Clock clock, SavedLookups saved) {
        this.sender = new LookupSender(EpaOperation.MEMBER_LOOKUP, LookupWords.MEMBER, lookup.member(), executor, tokens, gateway, client);
        this.saved = saved;
        this.clock = clock;
    }

    @Override
    public MemberLookupResult lookup(MemberIdForms memberId) {
        return lookup(memberId, null);
    }

    @Override
    public MemberLookupResult lookup(MemberIdForms memberId, LocalDate dateOfService) {
        LookupSettings settings = saved.saved(LookupKind.MEMBER);
        // The packed form, whatever rule the environment's own requests follow: application.yml's
        // lookup always sent it, and every rule starts from it.
        String packed = MemberIds.noSpace(memberId.typed());
        LookupSender.Attempt attempt;
        LookupReading reading;
        if (settings == null) {
            attempt = sender.send(packed);
            reading = LookupReading.usual(LookupWords.MEMBER);
        } else {
            String asked = inItsForm(memberId.typed(), settings.idForm());
            attempt = sender.send(settings, asked, dateOfService);
            reading = LookupReading.saved(LookupWords.MEMBER, LookupKind.MEMBER, settings, asked);
        }
        MemberLookupResult result = attempt.call() == null
                ? MemberResponseMapping.notSent(attempt.notSent(), memberId, attempt.request())
                : MemberResponseMapping.map(attempt.call(), memberId, LocalDate.now(clock), reading);
        // The outcome and the HTTP status only. Never the member ID, never the url or the body,
        // which hold the ID, and never anything the API returned.
        log.info("Member lookup ended {} with HTTP status {}", result.status(),
                attempt.call() == null || attempt.call().httpStatus() == null ? "none" : attempt.call().httpStatus());
        return result;
    }

    /** The member ID in the form the saved lookup states (R-11): as typed, less the spaces around it, or by one of the rules. */
    static String inItsForm(String typed, String idForm) {
        if (LookupSettings.AS_TYPED.equals(idForm)) {
            return typed == null ? "" : typed.strip();
        }
        MemberIdRule rule = MemberIdRule.parse(idForm);
        return (rule == null ? MemberIdRule.PACKED : rule).apply(typed);
    }
}
