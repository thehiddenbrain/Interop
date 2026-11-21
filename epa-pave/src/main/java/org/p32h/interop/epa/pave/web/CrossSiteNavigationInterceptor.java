package org.p32h.interop.epa.pave.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closes the one opening {@link ApiGuardFilter} leaves for another site: a top-level navigation,
 * which it lets through so that a link to the tool from a wiki page still opens it.
 *
 * <p>Such a navigation is refused whenever the handler the framework chose for it is a controller
 * method. Every answer of the API is a controller method and a page or a static file of the tool
 * is not, so the framework's own mapping decides what a link from another site may open. No
 * address is sorted here: sorting addresses into page and API is what an earlier guard got wrong,
 * because the server, the framework and a filter each read an address in their own way.
 *
 * <p>Without this a link on another site to the ledger export saves the ledger to the tester's
 * disk, and on the live profile a link to a lookup asks the payer about a member that site chose.
 */
public class CrossSiteNavigationInterceptor implements HandlerInterceptor {

    private final JsonMapper mapper;

    public CrossSiteNavigationInterceptor(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // The error dispatch answers a request that already passed or was refused; it is not a new request.
        if (request.getDispatcherType() == DispatcherType.ERROR || !(handler instanceof HandlerMethod)
                || !ApiGuardFilter.fromAnotherSite(request)) {
            return true;
        }
        ApiGuardFilter.refuseFromAnotherSite(request, response, mapper);
        return false;
    }
}
