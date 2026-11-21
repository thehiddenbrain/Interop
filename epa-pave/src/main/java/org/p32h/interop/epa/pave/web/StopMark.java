package org.p32h.interop.epa.pave.web;

import java.util.UUID;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Marks on the servlet context that this tool has begun to stop. The event comes before the web
 * server's own stop, so every connection the server closes at the end of its time for the stop
 * finds the mark, and {@link ClientGone} does not say the client closed it.
 *
 * <p>The mark is kept on the servlet context, not in a field of a class, so that one application
 * that stops never changes what another in the same process says.
 */
@Component
public class StopMark implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StopMark.class);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            if (event.getApplicationContext() instanceof WebApplicationContext web && web.getServletContext() != null) {
                web.getServletContext().setAttribute(ClientGone.STOPPING, Boolean.TRUE);
            }
        } catch (RuntimeException | Error e) {
            // A failure here must not reach the stop of the framework. A reference and the exception types and frames only.
            log.error("Marking the stop failed inside this tool, reference {}: {}", UUID.randomUUID(), Faults.describe(e));
        }
    }
}
