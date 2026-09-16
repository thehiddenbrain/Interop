package com.thehiddenbrain.interop.patientaccess.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Builds the signed JWT client assertion of SMART Backend Services (RS384 / ES384). */
public final class ClientAssertions {

    private ClientAssertions() {
    }

    /** {@code jwkJson} is a single private JWK or a JWK Set (the first private key is used). */
    public static String build(String clientId, String tokenEndpoint, String jwkJson, String algorithm, Instant now) {
        JWK jwk = parsePrivateKey(jwkJson);
        JWSAlgorithm alg = JWSAlgorithm.parse(algorithm == null ? "RS384" : algorithm);
        JWSSigner signer;
        try {
            if (jwk instanceof RSAKey rsa) {
                if (!alg.getName().startsWith("RS") && !alg.getName().startsWith("PS")) {
                    throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "an RSA key needs an RS*/PS* algorithm, not " + alg);
                }
                signer = new RSASSASigner(rsa);
            } else if (jwk instanceof ECKey ec) {
                if (!alg.getName().startsWith("ES")) {
                    throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "an EC key needs an ES* algorithm, not " + alg);
                }
                signer = new ECDSASigner(ec);
            } else {
                throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "unsupported key type " + jwk.getKeyType());
            }
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(clientId)
                    .subject(clientId)
                    .audience(tokenEndpoint)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(240)))
                    .build();
            JWSHeader.Builder header = new JWSHeader.Builder(alg).type(com.nimbusds.jose.JOSEObjectType.JWT).keyID(keyId(jwk));
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new WorkbenchException(ErrorCode.AUTH_FAILED, "cannot sign the client assertion: " + e.getMessage(), e);
        }
    }

    /** The JWK's kid, or its RFC 7638 thumbprint when it has none, so the assertion header and the published JWKS agree. */
    static String keyId(JWK jwk) {
        if (jwk.getKeyID() != null && !jwk.getKeyID().isBlank()) {
            return jwk.getKeyID();
        }
        try {
            return jwk.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "cannot compute the key thumbprint: " + e.getMessage());
        }
    }

    /** Null when the key can sign with the algorithm; else what is wrong (used at save time). */
    public static String checkKey(String jwkJson, String algorithm) {
        try {
            JWK jwk = parsePrivateKey(jwkJson);
            String alg = algorithm == null ? "RS384" : algorithm;
            if (jwk instanceof RSAKey && !(alg.startsWith("RS") || alg.startsWith("PS"))) {
                return "an RSA key needs an RS*/PS* signing algorithm, not " + alg;
            }
            if (jwk instanceof ECKey ec) {
                if (!alg.startsWith("ES")) {
                    return "an EC key needs an ES* signing algorithm, not " + alg;
                }
                String curve = ec.getCurve() == null ? "" : ec.getCurve().getName();
                if ((alg.equals("ES384") && !curve.equals("P-384")) || (alg.equals("ES256") && !curve.equals("P-256"))) {
                    return alg + " needs a " + (alg.equals("ES384") ? "P-384" : "P-256") + " key, this key uses " + curve;
                }
            }
            if (!(jwk instanceof RSAKey) && !(jwk instanceof ECKey)) {
                return "unsupported key type " + jwk.getKeyType();
            }
            return null;
        } catch (WorkbenchException e) {
            return e.getMessage();
        }
    }

    public static JWK parsePrivateKey(String jwkJson) {
        if (jwkJson == null || jwkJson.isBlank()) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "auth.privateKeyJwk is empty");
        }
        try {
            String trimmed = jwkJson.trim();
            JWK jwk;
            if (trimmed.contains("\"keys\"")) {
                JWKSet set = JWKSet.parse(trimmed);
                jwk = set.getKeys().stream().filter(JWK::isPrivate).findFirst()
                        .orElseThrow(() -> new WorkbenchException(ErrorCode.VALIDATION_ERROR, "the JWK Set holds no private key"));
            } else {
                jwk = JWK.parse(trimmed);
            }
            if (!jwk.isPrivate()) {
                throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "the JWK is a public key; a private key is required to sign");
            }
            return jwk;
        } catch (ParseException e) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "auth.privateKeyJwk is not a valid JWK: " + e.getMessage());
        }
    }

    /** Public JWK Set for the configured key, to register with the authorization server. */
    public static String publicJwks(String jwkJson) {
        JWK jwk = parsePrivateKey(jwkJson);
        JWK pub = jwk.toPublicJWK();
        try {
            if (pub.getKeyID() == null || pub.getKeyID().isBlank()) {
                pub = JWK.parse(pub.toJSONObject()).toPublicJWK();
                java.util.Map<String, Object> json = pub.toJSONObject();
                json.put("kid", keyId(jwk));
                pub = JWK.parse(json);
            }
        } catch (ParseException e) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "cannot render the public JWK: " + e.getMessage());
        }
        return new JWKSet(pub).toString();
    }
}
