package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.dtr.CaseAnswers;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers a questionnaire's questions from the case on screen (plan R-18). A controller of its own,
 * apart from the DTR runs, because it sends nothing to any service and records nothing: it reads the
 * questionnaire the page already holds and the case the tester typed, the same on mock and on live,
 * since the answers are the tester's own data, each marked with the field it came from.
 */
@RestController
public class CaseAnswersController {

    @PostMapping("/api/dtr/case-answers")
    public CaseAnswers.Result caseAnswers(@RequestBody CaseAnswers.Request request) {
        return CaseAnswers.answer(request);
    }
}
