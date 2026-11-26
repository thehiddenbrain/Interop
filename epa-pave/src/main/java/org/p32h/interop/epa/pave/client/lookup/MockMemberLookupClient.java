package org.p32h.interop.epa.pave.client.lookup;

import java.time.Clock;
import java.time.LocalDate;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Member lookup on the mock profile. It answers from the synthetic fixture files and needs no
 * configuration, no credentials and no network.
 *
 * <p>Every member it can return is invented. See {@link LookupFixtures} for how that is enforced.
 * A fixture holds a response in the shape an API might send, and that response goes through
 * {@link MemberResponseMapping#map}, the same method the live client uses.
 *
 * <p>A member ID with no fixture is not found. {@code P32H-NOMEM} is one on purpose, so the
 * sentinel for "member not found" is not found here too.
 *
 * <p>Four sentinels exist only to show a state of the lookup itself without a network:
 * <ul>
 *   <li>{@code P32H-LOOKUP-FAULT}: the member API fails with HTTP 503. The fault state</li>
 *   <li>{@code P32H-LOOKUP-TIMEOUT}: the member API does not answer in time</li>
 *   <li>{@code P32H-LOOKUP-MULTI}: two records come back for one ID: several matched, which is
 *       never answered by choosing one</li>
 *   <li>{@code P32H-LOOKUP-NONE}: HTTP 200 with an empty list, the other way of saying not found</li>
 * </ul>
 * {@code P32H-INACTIVE} is found with coverage that is not active, which is the "needs a look"
 * state. Setting {@code lookup.member.enabled} to false shows the disabled state. A lookup saved
 * on screen changes nothing here: on mock, lookups answer from the fixtures whatever is configured
 * (R-29), so the Members page can be filled in on mock and used on live.
 */
@Component
@Profile("mock")
public class MockMemberLookupClient implements MemberLookupClient {

    private final LookupFixtures fixtures;
    private final LookupProperties lookup;
    private final Clock clock;

    public MockMemberLookupClient(LookupFixtures fixtures, LookupProperties lookup, Clock clock) {
        this.fixtures = fixtures;
        this.lookup = lookup;
        this.clock = clock;
    }

    @Override
    public MemberLookupResult lookup(MemberIdForms memberId) {
        LookupProperties.Endpoint endpoint = lookup.member();
        if (!endpoint.enabled()) {
            return MemberResponseMapping.notSent(LookupOutcomes.switchedOff(LookupWords.MEMBER), memberId, null);
        }
        // The packed form, whatever the active environment's rule makes of it, so a sentinel picks its fixture under every rule.
        LookupFixtures.Fixture fixture = fixtures.member(org.p32h.interop.epa.pave.model.MemberIds.noSpace(memberId.typed()));
        VendorExchange exchange = fixture == null ? MockLookups.unknown(endpoint.url(), LookupWords.MEMBER)
                : MockLookups.answer(fixture, endpoint.url());
        return MemberResponseMapping.map(exchange, memberId, LocalDate.now(clock));
    }
}
