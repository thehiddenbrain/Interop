package org.p32h.interop.mmi;

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
 * legacyMemberId. No member at all is an HTTP 404 with an error-typed message, as the real MMI answers.
 * A few reserved ids simulate faults (see the README).
 *
 * <p>The service sends the member id exactly as the EMR typed it. The stub ignores separators (spaces, hyphens,
 * anything that is not a letter or digit) and case when matching, which is the leniency the owner describes for
 * the real MMI; confirm it in PQA with a hyphenated id.
 *
 * <p>Refuses to start outside the {@code DEV} and {@code test} profiles, and inside any Kubernetes/OpenShift pod,
 * so canned answers can never reach PQA or PRD through a copied environment variable.
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
    private volatile String lastSearched;

    private final List<MmiMember> members;
    private final MmiProperties properties;
    private final ObjectMapper objectMapper;

    public StubMmiClient(Environment environment, ResourceLoader resourceLoader, ObjectMapper objectMapper, MmiProperties properties) {
        if (!environment.acceptsProfiles(Profiles.of("DEV", "test"))) {
            throw new IllegalStateException("mmi.stub.enabled=true is only allowed with the DEV or test profile; active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            // a pod must never serve canned answers, whatever profile it was (or was not) started with
            throw new IllegalStateException("the MMI stub must never run inside a Kubernetes/OpenShift pod; set SPRING_PROFILES_ACTIVE "
                    + "to FQA, PQA, PQA-LITE or PRD");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
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

    /** The member id of the most recent search, exactly as this client received it; lets tests prove nothing was reshaped. */
    public String lastSearched() {
        return lastSearched;
    }

    @Override
    public MmiResult search(String memberId, String correlationId) {
        calls.incrementAndGet();
        lastSearched = memberId;
        String requestId = MmiRequestIds.next(properties.clientId());
        if (properties.logPayloads()) {
            MmiRequest request = new MmiRequest(memberId, memberId, properties.voidCoverageRecord(), properties.clientId(),
                    properties.clientType(), requestId);
            log.info("mmi request (stub) requestId={} POST {}{}\n{}", requestId, properties.baseUrl(), properties.path(),
                    objectMapper.writeValueAsString(request));
        }
        try {
            MmiResult result = answer(memberId, requestId);
            if (properties.logPayloads()) {
                log.info("mmi response (stub) requestId={} status={}\n{}", requestId, result.httpStatus(), objectMapper.writeValueAsString(result.response()));
            }
            return result;
        } catch (MmiException e) {
            if (properties.logPayloads()) {
                log.info("mmi response (stub) requestId={} simulated failure code={} detail={}", requestId, e.code(), e.detail());
            }
            throw e;
        }
    }

    private MmiResult answer(String memberId, String requestId) {
        String key = compact(memberId);
        String core = key.length() >= 9 ? key.substring(0, 9) : key;
        switch (core) {
            case FAULT_HTTP_500 -> throw MmiException.unavailable(requestId, "HTTP_500", "The member lookup reported an internal error: INTERNAL_ERROR search failed (stub)", null);
            case FAULT_TIMEOUT -> throw MmiException.unavailable(requestId, "READ_TIMEOUT", "The member lookup could not be reached: READ_TIMEOUT (stub)", null);
            case FAULT_HTTP_400 -> throw MmiException.badRequest(requestId, "HTTP_400",
                    "The member lookup rejected the request: INVALID_REQUEST memberId could not be processed (stub)");
            case FAULT_BAD_BODY -> throw MmiException.invalidResponse(requestId, "UNPARSEABLE_BODY", "The member lookup answered 200 with an unreadable body (stub)", null);
            case FAULT_ERROR_MESSAGE -> {
                return new MmiResult(requestId, 200, new MmiResponse(properties.clientId(), properties.clientType(), requestId,
                        List.of(new MmiMessage("ERROR", "500", "ES_TIMEOUT", "search backend timed out (stub)")), null));
            }
            case FAULT_ERROR_WITH_MEMBER -> {
                MmiMember member = new MmiMember(FAULT_ERROR_WITH_MEMBER + "   01", null, "01/01/1951", null, "THP", "MCR", "N", null,
                        List.of(new MmiCoverage("01/01/2024", null, "00001111", "N")));
                return new MmiResult(requestId, 200, new MmiResponse(properties.clientId(), properties.clientType(), requestId,
                        List.of(new MmiMessage("ERROR", "500", "SECOND_PASS_FAILED", "legacy pass failed (stub)")), List.of(member)));
            }
            case FAULT_NO_MEMBER_ID -> {
                MmiMember member = new MmiMember(null, null, "01/01/1951", null, "THP", "MCR", "N", null,
                        List.of(new MmiCoverage("01/01/2024", null, "00001111", "N")));
                return new MmiResult(requestId, 200, new MmiResponse(properties.clientId(), properties.clientType(), requestId, null, List.of(member)));
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
        if (found.isEmpty()) {
            // like the real MMI: no member for this id is an HTTP 404 with a message and no members
            return new MmiResult(requestId, 404, new MmiResponse(properties.clientId(), properties.clientType(), requestId,
                    List.of(new MmiMessage("ERROR", "404", "MEMBER_NOT_FOUND", "No member found for the given id (stub)")), null));
        }
        return new MmiResult(requestId, 200, new MmiResponse(properties.clientId(), properties.clientType(), requestId, null, new ArrayList<>(found)));
    }

    private static boolean matches(String key, MmiMember m) {
        String mKey = compact(m.memberId());
        String mLegacy = compact(m.legacyMemberId());
        boolean cardNumber = key.length() == 9 && key.chars().allMatch(Character::isLetterOrDigit); // 9 characters: the card / policy number
        if (key.equals(mKey) || key.equals(mLegacy)) {
            return true;
        }
        return cardNumber && ((mKey != null && mKey.startsWith(key)) || (mLegacy != null && mLegacy.startsWith(key)));
    }

    private static String compact(String id) {
        return id == null ? null : id.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }
}
