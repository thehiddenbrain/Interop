package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UrlFormsTest {

    private static final VariableResolver RESOLVER = new VariableResolver(name -> null,
            Clock.fixed(Instant.parse("2026-01-05T15:04:05Z"), ZoneOffset.UTC), () -> "00000000-0000-4000-8000-000000000001");

    /** A word with one letter outside ASCII, written as a number so this source stays plain ASCII. */
    private static final String ACCENTED = "caf" + (char) 0xE9;

    /** Every character that could change the shape of a url if it were substituted as written. */
    private static final String[] AWKWARD = {"S12345678   01", "a/b", "100%", "a&b=c", "what?", "frag#ment", "plus+sign", "semi;colon",
            "../../admin", ACCENTED, "a b/c%d&e?f#g"};

    private static UrlForms.Skeleton skeleton(String template, String... keysAndValues) {
        Map<String, PostmanVariable> variables = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            variables.put(keysAndValues[i], new PostmanVariable(keysAndValues[i], keysAndValues[i + 1], true, "default"));
        }
        return UrlForms.skeleton(RESOLVER.resolve(template, List.of(VariableLayer.environment("Test", variables))));
    }

    @Test
    void eachAwkwardCharacterIsPercentEncoded() {
        assertThat(UrlForms.encode(" ")).isEqualTo("%20");
        assertThat(UrlForms.encode("/")).isEqualTo("%2F");
        assertThat(UrlForms.encode("%")).isEqualTo("%25");
        assertThat(UrlForms.encode("&")).isEqualTo("%26");
        assertThat(UrlForms.encode("?")).isEqualTo("%3F");
        assertThat(UrlForms.encode("#")).isEqualTo("%23");
        assertThat(UrlForms.encode("+")).isEqualTo("%2B");
        assertThat(UrlForms.encode("=")).isEqualTo("%3D");
        assertThat(UrlForms.encode(ACCENTED)).isEqualTo("caf%C3%A9");
        assertThat(UrlForms.encode("AZaz09-._~")).isEqualTo("AZaz09-._~");
        assertThat(UrlForms.encode("S12345678   01")).isEqualTo("S12345678%20%20%2001");
        assertThat(UrlForms.encode(null)).isEmpty();
    }

    @Test
    void aValueInThePathFormStaysOneSegmentWhateverItHolds() {
        for (String value : AWKWARD) {
            UrlForms.Filled filled = skeleton("https://lookup.example.invalid/api/members/{id}/coverage").fill(Map.of("id", value));

            assertThat(filled.complete()).as(value).isTrue();
            URI uri = URI.create(filled.send());
            // Still four segments, with the same neighbours, and nothing moved into the query or the fragment.
            assertThat(uri.getRawPath().split("/")).as(value).hasSize(5);
            assertThat(uri.getRawPath()).as(value).startsWith("/api/members/").endsWith("/coverage");
            assertThat(uri.getRawQuery()).as(value).isNull();
            assertThat(uri.getRawFragment()).as(value).isNull();
            assertThat(uri.getHost()).as(value).isEqualTo("lookup.example.invalid");
            // And it decodes to exactly what was supplied.
            assertThat(URLDecoder.decode(uri.getRawPath().split("/")[3], StandardCharsets.UTF_8)).as(value).isEqualTo(value);
            assertThat(filled.display()).as(value).isEqualTo(filled.send());
        }
    }

    @Test
    void aValueInTheQueryFormStaysOneValueWhateverItHolds() {
        for (String value : AWKWARD) {
            UrlForms.Filled filled = skeleton("https://lookup.example.invalid/api/members?memberId={id}&active=true").fill(Map.of("id", value));

            assertThat(filled.complete()).as(value).isTrue();
            URI uri = URI.create(filled.send());
            assertThat(uri.getRawPath()).as(value).isEqualTo("/api/members");
            assertThat(uri.getRawFragment()).as(value).isNull();
            String[] parameters = uri.getRawQuery().split("&");
            // Two parameters went in and two come out: the value added none and ended none.
            assertThat(parameters).as(value).hasSize(2);
            assertThat(parameters[1]).as(value).isEqualTo("active=true");
            assertThat(parameters[0]).as(value).startsWith("memberId=");
            assertThat(URLDecoder.decode(parameters[0].substring("memberId=".length()), StandardCharsets.UTF_8)).as(value).isEqualTo(value);
        }
    }

    @Test
    void aPostmanPathVariableIsFilledTheSameWay() {
        UrlForms.Skeleton skeleton = skeleton("{{baseUrl}}/Subscription/:id/$status", "baseUrl", "https://host.example.invalid:8443/fhir");

        assertThat(skeleton.tokenNames()).containsExactly("id");
        UrlForms.Filled filled = skeleton.fill(Map.of("id", "sub 1/x"));
        assertThat(filled.send()).isEqualTo("https://host.example.invalid:8443/fhir/Subscription/sub%201%2Fx/$status");
    }

    @Test
    void aPortASchemeAndAColonInTheQueryAreNotPathVariables() {
        assertThat(skeleton("https://host.example.invalid:8443/fhir/Claim").tokenNames()).isEmpty();
        assertThat(skeleton("https://host.example.invalid/fhir/Claim?time=:now&ref=/:id").tokenNames()).isEmpty();
        assertThat(skeleton("{{baseUrl}}:8443/fhir", "baseUrl", "https://host.example.invalid").tokenNames()).isEmpty();
        // With the base missing the scheme cannot be seen, and a port still is not a path variable.
        assertThat(skeleton("{{baseUrl}}:8443/fhir/:id").tokenNames()).containsExactly("id");
        assertThat(skeleton("https://host.example.invalid/a/:id-with-more/b").tokenNames()).containsExactly("id-with-more");
        assertThat(skeleton("https://host.example.invalid/a/x:id/b").tokenNames()).isEmpty();
        assertThat(skeleton("https://host.example.invalid/a/:/b").tokenNames()).isEmpty();
    }

    @Test
    void bothTokenFormsCanAppearInOneUrlAndEachNameIsFilledWhereverItAppears() {
        UrlForms.Skeleton skeleton = skeleton("https://x.example.invalid/{tenant}/members/:id?also={id}");

        assertThat(skeleton.tokenNames()).containsExactly("tenant", "id");
        assertThat(skeleton.fill(Map.of("tenant", "p32h", "id", "S123456789")).send())
                .isEqualTo("https://x.example.invalid/p32h/members/S123456789?also=S123456789");
    }

    @Test
    void aTokenWithNoValueIsLeftAsWrittenAndListed() {
        UrlForms.Skeleton skeleton = skeleton("https://x.example.invalid/members/{id}?tenant={tenant}");

        UrlForms.Filled none = skeleton.fill(Map.of());
        assertThat(none.complete()).isFalse();
        assertThat(none.send()).isNull();
        assertThat(none.display()).isNull();
        assertThat(none.missingTokens()).containsExactly("id", "tenant");
        assertThat(none.shown()).isEqualTo("https://x.example.invalid/members/{id}?tenant={tenant}");

        // A blank value is no value. Substituted, it would send the request to /members/.
        UrlForms.Filled blank = skeleton.fill(Map.of("id", "  ", "tenant", "p32h"));
        assertThat(blank.complete()).isFalse();
        assertThat(blank.missingTokens()).containsExactly("id");
        assertThat(blank.shown()).isEqualTo("https://x.example.invalid/members/{id}?tenant=p32h");
        assertThat(skeleton.fill(null).missingTokens()).containsExactly("id", "tenant");
    }

    @Test
    void aTokenThatArrivesInsideAVariableValueIsFilledToo() {
        UrlForms.Skeleton skeleton = skeleton("{{memberLookupUrl}}", "memberLookupUrl", "https://x.example.invalid/members/{id}");

        assertThat(skeleton.tokenNames()).containsExactly("id");
        assertThat(skeleton.fill(Map.of("id", "S123456789")).send()).isEqualTo("https://x.example.invalid/members/S123456789");
    }

    @Test
    void aDoubleBracePlaceholderIsNeverTakenForAToken() {
        UrlForms.Skeleton skeleton = skeleton("https://x.example.invalid/{{missing}}/members/{id}");

        assertThat(skeleton.tokenNames()).containsExactly("id");
        assertThat(skeleton.hasMissing()).isTrue();
        UrlForms.Filled filled = skeleton.fill(Map.of("id", "S123456789", "missing", "must-not-be-used"));
        assertThat(filled.complete()).isFalse();
        assertThat(filled.shown()).isEqualTo("https://x.example.invalid/{{missing}}/members/S123456789");
        assertThat(filled.missingTokens()).isEmpty();
    }

    @Test
    void aSecretIsSentAsItIsShownAsItsMaskAndNeverSearchedForTokens() {
        UrlForms.Skeleton skeleton = skeleton("https://x.example.invalid/members/{id}?key={{apiKey}}", "apiKey", "hunter2{id}/:id");

        // The braces inside the secret are part of the secret. They are not a token and are not filled.
        assertThat(skeleton.tokenNames()).containsExactly("id");
        UrlForms.Filled filled = skeleton.fill(Map.of("id", "S123456789"));
        assertThat(filled.send()).isEqualTo("https://x.example.invalid/members/S123456789?key=hunter2{id}/:id");
        assertThat(filled.display()).isEqualTo("https://x.example.invalid/members/S123456789?key=[secret apiKey]");
        assertThat(filled.shown()).isEqualTo(filled.display());
        // The probe is what detection reads. It holds the real value and is never displayed.
        assertThat(filled.probe()).isEqualTo(filled.send());
        assertThat(filled.toString()).doesNotContain("hunter2").doesNotContain("S123456789");
        assertThat(skeleton.toString()).doesNotContain("hunter2");
    }

    @Test
    void theSegmentAfterAResourceNameIsFoundWithTheVariableThatFillsIt() {
        UrlForms.Segment byPlaceholder = skeleton("{{baseUrl}}/Subscription/{{subscriptionId}}", "baseUrl", "https://x.example.invalid/fhir",
                "subscriptionId", "sample-1").segmentAfter("Subscription");
        assertThat(byPlaceholder.placeholder()).isEqualTo("subscriptionId");
        assertThat(byPlaceholder.text()).isEqualTo("sample-1");
        assertThat(byPlaceholder.readable()).isTrue();

        // A placeholder nobody defines still marks the place.
        UrlForms.Segment undefined = skeleton("{{baseUrl}}/Subscription/{{subscriptionId}}/$status").segmentAfter("Subscription");
        assertThat(undefined.placeholder()).isEqualTo("subscriptionId");
        assertThat(undefined.readable()).isFalse();

        UrlForms.Segment literal = skeleton("https://x.example.invalid/fhir/Subscription/does-not-exist").segmentAfter("subscription");
        assertThat(literal.placeholder()).isNull();
        assertThat(literal.text()).isEqualTo("does-not-exist");
        assertThat(literal.readable()).isTrue();

        // The resource name itself arrives through a variable, and the id through another.
        UrlForms.Segment nested = skeleton("{{subscriptionUrl}}/{{sid}}", "subscriptionUrl", "{{baseUrl}}/Subscription",
                "baseUrl", "https://x.example.invalid/fhir", "sid", "abc").segmentAfter("Subscription");
        assertThat(nested.placeholder()).isEqualTo("sid");

        UrlForms.Segment mixed = skeleton("https://x.example.invalid/Subscription/sub-{{n}}", "n", "7").segmentAfter("Subscription");
        assertThat(mixed.placeholder()).isNull();
        assertThat(mixed.text()).isEqualTo("sub-7");

        assertThat(skeleton("https://x.example.invalid/fhir/Subscription").segmentAfter("Subscription")).isNull();
        assertThat(skeleton("https://x.example.invalid/fhir/Subscription/").segmentAfter("Subscription")).isNull();
        assertThat(skeleton("https://x.example.invalid/fhir/Claim/1").segmentAfter("Subscription")).isNull();
        // In the query it is not a path segment.
        assertThat(skeleton("https://x.example.invalid/fhir?next=/Subscription/1").segmentAfter("Subscription")).isNull();
    }

    @Test
    void aUrlIsCutJustAfterAMarkerThatEndsASegment() {
        UrlForms.Skeleton hook = skeleton("{{crdUrl}}/cds-services/{{serviceId}}?x=1", "crdUrl", "https://crd.example.invalid/r4",
                "serviceId", "order-sign-crd");

        assertThat(hook.upTo("/cds-services").fill(Map.of()).send()).isEqualTo("https://crd.example.invalid/r4/cds-services");
        // The marker arrives inside a variable value.
        assertThat(skeleton("{{hookUrl}}", "hookUrl", "https://crd.example.invalid/CDS-Services/order-sign").upTo("/cds-services")
                .fill(Map.of()).send()).isEqualTo("https://crd.example.invalid/CDS-Services");
        assertThat(skeleton("https://crd.example.invalid/cds-services-old/x").upTo("/cds-services")).isNull();
        assertThat(skeleton("https://crd.example.invalid/hooks/x").upTo("/cds-services")).isNull();
        // A base that has no value stays a hole in what is cut.
        UrlForms.Filled partial = skeleton("{{crdUrl}}/cds-services/{{serviceId}}").upTo("/cds-services").fill(Map.of());
        assertThat(partial.complete()).isFalse();
        assertThat(partial.shown()).isEqualTo("{{crdUrl}}/cds-services");
    }
}
