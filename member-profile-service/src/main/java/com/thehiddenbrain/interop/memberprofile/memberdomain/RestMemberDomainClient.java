package com.thehiddenbrain.interop.memberprofile.memberdomain;

import com.thehiddenbrain.interop.memberprofile.config.MemberProfileProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

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
            MemberDomainMember member = restClient.get()
                    .uri(memberPath, memberId)
                    .retrieve()
                    .body(MemberDomainMember.class);
            return Optional.ofNullable(member);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new MemberDomainException("MemberDomain answered " + e.getStatusCode() + " for member " + memberId, e);
        } catch (ResourceAccessException e) {
            throw new MemberDomainException("MemberDomain unreachable for member " + memberId, e);
        } catch (RestClientException e) {
            throw new MemberDomainException("MemberDomain call failed for member " + memberId, e);
        }
    }
}
