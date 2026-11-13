package org.p32h.interop.service;

import org.p32h.interop.api.Coverage;
import org.p32h.interop.api.MemberResolutionRequest;
import org.p32h.interop.api.MemberResolutionResponse;
import org.p32h.interop.api.SourceMessage;
import org.p32h.interop.api.Outcome;
import org.p32h.interop.api.RequestValidator;
import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.coverage.CoverageLookupException;
import org.p32h.interop.coverage.MemberCoverageRow;
import org.p32h.interop.coverage.MemberCoverageRowRepository;
import org.p32h.interop.domain.CoverageDecision;
import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.CoverageSpan;
import org.p32h.interop.domain.MemberRecord;
import org.p32h.interop.domain.MemberSelector;
import org.p32h.interop.domain.ResolutionException;
import org.p32h.interop.domain.SelectionResult;
import org.p32h.interop.memberinfo.MemberInfoClient;
import org.p32h.interop.memberinfo.MemberInfoException;
import org.p32h.interop.memberinfo.MemberInfoResponse;
import org.p32h.interop.memberinfo.MemberPlan;
import org.p32h.interop.mmi.MmiClient;
import org.p32h.interop.mmi.MmiException;
import org.p32h.interop.mmi.MmiMember;
import org.p32h.interop.mmi.MmiMessage;
import org.p32h.interop.mmi.MmiProperties;
import org.p32h.interop.mmi.MmiRecordMapper;
import org.p32h.interop.mmi.MmiResult;
import org.p32h.interop.vendor.Payer;
import org.p32h.interop.vendor.VendorFormatter;
import org.p32h.interop.vendor.VendorRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * The member resolution, {@code POST /v1/interop/resolve}: ask MMI once with the member id exactly as received,
 * reduce the records to one person, ask the member information service for that member's coverage records to derive
 * the line of business, read the member's rows in the coverage view, which decide ACTIVE or INACTIVE on the date (or
 * period) of service and give the coverage dates and the coverageId, and render the stored id for every configured
 * vendor.
 */
@Service
public class ResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionService.class);

    /** The reason logged for a NOT_FOUND answered by the recent-coverage rule (the member exists, the answer says nothing of it). */
    static final String NO_RECENT_COVERAGE = "NO_RECENT_COVERAGE";

    private static final CoverageEvaluator COVERAGE = new CoverageEvaluator();

    private final RequestValidator validator;
    private final VendorRegistry vendors;
    private final MmiClient mmi;
    private final MmiProperties mmiProperties;
    private final MmiRecordMapper mapper;
    private final MemberSelector selector;
    private final VendorFormatter formatter;
    private final MemberInfoClient memberInfo;
    private final MemberCoverageRowRepository coverageRows;
    private final LineOfBusinessDeriver lineOfBusiness;
    private final MemberIdProperties properties;
    private final Clock clock;

    public ResolutionService(RequestValidator validator, VendorRegistry vendors, MmiClient mmi, MmiProperties mmiProperties,
            MmiRecordMapper mapper, MemberSelector selector, VendorFormatter formatter, MemberInfoClient memberInfo,
            MemberCoverageRowRepository coverageRows, LineOfBusinessDeriver lineOfBusiness,
            MemberIdProperties properties, Clock clock) {
        this.validator = validator;
        this.vendors = vendors;
        this.mmi = mmi;
        this.mmiProperties = mmiProperties;
        this.mapper = mapper;
        this.selector = selector;
        this.formatter = formatter;
        this.memberInfo = memberInfo;
        this.coverageRows = coverageRows;
        this.lineOfBusiness = lineOfBusiness;
        this.properties = properties;
        this.clock = clock;
    }

    /** Resolves the member behind the id and renders the stored id, with the vendor's payer, for every configured vendor. */
    public MemberResolutionResponse resolve(MemberResolutionRequest request, String correlationId) {
        long start = System.nanoTime();
        RequestValidator.Validated v = validator.validate(request);
        Resolved found = resolveMember(v, correlationId);
        String lob = found.record() == null ? null : lineOfBusiness(found, v.dateOfService(), correlationId);
        List<MemberCoverageRow> rows = found.record() == null ? List.of() : coverageRows(found);
        Resolved r = found.record() == null ? found : found.decidedOn(coverage(found, rows, v.dateOfService()));
        MemberCoverageRow row = shownRow(rows, v.dateOfService());
        List<MemberResolutionResponse.VendorMemberId> forVendors = null;
        if (r.record() != null) {
            List<MemberResolutionResponse.HumanName> name = MemberResolutionResponse.HumanName.of(r.record().name());
            if (name == null) {
                log.warn("marker=MEMBER_NAME_MISSING memberId={}: the member record has neither a first nor a last name; "
                        + "the vendor entries carry no name", r.storedMemberId());
            }
            forVendors = vendors.all().stream()
                    .map(vendor -> {
                        if (!vendor.payerByCompany().isEmpty() && !vendor.hasOwnPayerFor(r.company(), r.recordLineOfBusiness())) {
                            log.warn("marker=VENDOR_PAYER_DEFAULTED vendor={} company={} lineOfBusiness={}: the vendor keys the payer on "
                                    + "the company and has no entry for this one; the default payer was returned", vendor.code(), r.company(),
                                    r.recordLineOfBusiness());
                        }
                        Payer payer = vendor.payerFor(r.company(), r.recordLineOfBusiness());
                        return new MemberResolutionResponse.VendorMemberId(vendor.code(),
                                formatter.format(r.record().storedMemberId(), vendor.format()).value(), payer.id(), payer.name(), name);
                    })
                    .toList();
        }
        MemberResolutionResponse response = new MemberResolutionResponse(r.outcome(), message(r, v),
                new MemberResolutionResponse.MemberId(v.memberId(), r.storedMemberId(), forVendors),
                lob, v.dateOfService(), v.dateOfServiceEnd(), v.dateOfServiceDefaulted(),
                r.coverageBlock(row), v.requestId(), r.mmiRequestId(), r.mmiNote());
        logOutcome(r, v, lob, start);
        return response;
    }

    /**
     * The identified member's coverage records from the member information service, and the line of business derived from
     * the record that covers the date of service, or from the most recent record when none does. A failure of that service fails the answer (503 or 502, carrying MMI's
     * request id as traceId).
     */
    private String lineOfBusiness(Resolved r, LocalDate dateOfService, String correlationId) {
        MemberInfoResponse info;
        try {
            info = memberInfo.lookup(r.storedMemberId(), dateOfService, correlationId);
        } catch (MemberInfoException e) {
            throw e.withTraceId(r.mmiRequestId());
        }
        List<MemberPlan> coverageRecords = info.coverageRecordsFor(r.storedMemberId());
        return lineOfBusiness.derive(r.record(), coverageRecords, dateOfService);
    }

    /**
     * The identified member's rows in the coverage view, read once. A database that cannot be reached (a
     * {@code TransactionException}: no connection), does not answer in time or fails the query fails the answer: 503,
     * carrying MMI's request id as traceId.
     */
    private List<MemberCoverageRow> coverageRows(Resolved found) {
        List<MemberCoverageRow> rows;
        try {
            rows = coverageRows.findByMemberId(found.storedMemberId());
        } catch (DataAccessException | TransactionException e) {
            throw new CoverageLookupException(found.mmiRequestId(), e);
        }
        if (rows.isEmpty()) {
            log.warn("marker=COVERAGE_ROW_NOT_FOUND memberId={}: the coverage view has no row for this member; answered "
                    + "INACTIVE, the coverage block carries the flag only", found.storedMemberId());
        }
        return rows;
    }

    /**
     * ACTIVE or INACTIVE on the date of service, decided on the member's rows in the coverage view: covered when a row
     * covers the date, and without any row not covered. MMI's spans tell who the member is; they do not decide this,
     * and an answer on which the two disagree is logged.
     */
    private static CoverageDecision coverage(Resolved found, List<MemberCoverageRow> rows, LocalDate dateOfService) {
        CoverageDecision decision = COVERAGE.evaluate(
                rows.stream().map(c -> new CoverageSpan(c.getEffDate(), c.getEndDate())).toList(), dateOfService);
        if (decision.active() != found.coverage().active()) {
            log.info("marker=COVERAGE_STATUS_DIFFERS memberId={} dos={}: the coverage view says {} ({}), MMI's spans say {} ({}); "
                    + "the view decides", found.storedMemberId(), dateOfService, decision.active() ? "covered" : "not covered",
                    decision.reason(), found.coverage().active() ? "covered" : "not covered", found.coverage().reason());
        }
        return decision;
    }

    /**
     * The row whose dates and coverageId the answer shows: the row that covers the date of service (the one that ends
     * last, then starts last, when several do), else the member's most recent row; null when the member has none.
     */
    private static MemberCoverageRow shownRow(List<MemberCoverageRow> rows, LocalDate dateOfService) {
        Comparator<MemberCoverageRow> latest = Comparator.comparing(MemberCoverageRow::getEndDate)
                .thenComparing(MemberCoverageRow::getEffDate);
        return rows.stream().filter(c -> c.covers(dateOfService)).max(latest)
                .orElseGet(() -> rows.stream().max(latest).orElse(null));
    }

    /** MMI's answer reduced to one outcome, before it is rendered. */
    private record Resolved(Outcome outcome, MemberRecord record, CoverageDecision coverage, String reason,
            int persons, String mmiRequestId, int records, SourceMessage mmiNote) {

        /** The same member with ACTIVE or INACTIVE as the coverage view has it, in place of what MMI's spans said. */
        Resolved decidedOn(CoverageDecision view) {
            return new Resolved(view.active() ? Outcome.ACTIVE : Outcome.INACTIVE, record, view, reason, persons, mmiRequestId,
                    records, mmiNote);
        }

        String storedMemberId() {
            return record == null ? null : record.storedMemberId();
        }

        String company() {
            return record == null ? null : record.company();
        }

        /** The line of business on the member record (MMI's own value, for example PP), not the derived one. */
        String recordLineOfBusiness() {
            return record == null ? null : record.lineOfBusiness();
        }

        /** The flag as decided on the member's rows in the coverage view; the id and the dates from the row shown, absent when the member has none. */
        Coverage coverageBlock(MemberCoverageRow row) {
            if (coverage == null) {
                return null;
            }
            if (row == null) {
                return new Coverage(null, coverage.active(), null, null);
            }
            return new Coverage(coverageId(record.storedMemberId(), row.getEffDate(), row.getEndDate()), coverage.active(),
                    row.getEffDate(), row.getEndDate());
        }

        /**
         * MEMBER_ID + EFF_DATE + END_DATE run together (the form Onyx asked for), no separator, dates as yyyyMMdd, letters and digits only (the stored id loses its spaces and any punctuation);
         * the dates are those of the member's row in the coverage view, where an open end is 12/31/3999.
         */
        private static String coverageId(String storedMemberId, LocalDate effective, LocalDate end) {
            DateTimeFormatter compact = DateTimeFormatter.BASIC_ISO_DATE;
            return storedMemberId.replaceAll("[^A-Za-z0-9]", "") + effective.format(compact) + end.format(compact);
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

        LocalDate recentSince = LocalDate.now(clock).minusMonths(properties.recentCoverageMonths());
        SelectionResult selection;
        try {
            selection = selector.select(records, v.dateOfBirth(), v.dateOfService(), recentSince);
        } catch (ResolutionException e) {
            throw e.withMmiRequestId(mmiResult.requestId());
        }
        if (selection instanceof SelectionResult.Ambiguous a) {
            return new Resolved(Outcome.AMBIGUOUS, null, null, a.reason(), a.persons(), mmiResult.requestId(), members.size(), null);
        }
        if (selection instanceof SelectionResult.NoneRecent n) {
            log.info("marker=NO_RECENT_COVERAGE mmiRequestId={} persons={} latestCoverageEnd={} recentSince={}: not covered on the date "
                    + "of service and no coverage in the last {} months; answered NOT_FOUND", mmiResult.requestId(), n.persons(),
                    n.latestCoverageEnd(), recentSince, properties.recentCoverageMonths());
            return new Resolved(Outcome.NOT_FOUND, null, null, NO_RECENT_COVERAGE, n.persons(), mmiResult.requestId(), members.size(), null);
        }
        SelectionResult.Selected s = (SelectionResult.Selected) selection;
        if (s.readableSpans() == 0 && s.unreadableSpans() > 0) {
            // every span MMI holds for the member is unreadable: whether this is a current member cannot be told from it
            throw MmiException.invalidResponse(mmiResult.requestId(), "UNREADABLE_COVERAGE",
                    "every coverage span on the member record has an unreadable date; coverage cannot be determined", null);
        }
        CoverageDecision coverage = s.coverage();
        if (s.unreadableSpans() > 0) {
            log.warn("marker=UNREADABLE_SPANS_ON_SELECTED mmiRequestId={} count={}", mmiResult.requestId(), s.unreadableSpans());
        }
        return new Resolved(coverage.active() ? Outcome.ACTIVE : Outcome.INACTIVE, s.record(), coverage, null, 1,
                mmiResult.requestId(), members.size(), null);
    }

    /**
     * One plain sentence per outcome, for a human reading the response. It carries what used to be separate fields:
     * why an INACTIVE member is not covered, and what to do about an AMBIGUOUS answer.
     */
    private static String message(Resolved r, RequestValidator.Validated v) {
        return switch (r.outcome()) {
            case ACTIVE -> !v.isPeriod() ? "Member found; coverage active on " + v.dateOfService()
                    : r.coverage().span().covers(v.lastDateOfService())
                            ? "Member found; coverage active from " + v.dateOfService() + " to " + v.lastDateOfService()
                            : "Member found; coverage active on " + v.dateOfService() + "; ends " + r.coverage().span().end()
                                    + ", before " + v.lastDateOfService();
            case INACTIVE -> "Member found; " + switch (r.coverage().reason()) {
                case NO_COVERAGE_RECORDS -> "no coverage on record"; // the member has no row in the coverage view
                case NOT_YET_EFFECTIVE -> "coverage not yet effective on " + v.dateOfService();
                case COVERAGE_ENDED -> "coverage ended before " + v.dateOfService();
                case COVERAGE_GAP -> "no coverage on " + v.dateOfService() + " (gap between coverage periods)";
                case COVERED -> throw new IllegalStateException("INACTIVE with reason COVERED");
            };
            case NOT_FOUND -> "No member found for this id";
            case AMBIGUOUS -> switch (r.reason()) {
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

    private static void logOutcome(Resolved r, RequestValidator.Validated v, String lob, long start) {
        log.info("resolution clientId={} clientType={} outcome={} reason={} memberId={} resolved={} company={} lob={} lobOnRecord={} dos={} dosEnd={} "
                        + "dosDefaulted={} records={} persons={} mmiRequestId={} ms={}",
                v.clientId(), v.clientType(), r.outcome(), r.coverage() != null ? r.coverage().reason() : r.reason() == null ? "-" : r.reason(),
                v.memberId(), r.storedMemberId(), r.company(), lob, r.recordLineOfBusiness(),
                v.dateOfService(), v.dateOfServiceEnd() == null ? "-" : v.dateOfServiceEnd(), v.dateOfServiceDefaulted(), r.records(), r.persons(), r.mmiRequestId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
