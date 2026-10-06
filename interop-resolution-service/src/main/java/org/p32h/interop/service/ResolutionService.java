package org.p32h.interop.service;

import org.p32h.interop.api.Coverage;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.SourceMessage;
import org.p32h.interop.api.Outcome;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.domain.CoverageDecision;
import org.p32h.interop.domain.CoverageSpan;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.domain.MemberSelector;
import org.p32h.interop.domain.ResolutionException;
import org.p32h.interop.domain.SelectionResult;
import org.p32h.interop.mmi.MmiClient;
import org.p32h.interop.mmi.MmiException;
import org.p32h.interop.mmi.MmiMember;
import org.p32h.interop.mmi.MmiMessage;
import org.p32h.interop.mmi.MmiProperties;
import org.p32h.interop.mmi.MmiRecordMapper;
import org.p32h.interop.mmi.MmiResult;
import org.p32h.interop.vendor.VendorFormatter;
import org.p32h.interop.vendor.VendorRegistry;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The member resolution, {@code POST /v1/interop/resolve}: ask MMI once with the member id exactly as received,
 * reduce the records to one person, decide coverage on the date (or period) of service, and render the stored id for
 * every configured vendor.
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

    /** Resolves the member behind the id and renders the stored id for every configured vendor. */
    public MemberResolutionResponse resolve(MemberResolutionRequest request, String correlationId) {
        long start = System.nanoTime();
        RequestValidator.Validated v = validator.validate(request);
        Resolved r = resolveMember(v, correlationId);
        List<MemberResolutionResponse.VendorMemberId> forVendors = null;
        if (r.record() != null) {
            forVendors = vendors.all().stream()
                    .map(vendor -> new MemberResolutionResponse.VendorMemberId(vendor.code(),
                            formatter.format(r.record().storedMemberId(), vendor.format()).value()))
                    .toList();
        }
        MemberResolutionResponse response = new MemberResolutionResponse(r.outcome(), message(r, v),
                new MemberResolutionResponse.MemberId(v.memberId(), r.storedMemberId(), forVendors),
                r.lineOfBusiness(), v.dateOfService(), v.dateOfServiceEnd(), v.dateOfServiceDefaulted(),
                r.coverageBlock(), v.requestId(), r.mmiRequestId(), r.mmiNote());
        logOutcome(r, v, start);
        return response;
    }

    /** MMI's answer reduced to one outcome, before it is rendered. */
    private record Resolved(Outcome outcome, MemberRecord record, CoverageDecision coverage, String ambiguityReason,
            int persons, String mmiRequestId, int records, SourceMessage mmiNote) {

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
            if (coverage.span() == null) {
                return new Coverage(null, coverage.active(), null, null);
            }
            CoverageSpan period = coverage.span();
            return new Coverage(coverageId(record.storedMemberId(), period), coverage.active(), period.effective(),
                    period.end() == null ? Coverage.OPEN_END : period.end());
        }

        /**
         * MEMBER_ID + EFF_DATE + END_DATE run together (the form Onyx asked for), no separator, dates as yyyyMMdd, letters and digits only (the stored id loses its spaces and any punctuation);
         * 99991231 stands for an open-ended period.
         */
        private static String coverageId(String storedMemberId, CoverageSpan period) {
            DateTimeFormatter compact = DateTimeFormatter.BASIC_ISO_DATE;
            return storedMemberId.replaceAll("[^A-Za-z0-9]", "") + period.effective().format(compact)
                    + (period.end() == null ? "99991231" : period.end().format(compact));
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
            return new Resolved(Outcome.NOT_FOUND, null, null, null, 0, mmiResult.requestId(), 0, note);
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
            selection = selector.select(records, v.dateOfBirth(), v.dateOfService(), v.lastDateOfService());
        } catch (ResolutionException e) {
            throw e.withMmiRequestId(mmiResult.requestId());
        }
        if (selection instanceof SelectionResult.Ambiguous a) {
            return new Resolved(Outcome.AMBIGUOUS, null, null, a.reason(), a.persons(), mmiResult.requestId(), members.size(), null);
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
        return new Resolved(coverage.active() ? Outcome.ACTIVE : Outcome.INACTIVE, s.record(), coverage, null, 1,
                mmiResult.requestId(), members.size(), null);
    }

    /**
     * One plain sentence per outcome, for a human reading the response. It carries what used to be separate fields:
     * why an INACTIVE member is not covered, and what to do about an AMBIGUOUS answer.
     */
    private static String message(Resolved r, RequestValidator.Validated v) {
        String when = v.isPeriod() ? "from " + v.dateOfService() + " to " + v.lastDateOfService() : "on " + v.dateOfService();
        return switch (r.outcome()) {
            case ACTIVE -> "Member found; coverage active " + when;
            case INACTIVE -> "Member found; " + switch (r.coverage().reason()) {
                case NO_COVERAGE_RECORDS -> "no coverage on record";
                case NOT_YET_EFFECTIVE -> "coverage not yet effective on " + v.dateOfService();
                case COVERAGE_ENDED -> "coverage ended before " + v.dateOfService();
                case COVERAGE_GAP -> "no coverage on " + v.dateOfService() + " (gap between coverage periods)";
                case COVERAGE_ENDS_WITHIN_PERIOD -> "coverage active on " + v.dateOfService() + " but ends " + r.coverage().span().end()
                        + ", before " + v.lastDateOfService();
                case COVERED -> throw new IllegalStateException("INACTIVE with reason COVERED");
            };
            case NOT_FOUND -> "No member found for this id";
            case AMBIGUOUS -> switch (r.ambiguityReason()) {
                case MemberSelector.DOB_NOT_DISCRIMINATING -> "Several members match this id and date of birth; resend with the member's full id including the suffix";
                case MemberSelector.DOB_NOT_ON_RECORDS -> "Several members match this id and their records carry no date of birth to check; resend with the member's full id including the suffix";
                default -> "Several members match this id; resend with dateOfBirth or the member's full id including the suffix";
            };
        };
    }

    private boolean hasErrorMessage(List<MmiMessage> messages) {
        return messages.stream().anyMatch(this::isError);
    }

    private boolean isError(MmiMessage m) {
        return m.messageType() != null && mmiProperties.errorMessageTypes().stream().anyMatch(t -> t.equalsIgnoreCase(m.messageType().strip()));
    }

    private static void logOutcome(Resolved r, RequestValidator.Validated v, long start) {
        log.info("resolution clientId={} clientType={} outcome={} reason={} memberId={} resolved={} company={} lob={} dos={} dosEnd={} dosDefaulted={} records={} persons={} mmiRequestId={} ms={}",
                v.clientId(), v.clientType(), r.outcome(), r.coverage() == null ? "-" : r.coverage().reason(),
                v.memberId(), r.storedMemberId(), r.company(), r.lineOfBusiness(),
                v.dateOfService(), v.dateOfServiceEnd() == null ? "-" : v.dateOfServiceEnd(), v.dateOfServiceDefaulted(), r.records(), r.persons(), r.mmiRequestId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
