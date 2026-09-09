package com.thehiddenbrain.interop.cms1500.config;

import com.thehiddenbrain.interop.cms1500.api.soap.ClaimSoapEndpoint;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

import java.util.List;

/** Spring-WS wiring: servlet on /ws, generated WSDL at /ws/cms1500.wsdl, requests validated against the XSD. */
@EnableWs
@Configuration
public class WebServiceConfig extends WsConfigurerAdapter {

    public static final String XSD_LOCATION = "xsd/cms1500-claim.xsd";

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean(name = "cms1500")
    public DefaultWsdl11Definition cms1500Wsdl(XsdSchema cms1500Schema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("Cms1500ClaimPort");
        wsdl.setServiceName("Cms1500ClaimService");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace(ClaimSoapEndpoint.NAMESPACE);
        wsdl.setSchema(cms1500Schema);
        return wsdl;
    }

    @Bean
    public XsdSchema cms1500Schema() {
        return new SimpleXsdSchema(new ClassPathResource(XSD_LOCATION));
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validator = new PayloadValidatingInterceptor();
        validator.setXsdSchema(cms1500Schema());
        validator.setValidateRequest(true);
        validator.setValidateResponse(false);
        interceptors.add(validator);
    }
}
