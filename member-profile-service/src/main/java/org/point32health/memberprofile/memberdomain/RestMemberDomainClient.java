package org.point32health.memberprofile.memberdomain;

import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.config.MemberProfileProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/** MemberDomain over HTTP: {@code GET {baseUrl}{memberPath}} with the member id substituted. */
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
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(memberPath, memberId)
                    .retrieve()
                    .body(MemberDomainMember.class));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR,
                    "MemberDomain answered " + e.getStatusCode().value() + " for member '" + memberId + "'", e);
        } catch (ResourceAccessException e) {
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_UNREACHABLE,
                    "MemberDomain unreachable for member '" + memberId + "': " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR,
                    "MemberDomain call failed for member '" + memberId + "': " + e.getMessage(), e);
        }
    }
}
