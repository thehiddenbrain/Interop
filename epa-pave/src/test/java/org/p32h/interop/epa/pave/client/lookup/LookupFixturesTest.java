package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;

/**
 * The fixture loader: what a file may hold, and that a file it cannot use stops the application
 * with the file named, and is never skipped.
 */
class LookupFixturesTest {

    private static final String NONE = "classpath*:lookup/no-such-directory/*.json";

    private static LookupFixtures.Fixture member(String json) {
        return LookupFixtures.parse("test.json", json, "memberId");
    }

    private static LookupFixtures.Fixture provider(String json) {
        return LookupFixtures.parse("test.json", json, "npi");
    }

    @Test
    void aFileHoldsAnIdentifierAndWhatAnApiWouldAnswerForIt() {
        LookupFixtures.Fixture respond = member("{\"memberId\":\"P32H-TEST\",\"description\":\"A test.\",\"httpStatus\":200,"
                + "\"response\":{\"memberId\":\"P32H-TEST\"}}");
        assertThat(respond.identifier()).isEqualTo("P32H-TEST");
        assertThat(respond.source()).isEqualTo("test.json");
        assertThat(respond.description()).isEqualTo("A test.");
        // A missing behavior means respond, as in a scenario file.
        assertThat(respond.behavior()).isEqualTo(ScenarioBehavior.RESPOND);
        assertThat(respond.httpStatus()).isEqualTo(200);
        assertThat(respond.body()).contains("\"memberId\"").contains("P32H-TEST");
        assertThat(respond.json()).isTrue();

        LookupFixtures.Fixture text = provider("{\"npi\":\"1234567893\",\"behavior\":\"respond\",\"httpStatus\":502,\"responseText\":\"<html>\"}");
        assertThat(text.body()).isEqualTo("<html>");
        assertThat(text.json()).isFalse();
        assertThat(text.httpStatus()).isEqualTo(502);

        LookupFixtures.Fixture noBody = member("{\"memberId\":\"S123456789\",\"httpStatus\":204}");
        assertThat(noBody.body()).isNull();
        assertThat(member("{\"memberId\":\"S123456789\",\"httpStatus\":200,\"response\":null}").body()).isNull();

        LookupFixtures.Fixture timeout = member("{\"memberId\":\"S1234567801\",\"behavior\":\"timeout\"}");
        assertThat(timeout.behavior()).isEqualTo(ScenarioBehavior.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();
        assertThat(provider("{\"npi\":1234567893,\"behavior\":\"UNAVAILABLE\"}").behavior()).isEqualTo(ScenarioBehavior.UNAVAILABLE);
    }

    @Test
    void aMemberFixtureIsRefusedUnlessItsIdIsASentinelOrAPlaceholder() {
        // The mock profile is free of member data by construction: this is the construction.
        for (String id : new String[] {"NOT-A-PLACEHOLDER", "p32h-lower-case", "P32H-", "S12345678", "S12345678901", "X123456789",
                "S12345678A", "123456789"}) {
            assertThatThrownBy(() -> member("{\"memberId\":\"" + id + "\",\"httpStatus\":200}")).as(id)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("neither a P32H- sentinel nor a placeholder");
        }
        // The key is the form that is sent, so the written form with spaces is not a key.
        assertThatThrownBy(() -> member("{\"memberId\":\"S12345678   01\",\"httpStatus\":200}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no-space form");
        for (String id : new String[] {"P32H-PA-REQ", "P32H-X", "P32H-LOOKUP-FAULT-2", "S123456789", "S1234567801"}) {
            assertThat(member("{\"memberId\":\"" + id + "\",\"httpStatus\":200}").identifier()).isEqualTo(id);
        }
    }

    @Test
    void aProviderFixtureIsRefusedUnlessItsNpiPassesTheApplicationsOwnCheck() {
        for (String npi : new String[] {"1234567890", "123456789", "12345678931", "123456789X", "1234567894"}) {
            assertThatThrownBy(() -> provider("{\"npi\":\"" + npi + "\",\"httpStatus\":200}")).as(npi)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not pass the NPI check")
                    .hasMessageContaining("Npi.withCheckDigit");
        }
    }

    @Test
    void aFileThatDoesNotSayWhatToAnswerIsRefusedWithTheReason() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("{\"memberId\":", "not valid JSON");
        refused.put("[]", "one JSON object");
        refused.put("{\"httpStatus\":200}", "no memberId");
        refused.put("{\"memberId\":\"  \",\"httpStatus\":200}", "no memberId");
        refused.put("{\"memberId\":\"S123456789\"}", "no httpStatus");
        refused.put("{\"memberId\":\"S123456789\",\"httpStatus\":\"OK\"}", "no httpStatus");
        refused.put("{\"memberId\":\"S123456789\",\"httpStatus\":99}", "no httpStatus");
        refused.put("{\"memberId\":\"S123456789\",\"behavior\":\"explode\"}", "respond, timeout or unavailable");
        refused.put("{\"memberId\":\"S123456789\",\"httpStatus\":200,\"response\":{},\"responseText\":\"x\"}", "both a response and a responseText");
        refused.put("{\"memberId\":\"S123456789\",\"httpStatus\":200,\"responseText\":{}}", "responseText is not text");
        refused.put("{\"memberId\":\"S123456789\",\"behavior\":\"timeout\",\"response\":{}}", "never answers");
        refused.put("{\"memberId\":\"S123456789\",\"behavior\":\"unavailable\",\"httpStatus\":503}", "never answers");
        // A misspelt key is named. Dropped in silence, this file would be an API that answers with nothing.
        refused.put("{\"memberId\":\"S123456789\",\"httpStatus\":200,\"respnse\":{},\"behaviour\":\"timeout\"}", "respnse, behaviour");
        refused.forEach((json, reason) -> assertThatThrownBy(() -> member(json)).as(json)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason));
        // A provider file is not read with the member key, and the other way round.
        assertThatThrownBy(() -> provider("{\"memberId\":\"S123456789\",\"httpStatus\":200}")).hasMessageContaining("memberId");
    }

    @Test
    void theBundledFixturesLoadAndAreFoundWhateverTheLetterCase() {
        LookupFixtures fixtures = new LookupFixtures();
        assertThat(fixtures.member("P32H-PA-REQ")).isNotNull();
        assertThat(fixtures.member("p32h-pa-req")).isSameAs(fixtures.member("P32H-PA-REQ"));
        assertThat(fixtures.member("S12345678   01")).isSameAs(fixtures.member("S1234567801"));
        assertThat(fixtures.member("P32H-NOMEM")).isNull();
        assertThat(fixtures.member(null)).isNull();
        assertThat(fixtures.member("")).isNull();
        assertThat(fixtures.provider("1234567893").source()).isEqualTo("1234567893.json");
        assertThat(fixtures.provider(" 1234567893 ")).isNotNull();
        assertThat(fixtures.provider("9999999995")).isNull();
        assertThat(fixtures.provider(null)).isNull();
    }

    @Test
    void aFileAddedToADirectoryIsPickedUpWithNoChangeToAnyOther() {
        LookupFixtures fixtures = new LookupFixtures("classpath*:lookup/fixtures-extra/members/*.json",
                "classpath*:lookup/fixtures-extra/providers/*.json");
        assertThat(fixtures.members()).extracting(LookupFixtures.Fixture::identifier).containsExactly("P32H-TEST-EXTRA");
        assertThat(fixtures.member("P32H-TEST-EXTRA").source()).isEqualTo("p32h-test-extra.json");
        assertThat(fixtures.providers()).singleElement().satisfies(fixture -> {
            assertThat(fixture.identifier()).isEqualTo("1000000061");
            assertThat(fixture.behavior()).isEqualTo(ScenarioBehavior.UNAVAILABLE);
        });
        // A directory with nothing in it is an API that knows nobody, not a failure to start.
        LookupFixtures empty = new LookupFixtures(NONE, NONE);
        assertThat(empty.members()).isEmpty();
        assertThat(empty.member("P32H-PA-REQ")).isNull();
    }

    @Test
    void aFileThatCannotBeUsedStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> new LookupFixtures("classpath*:lookup/fixtures-duplicate/members/*.json", NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("second.json").hasMessageContaining("first.json")
                .hasMessageContaining("One identifier returns one record");
        assertThatThrownBy(() -> new LookupFixtures(NONE, "classpath*:lookup/fixtures-broken/providers/*.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hand-typed-npi.json")
                .hasMessageContaining("does not pass the NPI check");
    }
}
