package com.thehiddenbrain.interop.patientaccess.secrets;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption of secrets at rest. The key comes from {@code paw.master-key} (base64 of 32
 * random bytes); when that is empty a key is generated once and kept in {@code <data-dir>/master.key}
 * (readable by the owner only). Losing the key means every stored secret has to be re-entered.
 */
@Component
public class SecretCrypto {

    private static final Logger log = LoggerFactory.getLogger(SecretCrypto.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String keySource;

    @Autowired
    public SecretCrypto(WorkbenchProperties properties) {
        this(properties.masterKey(), properties.dataDirPath().resolve("master.key"));
    }

    public SecretCrypto(String configuredKey, Path keyFile) {
        byte[] raw;
        if (configuredKey != null && !configuredKey.isBlank()) {
            raw = decodeKey(configuredKey.trim(), "paw.master-key");
            keySource = "paw.master-key";
        } else {
            raw = loadOrCreate(keyFile);
            keySource = keyFile.toString();
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Test constructor with an explicit key. */
    public static SecretCrypto withKey(byte[] raw) {
        return new SecretCrypto(Base64.getEncoder().encodeToString(raw), null);
    }

    public String keySource() {
        return keySource;
    }

    public Secret seal(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return new Secret(PREFIX + Base64.getEncoder().encodeToString(out));
        } catch (GeneralSecurityException e) {
            throw new WorkbenchException(ErrorCode.INTERNAL_ERROR, "cannot encrypt secret: " + e.getMessage(), e);
        }
    }

    public String reveal(Secret secret) {
        if (secret == null || !secret.isSet()) {
            return null;
        }
        String enc = secret.enc();
        if (!enc.startsWith(PREFIX)) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR, "stored secret has an unknown format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR,
                    "cannot decrypt a stored secret (was the master key changed?): " + e.getMessage(), e);
        }
    }

    public SecretView view(Secret secret) {
        if (secret == null || !secret.isSet()) {
            return SecretView.unset();
        }
        String plain = reveal(secret);
        return new SecretView(true, hint(plain));
    }

    static String hint(String plain) {
        if (plain == null || plain.length() < 12) {
            return "***";
        }
        return "***" + plain.substring(plain.length() - 4);
    }

    private static byte[] decodeKey(String base64, String what) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(what + " is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(what + " must be 32 bytes (256 bits) base64-encoded, got " + raw.length + " bytes");
        }
        return raw;
    }

    private static byte[] loadOrCreate(Path keyFile) {
        try {
            if (Files.isRegularFile(keyFile)) {
                return decodeKey(Files.readString(keyFile, StandardCharsets.UTF_8).trim(), keyFile.toString());
            }
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw) + System.lineSeparator());
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows or a file system without POSIX permissions
            }
            log.warn("generated a new master key at {} (set PAW_MASTER_KEY in production so the key does not live next to the data)", keyFile);
            return raw;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or create the master key file " + keyFile + ": " + e.getMessage(), e);
        }
    }
}
