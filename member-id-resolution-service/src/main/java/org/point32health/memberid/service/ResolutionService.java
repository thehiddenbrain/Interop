package org.point32health.memberid.service;

import org.point32health.memberid.api.Candidate;
import org.point32health.memberid.api.Coverage;
import org.point32health.memberid.api.MemberIdParts;
import org.point32health.memberid.api.SourceMessage;
import org.point32health.memberid.api.Outcome;
import org.point32health.memberid.api.RequestValidator;
import org.point32health.memberid.api.ResolveRequest;
import org.point32health.memberid.api.ResolveResponse;
import org.point32health.memberid.api.VendorMapRequest;
import org.point32health.memberid.api.VendorMapResponse;
import org.point32health.memberid.domain.CoverageDecision;
import org.point32health.memberid.domain.MemberRecord;
import org.point32health.memberid.domain.MemberSelector;
import org.point32health.memberid.domain.ResolutionException;
import org.point32health.memberid.domain.SelectionResult;
import org.point32health.memberid.domain.UnknownVendorException;
import org.point32health.memberid.mmi.MmiClient;
import org.point32health.memberid.mmi.MmiException;
import org.point32health.memberid.mmi.MmiMember;
import org.point32health.memberid.mmi.MmiMessage;
import org.point32health.memberid.mmi.MmiProperties;
import org.point32health.memberid.mmi.MmiRecordMapper;
import org.point32health.memberid.mmi.MmiResult;
import org.point32health.memberid.support.Masking;
import org.point32health.memberid.vendor.FormattedMemberId;
import org.point32health.memberid.vendor.Vendor;
import org.point32health.memberid.vendor.VendorFormatter;
import org.point32health.memberid.vendor.VendorRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Two operations over one core. The core: ask MMI once with the member id exactly as received, reduce the
 * records to one person, decide coverage on the date of service. {@link #resolve} then renders the stored id
 * for the one vendor Onyx named; {@link #vendorMap} renders it for every configured vendor.
 */
@Service
public class ResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionService.class);

    private final RequestValidator validator;
    private final VendorRegistry vendors;
    private final MmiClient mmi;
    private final MmiProperties mmiProperties;
    private final MmiRecordMapper mapper;
    private final MemberSelector selector;
    private final VendorFormatter formatter;

    public ResolutionService(RequestValidator validator, VendorRegistry vendors, MmiClient mmi, MmiProperties mmiProperties,
            MmiRecordMapper mapper, MemberSelector selector, VendorFormatter formatter) {
        this.validator = validator;
        this.vendors = vendors;
        this.mmi = mmi;
        this.mmiProperties = mmiProperties;
        this.mapper = mapper;
        this.selector = selector;
        this.formatter = formatter;
    }

    /** Operation 1, {@code POST /api/v1/member-ids/resolve}: the id for the one vendor Onyx named. */
    public ResolveResponse resolve(ResolveRequest request, String correlationId) {
        long start = System.nanoTime();
        RequestValidator.Validated v = validator.validate(request);
        Vendor vendor = vendors.find(v.vendor()).orElseThrow(() -> new UnknownVendorException(vendors.knownCodes()));
        Resolved r = resolveMember(v, correlationId);
        FormattedMemberId formatted = r.record() == null ? null : formatter.format(r.record().storedMemberId(), vendor.format());
        ResolveResponse response = new ResolveResponse(r.outcome(), message(r, v),
                new ResolveResponse.MemberId(v.memberId(), r.storedMemberId(), formatted == null ? null : formatted.value(), parts(formatted)),
                r.lineOfBusiness(), v.dateOfService(), v.dateOfServiceDefaulted(),
                r.coverageBlock(), r.candidates(), r.mmiRequestId(), r.mmiNote());
        logOutcome("resolve", vendor.code(), r, v, start);
        return response;
    }

    /** Operation 2, {@code POST /api/v1/member-ids/vendor-map}: the id for every configured vendor, no vendor named. */
    public VendorMapResponse vendorMap(VendorMapRequest request, String correlationId) {
        long start = System.nanoTime();
        RequestValidator.Validated v = validator.validate(request);
        Resolved r = resolveMember(v, correlationId);
        List<VendorMapResponse.VendorMemberId> vendorMemberIds = null;
        if (r.record() != null) {
            vendorMemberIds = vendors.all().stream().map(vendor -> {
                FormattedMemberId formatted = formatter.format(r.record().storedMemberId(), vendor.format());
                return new VendorMapResponse.VendorMemberId(vendor.code(), formatted.value(), parts(formatted));
            }).toList();
        }
        VendorMapResponse response = new VendorMapResponse(r.outcome(), message(r, v), new VendorMapResponse.MemberId(v.memberId(), r.storedMemberId()),
                r.lineOfBusiness(), v.dateOfService(), v.dateOfServiceDefaulted(),
                r.coverageBlock(), r.candidates(), vendorMemberIds, r.mmiRequestId(), r.mmiNote());
        logOutcome("vendor-map", "ALL(" + vendors.all().size() + ")", r, v, start);
        return response;
    }

    /** What both operations share once the request is valid: MMI's answer reduced to one outcome. */
    private record Resolved(Outcome outcome, MemberRecord record, CoverageDecision coverage, String ambiguityReason,
            List<Candidate> candidates, String mmiRequestId, int records, SourceMessage mmiNote) {

        String storedMemberId() {
            return record == null ? null : record.storedMemberId();
        }

        String company() {
            return record == null ? null : record.company();
        }

        String lineOfBusiness() {
            return record == null ? null : record.lineOfBusiness();
        }

        Coverage coverageBlock() {
            if (coverage == null) {
                return null;
            }
            Coverage.Span span = coverage.span() == null ? null : new Coverage.Span(coverage.span().effective(), coverage.span().end());
            return new Coverage(coverage.active(), span);
        }
    }

    private Resolved resolveMember(RequestValidator.Validated v, String correlationId) {
        MmiResult mmiResult = mmi.search(v.memberId(), correlationId);
        List<MmiMember> members = mmiResult.response().membersOrEmpty();
        List<MmiMessage> messages = mmiResult.response().messagesOrEmpty();

        boolean notFound = mmiResult.httpStatus() == 404; // MMI's contract: 404 = no member for this id; the status wins
        if (notFound || members.isEmpty()) {
            if (notFound && !members.isEmpty()) {
                log.warn("marker=MMI_NOT_FOUND_WITH_MEMBERS mmiRequestId={} messages={} records={}: 404 wins, records ignored",
                        mmiResult.requestId(), messages, members.size());
            }
            if (!notFound && hasErrorMessage(messages)) {
                MmiMessage m = messages.stream().filter(this::isError).findFirst().orElseThrow();
                throw MmiException.rejected(mmiResult.requestId(), "ERROR_MESSAGE",
                        "The member lookup reported an error: " + m.messageCode() + (m.message() == null ? "" : " " + m.message()));
            }
            MmiMessage first = messages.isEmpty() ? null : messages.get(0);
            SourceMessage note = first == null ? null : new SourceMessage(first.messageType(), first.statusCode(), first.messageCode(), first.message());
            return new Resolved(Outcome.NOT_FOUND, null, null, null, null, mmiResult.requestId(), 0, note);
        }
        if (hasErrorMessage(messages)) {
            log.warn("marker=MMI_ERROR_MESSAGE_WITH_MEMBERS mmiRequestId={} messages={}", mmiResult.requestId(), messages);
        }

        List<MemberRecord> records = members.stream().map(mapper::toRecord).filter(r -> r.matchKey() != null).toList();
        if (records.isEmpty()) {
            throw MmiException.invalidResponse(mmiResult.requestId(), "NO_MEMBER_ID", "every record returned lacks a member id", null);
        }

        SelectionResult selection;
        try {
            selection = selector.select(records, v.dateOfBirth(), v.dateOfService());
        } catch (ResolutionException e) {
            throw e.withMmiRequestId(mmiResult.requestId());
        }
        if (selection instanceof SelectionResult.Ambiguous a) {
            List<Candidate> candidates = a.candidates().stream()
                    .map(c -> new Candidate(c.storedMemberId(), c.lineOfBusiness(), c.coverageActive()))
                    .toList();
            return new Resolved(Outcome.AMBIGUOUS, null, null, a.reason(), candidates, mmiResult.requestId(), members.size(), null);
        }
        SelectionResult.Selected s = (SelectionResult.Selected) selection;
        if (s.readableSpans() == 0 && s.unreadableSpans() > 0) {
            // every span the member has is unreadable: answering INACTIVE would be a confident wrong answer
            throw MmiException.invalidResponse(mmiResult.requestId(), "UNREADABLE_COVERAGE",
                    "every coverage span on the member record has an unreadable date; coverage cannot be determined", null);
        }
        CoverageDecision coverage = s.coverage();
        if (s.unreadableSpans() > 0) {
            log.warn("marker=UNREADABLE_SPANS_ON_SELECTED mmiRequestId={} count={} active={}", mmiResult.requestId(),
                    s.unreadableSpans(), coverage.active());
        }
        return new Resolved(coverage.active() ? Outcome.ACTIVE : Outcome.INACTIVE, s.record(), coverage, null, null,
                mmiResult.requestId(), members.size(), null);
    }

    private static MemberIdParts parts(FormattedMemberId formatted) {
        return formatted == null || formatted.parts() == null ? null
                : new MemberIdParts(formatted.parts().memberId(), formatted.parts().suffix());
    }

    /**
     * One plain sentence per outcome, for a human reading the response. It carries what used to be separate fields:
     * why an INACTIVE member is not covered, and what to do about an AMBIGUOUS answer.
     */
    private static String message(Resolved r, RequestValidator.Validated v) {
        return switch (r.outcome()) {
            case ACTIVE -> "Member found; coverage active on " + v.dateOfService();
            case INACTIVE -> "Member found; " + switch (r.coverage().reason()) {
                case NO_COVERAGE_RECORDS -> "no coverage on record";
                case NOT_YET_EFFECTIVE -> "coverage not yet effective on " + v.dateOfService();
                case COVERAGE_ENDED -> "coverage ended before " + v.dateOfService();
                case COVERAGE_GAP -> "no coverage on " + v.dateOfService() + " (gap between coverage periods)";
                case COVERED -> throw new IllegalStateException("INACTIVE with reason COVERED");
            };
            case NOT_FOUND -> "No member found for this id";
            case AMBIGUOUS -> MemberSelector.DOB_NOT_DISCRIMINATING.equals(r.ambiguityReason())
                    ? "Several members match this id and date of birth; resend the member's full id including the suffix, or pick from candidates"
                    : "Several members match this id; add patient.dateOfBirth or resend the member's full id including the suffix, or pick from candidates";
        };
    }

    private boolean hasErrorMessage(List<MmiMessage> messages) {
        return messages.stream().anyMatch(this::isError);
    }

    private boolean isError(MmiMessage m) {
        return m.messageType() != null && mmiProperties.errorMessageTypes().stream().anyMatch(t -> t.equalsIgnoreCase(m.messageType().strip()));
    }

    private static void logOutcome(String operation, String vendor, Resolved r, RequestValidator.Validated v, long start) {
        log.info("{} outcome={} reason={} vendor={} memberId={} stored={} company={} lob={} dos={} dosDefaulted={} records={} mmiRequestId={} ms={}",
                operation, r.outcome(), r.coverage() == null ? "-" : r.coverage().reason(), vendor,
                Masking.memberId(v.memberId()), Masking.memberId(r.storedMemberId()), r.company(), r.lineOfBusiness(),
                v.dateOfService(), v.dateOfServiceDefaulted(), r.records(), r.mmiRequestId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
