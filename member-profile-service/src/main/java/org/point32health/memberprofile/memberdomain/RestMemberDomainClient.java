package org.point32health.memberprofile.memberdomain;

import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberIds;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.config.MemberProfileProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * MemberDomain over HTTP: {@code GET {baseUrl}{memberPath}} with the member id substituted.
 * Failures become {@link MemberProfileException}s whose caller-facing text is fixed; the upstream URL,
 * status and message go to the log only.
 */
@Component
public class RestMemberDomainClient implements MemberDomainClient {

    private final RestClient restClient;
    private final String memberPath;

    public RestMemberDomainClient(RestClient memberDomainRestClient, MemberProfileProperties properties) {
        this.restClient = memberDomainRestClient;
        this.memberPath = properties.memberDomain().memberPath();
    }

    @Override
    public Optional<MemberDomainMember> findMember(String memberId) {
        String who = MemberIds.forLog(memberId);
        try {
            MemberDomainMember member = restClient.get()
                    .uri(memberPath, memberId)
                    .retrieve()
                    .body(MemberDomainMember.class);
            if (member == null) {
                throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR, "MemberDomain returned an empty body for " + who);
            }
            return Optional.of(member);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR,
                    "MemberDomain answered " + e.getStatusCode().value() + " for " + who, e);
        } catch (ResourceAccessException e) {
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_UNREACHABLE,
                    "MemberDomain unreachable for " + who + ": " + rootCause(e), e);
        } catch (RestClientException e) {
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR,
                    "MemberDomain call failed for " + who + ": " + rootCause(e), e);
        }
    }

    /** The underlying reason without Spring's message, which repeats the request URL and with it the member id. */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
