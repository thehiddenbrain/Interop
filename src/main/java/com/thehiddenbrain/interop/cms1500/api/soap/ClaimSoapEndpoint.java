package com.thehiddenbrain.interop.cms1500.api.soap;

import com.thehiddenbrain.interop.cms1500.contract.GenerateClaimBundleRequest;
import com.thehiddenbrain.interop.cms1500.contract.GenerateClaimBundleResponse;
import com.thehiddenbrain.interop.cms1500.service.ClaimBundleService;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/** SOAP binding of the same operation the REST controller exposes; WSDL at /ws/cms1500.wsdl. */
@Endpoint
public class ClaimSoapEndpoint {

    public static final String NAMESPACE = "http://interop.thehiddenbrain.com/cms1500/v1";

    private final ClaimBundleService service;

    public ClaimSoapEndpoint(ClaimBundleService service) {
        this.service = service;
    }

    @PayloadRoot(namespace = NAMESPACE, localPart = "generateClaimBundleRequest")
    @ResponsePayload
    public GenerateClaimBundleResponse generate(@RequestPayload GenerateClaimBundleRequest request) {
        GenerateClaimBundleResponse response = new GenerateClaimBundleResponse();
        response.setResult(service.generate(request.getClaim()));
        return response;
    }
}
