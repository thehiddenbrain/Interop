package com.thehiddenbrain.interop.patientaccess.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.support.TestGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCryptoTest {

    private final SecretCrypto crypto = SecretCrypto.withKey(TestGraph.KEY);

    @Test
    void sealAndRevealRoundTripKeepsUnicode() {
        Secret s = crypto.seal("pa$$wörd ✓ with spaces");
        assertThat(s.isSet()).isTrue();
        assertThat(s.enc()).startsWith("v1:");
        assertThat(crypto.reveal(s)).isEqualTo("pa$$wörd ✓ with spaces");
        assertThat(crypto.seal(null)).isNull();
        assertThat(crypto.reveal(null)).isNull();
        assertThat(crypto.reveal(new Secret(""))).isNull();
    }

    @Test
    void everySealUsesAFreshIv() {
        Secret a = crypto.seal("same");
        Secret b = crypto.seal("same");
        assertThat(a.enc()).isNotEqualTo(b.enc());
        assertThat(crypto.reveal(a)).isEqualTo(crypto.reveal(b));
    }

    @Test
    void tamperedCiphertextAndWrongKeyAreDetected() {
        Secret s = crypto.seal("integrity");
        String enc = s.enc();
        // flip one character of the GCM tag (the last base64 block) so the ciphertext no longer authenticates
        int i = enc.length() - 3;
        String flipped = enc.substring(0, i) + (enc.charAt(i) == 'A' ? 'B' : 'A') + enc.substring(i + 1);
        assertThatThrownBy(() -> crypto.reveal(new Secret(flipped)))
                .isInstanceOf(WorkbenchException.class)
                .satisfies(e -> assertThat(((WorkbenchException) e).getCode()).isEqualTo(ErrorCode.STORAGE_ERROR))
                .hasMessageContaining("cannot decrypt");

        SecretCrypto other = SecretCrypto.withKey("ffffffffffffffffffffffffffffffff".getBytes());
        assertThatThrownBy(() -> other.reveal(s)).isInstanceOf(WorkbenchException.class).hasMessageContaining("master key");
        assertThatThrownBy(() -> crypto.reveal(new Secret("v0:abc"))).isInstanceOf(WorkbenchException.class).hasMessageContaining("unknown format");
    }

    @Test
    void generatesAndReusesAKeyFileWithOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("data").resolve("master.key");
        SecretCrypto first = new SecretCrypto("", keyFile);
        assertThat(first.keySource()).isEqualTo(keyFile.toString());
        assertThat(keyFile).isRegularFile();
        byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        assertThat(raw).hasSize(32);
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
            assertThat(perms).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }

        Secret sealed = first.seal("survives a restart");
        String keyBefore = Files.readString(keyFile);
        SecretCrypto second = new SecretCrypto(null, keyFile);
        assertThat(second.reveal(sealed)).isEqualTo("survives a restart");
        assertThat(Files.readString(keyFile)).isEqualTo(keyBefore);
    }

    @Test
    void configuredKeyMustBe32Base64Bytes() {
        assertThatThrownBy(() -> new SecretCrypto("not base64!!", null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> new SecretCrypto(Base64.getEncoder().encodeToString("short".getBytes()), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        SecretCrypto configured = new SecretCrypto(Base64.getEncoder().encodeToString(TestGraph.KEY), null);
        assertThat(configured.keySource()).isEqualTo("paw.master-key");
    }

    @Test
    void hintShowsOnlyTheTailOfLongValues() {
        assertThat(SecretCrypto.hint(null)).isEqualTo("***");
        assertThat(SecretCrypto.hint("short-secret")).isEqualTo("***cret");
        assertThat(SecretCrypto.hint("elevenchars")).isEqualTo("***");
        assertThat(SecretCrypto.hint("abcdefghijkl-WXYZ")).isEqualTo("***WXYZ");

        assertThat(crypto.view(null)).isEqualTo(SecretView.unset());
        assertThat(crypto.view(new Secret(" "))).isEqualTo(SecretView.unset());
        SecretView view = crypto.view(crypto.seal("client-secret-value-1234"));
        assertThat(view.set()).isTrue();
        assertThat(view.hint()).isEqualTo("***1234");
    }

    @Test
    void secretSerializesAsEncOnlyAndNeverPrintsItself() throws Exception {
        Secret s = crypto.seal("plain-text-value");
        String json = JsonFile.MAPPER.writeValueAsString(s);
        JsonNode node = JsonFile.MAPPER.readTree(json);
        assertThat(node.fieldNames()).toIterable().containsExactly("enc");
        assertThat(json).doesNotContain("plain-text-value");
        assertThat(JsonFile.MAPPER.readValue(json, Secret.class)).isEqualTo(s);
        assertThat(s.toString()).isEqualTo("Secret[***]").doesNotContain(s.enc());
        assertThat(new Secret(null).isSet()).isFalse();
    }
}
