package org.p32h.interop.epa.pave.config;

import org.apache.catalina.core.StandardHost;
import org.p32h.interop.epa.pave.web.JsonErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Replaces the embedded server's own error page, so that an error it raises before a request
 * reaches the application is answered as JSON in the shared error shape and never as HTML.
 */
@Component
public class ServerErrorPages implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }
}
