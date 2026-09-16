package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.HttpResult;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import com.thehiddenbrain.interop.patientaccess.fhir.UrlBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.thehiddenbrain.interop.patientaccess.conformance.checks.CheckSupport.EOB;

/** Paging behaviour on the EOB search: _count, next links, totals and self links. */
@Configuration
public class PagingChecks {

    private static final String FHIR_PAGING = "FHIR R4 http.html#paging (Bundle.link next/self, _count); C4BB 2.1.0 CapabilityStatement c4bb "
            + "rest documentation item 2 (RESTful behavior)";

    /** ExplanationOfBenefit?patient=&_count=2, fetched once per run. */
    static SearchPage firstPage(CheckContext ctx) {
        String pid = ctx.patientId().orElseThrow();
        return ctx.cached("paging.page1", () -> ctx.searchPage(EOB, CheckContext.params("patient", pid, "_count", "2")));
    }

    /** The second page (following the next link), or empty when the first has none; null result marks a link outside the environment. */
    static Optional<HttpResult> secondPage(CheckContext ctx) {
        SearchPage first = firstPage(ctx);
        if (first.nextUrl() == null) {
            return Optional.empty();
        }
        String next = first.nextUrl();
        return Optional.ofNullable(ctx.cached("paging.page2", () -> {
            try {
                return ctx.get(next);
            } catch (WorkbenchException e) {
                if (e.getCode() == ErrorCode.TARGET_NOT_ALLOWED) {
                    return null;
                }
                throw e;
            }
        }));
    }

    static Optional<CheckResult> firstPageProblem(CheckResult.Builder b, CheckContext ctx) {
        SearchPage first = CheckSupport.record(b, firstPage(ctx));
        String problem = CheckSupport.bundleProblem(first, EOB);
        if (problem != null) {
            return Optional.of(b.fail("ExplanationOfBenefit?patient=&_count=2 answered " + problem));
        }
        return Optional.empty();
    }

    @Bean
    Check pagingCount() {
        return SimpleCheck.of("paging.count", "paging", "_count is honoured", Severity.SHALL,
                "GET ExplanationOfBenefit?patient={id}&_count=2 returns at most 2 match entries", FHIR_PAGING, true, (b, ctx) -> {
            Optional<CheckResult> problem = firstPageProblem(b, ctx);
            if (problem.isPresent()) {
                return problem.get();
            }
            SearchPage first = firstPage(ctx);
            if (first.count() > 2) {
                return b.fail(first.count() + " match entries returned for _count=2");
            }
            return b.pass(first.count() + " match entries for _count=2" + (first.nextUrl() == null ? " (no next link)" : ", next link present"));
        });
    }

    @Bean
    Check pagingNext() {
        return SimpleCheck.of("paging.next", "paging", "Next link can be followed", Severity.SHALL,
                "When the first page has a next link, GET of that link returns HTTP 200 with a searchset Bundle whose EOB ids differ from the "
                        + "first page; informational when everything fits on one page", FHIR_PAGING, true, (b, ctx) -> {
            Optional<CheckResult> problem = firstPageProblem(b, ctx);
            if (problem.isPresent()) {
                return problem.get();
            }
            SearchPage first = firstPage(ctx);
            if (first.nextUrl() == null) {
                return b.info("no next link: all " + first.count() + " EOB(s) fit on one page of 2, paging could not be exercised");
            }
            b.detail("next: " + first.nextUrl());
            Optional<HttpResult> second = secondPage(ctx);
            if (second.isEmpty()) {
                return b.fail("next link points outside the environment (" + UrlBuilder.hostOf(first.nextUrl()) + "); enable "
                        + "fhir.allowNextLinkHostMismatch to follow it");
            }
            CheckSupport.record(b, second.get());
            SearchPage page2 = SearchPage.of(second.get(), List.of());
            String p = CheckSupport.bundleProblem(page2, EOB);
            if (p != null) {
                return b.fail("next page answered " + p);
            }
            List<String> repeated = new ArrayList<>(CheckSupport.ids(page2.resources()));
            repeated.retainAll(CheckSupport.ids(first.resources()));
            if (!repeated.isEmpty()) {
                return b.fail("second page repeats ids of the first: " + repeated);
            }
            if (page2.count() == 0) {
                return b.fail("second page is empty although a next link was offered");
            }
            return b.pass("second page has " + page2.count() + " new EOB(s)");
        });
    }

    @Bean
    Check pagingNextSameHost() {
        return SimpleCheck.of("paging.next.sameHost", "paging", "Next link stays on the server", Severity.SHOULD,
                "The next link points at the environment's host (links to another host break apps that pin the FHIR base URL and the "
                        + "workbench's outbound guard)", FHIR_PAGING + "; SMART App Launch: links inside the token's audience", true, (b, ctx) -> {
            Optional<CheckResult> problem = firstPageProblem(b, ctx);
            if (problem.isPresent()) {
                return problem.get();
            }
            SearchPage first = firstPage(ctx);
            if (first.nextUrl() == null) {
                return b.skip("no next link on the first page");
            }
            String host = UrlBuilder.hostOf(first.nextUrl());
            String expected = UrlBuilder.hostOf(ctx.environment().baseUrl());
            b.detail("next: " + first.nextUrl());
            if (host == null) {
                return b.fail("next link is not an absolute URL: " + first.nextUrl());
            }
            if (!host.equalsIgnoreCase(expected)) {
                return b.fail("next link host " + host + " differs from the environment host " + expected);
            }
            return b.pass("next link on " + host);
        });
    }

    @Bean
    Check pagingTotal() {
        return SimpleCheck.of("paging.total", "paging", "Bundle.total is consistent", Severity.SHOULD,
                "When Bundle.total is present it is the same on the first and the second page", FHIR_PAGING + " Bundle.total", true, (b, ctx) -> {
            Optional<CheckResult> problem = firstPageProblem(b, ctx);
            if (problem.isPresent()) {
                return problem.get();
            }
            SearchPage first = firstPage(ctx);
            if (first.total() == null) {
                return b.skip("Bundle.total is not present (optional)");
            }
            if (first.nextUrl() == null) {
                if (first.total() != first.count()) {
                    return b.fail("Bundle.total " + first.total() + " but " + first.count() + " entries on the only page");
                }
                return b.pass("Bundle.total " + first.total() + " matches the single page");
            }
            Optional<HttpResult> second = secondPage(ctx);
            if (second.isEmpty()) {
                return b.skip("next link could not be followed (see paging.next)");
            }
            CheckSupport.record(b, second.get());
            SearchPage page2 = SearchPage.of(second.get(), List.of());
            if (page2.total() == null) {
                return b.fail("second page has no Bundle.total while the first says " + first.total());
            }
            if (!page2.total().equals(first.total())) {
                return b.fail("Bundle.total differs: " + first.total() + " vs " + page2.total());
            }
            return b.pass("Bundle.total " + first.total() + " on both pages");
        });
    }

    @Bean
    Check pagingSelf() {
        return SimpleCheck.of("paging.self", "paging", "Self link present", Severity.SHOULD,
                "The searchset Bundle carries a link with relation self (apps use it to learn which parameters the server applied)",
                FHIR_PAGING + " Bundle.link self", true, (b, ctx) -> {
            Optional<CheckResult> problem = firstPageProblem(b, ctx);
            if (problem.isPresent()) {
                return problem.get();
            }
            SearchPage first = firstPage(ctx);
            if (first.selfUrl() == null) {
                return b.fail("no self link on the searchset Bundle");
            }
            b.detail("self: " + first.selfUrl());
            return b.pass("self link present");
        });
    }
}
