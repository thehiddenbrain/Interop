package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.auth.SmartAuthCodeFlow;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Where the authorization server sends the browser back after the SMART login. */
@RestController
public class OAuthCallbackController {

    private final SmartAuthCodeFlow flow;

    public OAuthCallbackController(SmartAuthCodeFlow flow) {
        this.flow = flow;
    }

    @GetMapping(value = SmartAuthCodeFlow.CALLBACK_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           @RequestParam(name = "error_description", required = false) String errorDescription) {
        SmartAuthCodeFlow.Outcome outcome = flow.callback(code, state, error, errorDescription);
        String title = outcome.success() ? "Login completed" : "Login failed";
        String envName = outcome.environmentName() == null ? "" : " for " + HtmlUtils.htmlEscape(outcome.environmentName());
        String link = outcome.environmentId() == null ? "/ui/" : "/ui/#env=" + HtmlUtils.htmlEscape(outcome.environmentId())
                + (outcome.patient() == null ? "" : "&patient=" + HtmlUtils.htmlEscape(outcome.patient()));
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + title + "</title>"
                + "<link rel=\"stylesheet\" href=\"/ui/app.css\"></head><body><main style=\"padding:24px\">"
                + "<h1>" + title + envName + "</h1><p>" + HtmlUtils.htmlEscape(outcome.message()) + "</p>"
                + "<p><a href=\"" + link + "\">Back to the workbench</a></p></main></body></html>";
    }
}
