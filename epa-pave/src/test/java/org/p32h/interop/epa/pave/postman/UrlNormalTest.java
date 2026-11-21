package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one normal form, in the forms a server treats as the same address: a run of slashes, a dot
 * segment with parameters, a path parameter, letters in another case, another name of this machine.
 */
class UrlNormalTest {

    private static final UrlNormal.Destination LIVE_TWO = UrlNormal.destination("http://127.0.0.1:18472/live2");

    @Test
    void aRunOfSlashesInThePathIsOneSlashAndATrailingSlashStays() {
        assertThat(UrlNormal.text("http://127.0.0.1:18472//live2/Claim/$submit")).isEqualTo("http://127.0.0.1:18472/live2/Claim/$submit");
        assertThat(UrlNormal.text("http://127.0.0.1:18472/live2///Claim//$submit")).isEqualTo("http://127.0.0.1:18472/live2/Claim/$submit");
        assertThat(UrlNormal.text("http://127.0.0.1:18472/live2/")).isEqualTo("http://127.0.0.1:18472/live2/");
        assertThat(UrlNormal.text("{{baseUrl}}//Claim")).isEqualTo("{{baseUrl}}/Claim");
        // The query keeps what it holds.
        assertThat(UrlNormal.text("http://h.example.invalid/a?next=//b")).isEqualTo("http://h.example.invalid/a?next=//b");
    }

    @Test
    void aDotSegmentWithParametersIsADotSegment() {
        assertThat(UrlNormal.text("http://127.0.0.1:18471/live/Claim/$inquire/..;/..;/Claim?x=/Claim/$inquire"))
                .isEqualTo("http://127.0.0.1:18471/live/Claim?x=/Claim/$inquire");
        assertThat(UrlNormal.text("http://h.example.invalid/a/.;x=1/b")).isEqualTo("http://h.example.invalid/a/b");
        assertThat(UrlNormal.of("http://h.example.invalid/..;/x").climbed()).isTrue();
        // A segment that only starts with dots is an ordinary segment.
        assertThat(UrlNormal.text("http://h.example.invalid/a/..x;y/b")).isEqualTo("http://h.example.invalid/a/..x;y/b");
    }

    @Test
    void theFormsAServerTreatsAsTheSameAddressAreCoveredByIt() {
        for (String same : new String[] {"http://127.0.0.1:18472//live2", "http://127.0.0.1:18472/live2;x=1", "http://127.0.0.1:18472/LIVE2",
                "http://localhost:18472/live2", "http://LOCALHOST.:18472/live2/", "http://127.1:18472/live2", "http://2130706433:18472/live2",
                "http://0x7f.0.0.1:18472/live2", "http://[::1]:18472/live2", "http://user@127.0.0.1:18472/live2/Claim",
                "http://127.0.0.1:18472/live2;a=b/Claim/$submit", "http://127.0.0.1:18472/%6Cive2"}) {
            UrlNormal.Destination destination = UrlNormal.destination(same);
            assertThat(LIVE_TWO.covers(destination)).as(same).isTrue();
            assertThat(LIVE_TWO.overlaps(destination)).as(same).isTrue();
        }
        for (String other : new String[] {"http://127.0.0.1:18479/live2", "https://127.0.0.1:18472/live2", "http://127.0.0.1:18472/live",
                "http://127.0.0.1:18472/live22", "http://10.0.0.1:18472/live2", "http://example.invalid:18472/live2"}) {
            assertThat(LIVE_TWO.covers(UrlNormal.destination(other))).as(other).isFalse();
        }
        // An address beneath is covered; one above overlaps.
        assertThat(LIVE_TWO.overlaps(UrlNormal.destination("http://127.0.0.1:18472"))).isTrue();
    }

    @Test
    void aDestinationLeavesOutThePathParametersAndShowsTheHostAsWritten() {
        UrlNormal.Destination destination = UrlNormal.destination("http://Example.invalid:8080/live2;jsessionid=ZZ1/R4/");
        assertThat(destination.shown()).isEqualTo("http://example.invalid:8080/live2/R4");
    }

    @Test
    void anIpAddressIsComparedInOneForm() {
        assertThat(UrlNormal.hostKey("127.0.0.1")).isEqualTo(UrlNormal.hostKey("localhost"));
        assertThat(UrlNormal.hostKey("0.0.0.0")).isEqualTo(UrlNormal.hostKey("localhost"));
        assertThat(UrlNormal.hostKey("[::ffff:127.0.0.1]")).isEqualTo(UrlNormal.hostKey("localhost"));
        assertThat(UrlNormal.hostKey("0xc0.0250.1.1")).isEqualTo("192.168.1.1");
        assertThat(UrlNormal.hostKey("3232235777")).isEqualTo("192.168.1.1");
        assertThat(UrlNormal.hostKey("api.example.invalid")).isEqualTo("api.example.invalid");
        assertThat(UrlNormal.hostKey("1.2.3.4.5")).isEqualTo("1.2.3.4.5");
        assertThat(UrlNormal.hostKey("256.1.1.1")).isEqualTo("256.1.1.1");
    }
}
