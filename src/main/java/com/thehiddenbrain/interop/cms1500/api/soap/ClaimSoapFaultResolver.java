package com.thehiddenbrain.interop.cms1500.api.soap;

import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ObjectFactory;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.server.endpoint.AbstractSoapFaultDefinitionExceptionResolver;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;

/**
 * Turns a {@link ClaimException} into a SOAP fault whose detail element is the same
 * {@code generateClaimBundleFault} document the REST API returns as JSON.
 */
@Component
public class ClaimSoapFaultResolver extends AbstractSoapFaultDefinitionExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(ClaimSoapFaultResolver.class);

    private final JAXBContext jaxbContext;
    private final ObjectFactory factory = new ObjectFactory();

    public ClaimSoapFaultResolver() {
        setOrder(0);
        try {
            jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("cannot create JAXB context for the claim contract", e);
        }
    }

    static boolean isClientFault(ErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST, VALIDATION_ERROR, BUNDLE_NOT_FOUND, BUNDLE_EXISTS,
                 NO_ATTACHMENTS, UNSUPPORTED_ATTACHMENT, ATTACHMENT_UNREADABLE -> true;
            case TEMPLATE_ERROR, STORAGE_ERROR, INTERNAL_ERROR -> false;
        };
    }

    @Override
    protected SoapFaultDefinition getFaultDefinition(Object endpoint, Exception ex) {
        if (!(ex instanceof ClaimException ce)) {
            return null;
        }
        if (isClientFault(ce.getCode())) {
            log.warn("claim {}: {} {}", ce.getClaimNumber(), ce.getCode(), ce.getMessage());
        } else {
            log.error("claim {}: {}", ce.getClaimNumber(), ce.getMessage(), ce);
        }
        SoapFaultDefinition definition = new SoapFaultDefinition();
        definition.setFaultCode(isClientFault(ce.getCode()) ? SoapFaultDefinition.CLIENT : SoapFaultDefinition.SERVER);
        definition.setFaultStringOrReason(ce.getCode() + ": " + ce.getMessage());
        return definition;
    }

    @Override
    protected void customizeFault(Object endpoint, Exception ex, SoapFault fault) {
        if (!(ex instanceof ClaimException ce)) {
            return;
        }
        try {
            SoapFaultDetail detail = fault.addFaultDetail();
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.marshal(factory.createGenerateClaimBundleFault(ce.toFault()), detail.getResult());
        } catch (JAXBException e) {
            log.error("cannot add fault detail", e);
        }
    }
}
