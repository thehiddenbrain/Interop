package org.p32h.interop.memberinfo;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

/**
 * In-process member information service for local runs and tests. Answers from {@code member-info-stub/members.json}:
 * the entries whose member id matches the one asked about, ignoring spacing and case. A member without an entry gets an
 * answer with no members, as for a member with no plan on the date. The date of service is not used to filter.
 *
 * <p>Refuses to start outside the {@code DEV} and {@code test} profiles, and inside any Kubernetes/OpenShift pod,
 * so canned answers can never reach PQA or PRD through a copied environment variable.
 */
public class StubMemberInfoClient implements MemberInfoClient {

    private static final Logger log = LoggerFactory.getLogger(StubMemberInfoClient.class);

    private final AtomicLong calls = new AtomicLong();
    private volatile String lastMemberId;
    private volatile LocalDate lastDateOfService;

    private final List<MemberInfoMember> members;
    private final MemberInfoProperties properties;
    private final ObjectMapper objectMapper;

    public StubMemberInfoClient(Environment environment, ResourceLoader resourceLoader, ObjectMapper objectMapper,
            MemberInfoProperties properties) {
        if (!environment.acceptsProfiles(Profiles.of("DEV", "test"))) {
            throw new IllegalStateException("member-info.stub.enabled=true is only allowed with the DEV or test profile; active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            throw new IllegalStateException("the member information stub must never run inside a Kubernetes/OpenShift pod; set "
                    + "SPRING_PROFILES_ACTIVE to FQA, PQA, PQA-LITE or PRD");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        Resource resource = resourceLoader.getResource(properties.stub().fixtures());
        try (InputStream in = resource.getInputStream()) {
            this.members = List.of(objectMapper.readValue(in, MemberInfoMember[].class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read member information stub fixtures from " + properties.stub().fixtures(), e);
        }
        log.info("member information stub loaded {} plans from {}", members.size(), properties.stub().fixtures());
    }

    @Override
    public String kind() {
        return "STUB";
    }

    /** Number of lookups served; lets tests prove when the service is and is not asked. */
    public long calls() {
        return calls.get();
    }

    /** The member id of the most recent lookup, exactly as this client received it. */
    public String lastMemberId() {
        return lastMemberId;
    }

    /** The date of service of the most recent lookup. */
    public LocalDate lastDateOfService() {
        return lastDateOfService;
    }

    @Override
    public MemberInfoResponse lookup(String memberId, LocalDate dateOfService, String correlationId) {
        calls.incrementAndGet();
        lastMemberId = memberId;
        lastDateOfService = dateOfService;
        if (properties.logPayloads()) {
            log.info("member-info request (stub) POST {}{}\n{}", properties.baseUrl(), properties.path(),
                    objectMapper.writeValueAsString(MemberInfoRequest.of(memberId, dateOfService)));
        }
        MemberInfoResponse response = new MemberInfoResponse(null,
                members.stream().filter(m -> sameMember(m.memberId(), memberId)).toList());
        if (properties.logPayloads()) {
            log.info("member-info response (stub) status=200\n{}", objectMapper.writeValueAsString(response));
        }
        return response;
    }

    private static boolean sameMember(String a, String b) {
        return a != null && b != null && a.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(b.replaceAll("[^A-Za-z0-9]", ""));
    }
}
