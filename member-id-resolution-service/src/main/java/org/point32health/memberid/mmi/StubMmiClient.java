package org.point32health.memberid.mmi;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * In-process MMI for local runs and tests. Serves the records in {@code mmi-stub/members.json} with
 * MMI's documented search behaviour: an exact id match, a 9-digit (policy) match returning every member
 * of the family, a legacy-id match, and the second pass that pulls in records linked through
 * legacyMemberId. A few reserved ids simulate faults (see the README).
 *
 * <p>Refuses to start outside the {@code dev} and {@code test} profiles, and inside any Kubernetes/OpenShift pod,
 * so canned answers can never reach PQA or PROD through a copied environment variable.
 */
public class StubMmiClient implements MmiClient {

    private static final Logger log = LoggerFactory.getLogger(StubMmiClient.class);

    public static final String FAULT_HTTP_500 = "500500500";
    public static final String FAULT_TIMEOUT = "503503503";
    public static final String FAULT_HTTP_400 = "400400400";
    public static final String FAULT_ERROR_MESSAGE = "888888888";
    public static final String FAULT_BAD_BODY = "202202202";
    /** An error-typed message returned beside a member record (MMI partial result). */
    public static final String FAULT_ERROR_WITH_MEMBER = "887777777";
    /** A member record without a memberId. */
    public static final String FAULT_NO_MEMBER_ID = "886666666";

    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();

    private final List<MmiMember> members;
    private final MmiProperties properties;

    public StubMmiClient(Environment environment, ResourceLoader resourceLoader, ObjectMapper objectMapper, MmiProperties properties) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("mmi.stub.enabled=true is only allowed with the dev or test profile; active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            // the default profile is local, so a pod started without SPRING_PROFILES_ACTIVE would otherwise serve canned answers
            throw new IllegalStateException("the MMI stub must never run inside a Kubernetes/OpenShift pod; set SPRING_PROFILES_ACTIVE "
                    + "to fqa, pqa, pqa-lite or prod");
        }
        this.properties = properties;
        Resource resource = resourceLoader.getResource(properties.stub().fixtures());
        try (InputStream in = resource.getInputStream()) {
            this.members = List.of(objectMapper.readValue(in, MmiMember[].class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read MMI stub fixtures from " + properties.stub().fixtures(), e);
        }
        log.info("MMI stub loaded {} member records from {}", members.size(), properties.stub().fixtures());
    }

    @Override
    public String kind() {
        return "STUB";
    }

    /** Number of searches served; lets tests prove MMI is not called for an invalid request. */
    public long calls() {
        return calls.get();
    }

    @Override
    public MmiResult search(String memberId, String correlationId) {
        calls.incrementAndGet();
        String requestId = MmiRequestIds.next(properties.clientId());
        String key = memberId.replaceAll("\\s+", "").toUpperCase();
        String core = key.length() >= 9 ? key.substring(0, 9) : key;
        switch (core) {
            case FAULT_HTTP_500 -> throw MmiException.unavailable(requestId, "HTTP_500", "MMI answered HTTP 500 (stub)", null);
            case FAULT_TIMEOUT -> throw MmiException.unavailable(requestId, "READ_TIMEOUT", "MMI could not be reached: READ_TIMEOUT (stub)", null);
            case FAULT_HTTP_400 -> throw MmiException.rejected(requestId, "HTTP_400", "MMI rejected the request with HTTP 400 (stub)");
            case FAULT_BAD_BODY -> throw MmiException.invalidResponse(requestId, "UNPARSEABLE_BODY", "MMI answered 200 with a body that is not the expected JSON (stub)", null);
            case FAULT_ERROR_MESSAGE -> {
                return new MmiResult(requestId, new MmiResponse(properties.clientId(), properties.clientType(), requestId,
                        List.of(new MmiMessage("ERROR", "500", "ES_TIMEOUT", "search backend timed out (stub)")), null));
            }
            case FAULT_ERROR_WITH_MEMBER -> {
                MmiMember member = new MmiMember(FAULT_ERROR_WITH_MEMBER + "   01", null, "01/01/1951", null, "THP", "MCR", "N", null,
                        List.of(new MmiCoverage("01/01/2024", null, "00001111", "N")));
                return new MmiResult(requestId, new MmiResponse(properties.clientId(), properties.clientType(), requestId,
                        List.of(new MmiMessage("ERROR", "500", "SECOND_PASS_FAILED", "legacy pass failed (stub)")), List.of(member)));
            }
            case FAULT_NO_MEMBER_ID -> {
                MmiMember member = new MmiMember(null, null, "01/01/1951", null, "THP", "MCR", "N", null,
                        List.of(new MmiCoverage("01/01/2024", null, "00001111", "N")));
                return new MmiResult(requestId, new MmiResponse(properties.clientId(), properties.clientType(), requestId, null, List.of(member)));
            }
            default -> {
                // fall through to the fixture search
            }
        }
        Set<MmiMember> found = new LinkedHashSet<>();
        for (MmiMember m : members) {
            if (matches(key, m)) {
                found.add(m);
            }
        }
        // MMI's second pass: anything linked through legacyMemberId, in either direction
        for (MmiMember m : new ArrayList<>(found)) {
            String mKey = compact(m.memberId());
            String mLegacy = compact(m.legacyMemberId());
            for (MmiMember other : members) {
                String oKey = compact(other.memberId());
                String oLegacy = compact(other.legacyMemberId());
                if ((mLegacy != null && mLegacy.equals(oKey)) || (oLegacy != null && oLegacy.equals(mKey))) {
                    found.add(other);
                }
            }
        }
        List<MmiMember> result = found.isEmpty() ? null : new ArrayList<>(found);
        return new MmiResult(requestId, new MmiResponse(properties.clientId(), properties.clientType(), requestId, null, result));
    }

    private static boolean matches(String key, MmiMember m) {
        String mKey = compact(m.memberId());
        String mLegacy = compact(m.legacyMemberId());
        boolean nineDigits = key.length() == 9 && key.chars().allMatch(Character::isDigit);
        if (key.equals(mKey) || key.equals(mLegacy)) {
            return true;
        }
        return nineDigits && ((mKey != null && mKey.startsWith(key)) || (mLegacy != null && mLegacy.startsWith(key)));
    }

    private static String compact(String id) {
        return id == null ? null : id.replaceAll("\\s+", "").toUpperCase();
    }
}
