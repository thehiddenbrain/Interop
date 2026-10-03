package com.thehiddenbrain.interop.memberid.service;

import com.thehiddenbrain.interop.memberid.api.Outcome;
import com.thehiddenbrain.interop.memberid.api.RequestValidator;
import com.thehiddenbrain.interop.memberid.api.ResolveRequest;
import com.thehiddenbrain.interop.memberid.api.ResolveResponse;
import com.thehiddenbrain.interop.memberid.domain.CoverageDecision;
import com.thehiddenbrain.interop.memberid.domain.InputShape;
import com.thehiddenbrain.interop.memberid.domain.MemberRecord;
import com.thehiddenbrain.interop.memberid.domain.MemberSelector;
import com.thehiddenbrain.interop.memberid.domain.SelectionResult;
import com.thehiddenbrain.interop.memberid.domain.UnknownVendorException;
import com.thehiddenbrain.interop.memberid.mmi.MmiClient;
import com.thehiddenbrain.interop.memberid.mmi.MmiException;
import com.thehiddenbrain.interop.memberid.mmi.MmiMember;
import com.thehiddenbrain.interop.memberid.mmi.MmiMessage;
import com.thehiddenbrain.interop.memberid.mmi.MmiProperties;
import com.thehiddenbrain.interop.memberid.mmi.MmiRecordMapper;
import com.thehiddenbrain.interop.memberid.mmi.MmiResult;
import com.thehiddenbrain.interop.memberid.support.Masking;
import com.thehiddenbrain.interop.memberid.vendor.FormattedMemberId;
import com.thehiddenbrain.interop.memberid.vendor.Vendor;
import com.thehiddenbrain.interop.memberid.vendor.VendorFormatter;
import com.thehiddenbrain.interop.memberid.vendor.VendorRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The whole request in order: validate, find the vendor, ask MMI once, reduce the records to one person,
 * decide coverage on the date of service, render the stored id for the vendor, answer.
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

    public ResolveResponse resolve(ResolveRequest request, String correlationId) {
        long start = System.nanoTime();
        RequestValidator.Validated v = validator.validate(request);
        Vendor vendor = vendors.find(v.vendor()).orElseThrow(() -> new UnknownVendorException(vendors.knownCodes()));

        MmiResult mmiResult = mmi.search(v.memberId().searched(), correlationId);
        List<MmiMember> members = mmiResult.response().membersOrEmpty();
        List<MmiMessage> messages = mmiResult.response().messagesOrEmpty();

        if (members.isEmpty()) {
            if (hasErrorMessage(messages)) {
                MmiMessage m = messages.stream().filter(this::isError).findFirst().orElseThrow();
                throw MmiException.rejected(mmiResult.requestId(), "ERROR_MESSAGE",
                        "MMI reported an error: type=" + m.messageType() + " status=" + m.statusCode() + " code=" + m.messageCode());
            }
            ResolveResponse response = new ResolveResponse(Outcome.NOT_FOUND, idBlock(v, null, null), vendor.code(), null, null,
                    v.dateOfService(), v.dateOfServiceDefaulted(), null, null, null, correlationId, mmiResult.requestId());
            logOutcome(response, v, 0, start);
            return response;
        }
        if (hasErrorMessage(messages)) {
            log.warn("marker=MMI_ERROR_MESSAGE_WITH_MEMBERS mmiRequestId={} messages={}", mmiResult.requestId(), messages);
        }

        List<MemberRecord> records = members.stream().map(mapper::toRecord).filter(r -> r.matchKey() != null).toList();
        if (records.isEmpty()) {
            throw MmiException.invalidResponse(mmiResult.requestId(), "NO_MEMBER_ID", "every MMI record lacks a memberId", null);
        }

        SelectionResult selection = selector.select(records, v.dateOfBirth(), v.dateOfService());
        ResolveResponse response;
        if (selection instanceof SelectionResult.Ambiguous a) {
            String hint = v.memberId().shape() == InputShape.THP_9
                    ? "Resend with the member's 11-character id (9 digits + suffix) or add patient.dateOfBirth"
                    : "Add patient.dateOfBirth or route to intake for a manual pick";
            List<ResolveResponse.Candidate> candidates = a.candidates().stream()
                    .map(c -> new ResolveResponse.Candidate(c.storedMemberId(), c.company(), c.lineOfBusiness(), c.coverageActive()))
                    .toList();
            response = new ResolveResponse(Outcome.AMBIGUOUS, idBlock(v, null, null), vendor.code(), null, null,
                    v.dateOfService(), v.dateOfServiceDefaulted(), null, new ResolveResponse.Ambiguity(a.reason(), hint), candidates,
                    correlationId, mmiResult.requestId());
        } else {
            SelectionResult.Selected s = (SelectionResult.Selected) selection;
            MemberRecord record = s.record();
            CoverageDecision coverage = s.coverage();
            FormattedMemberId formatted = formatter.format(record.storedMemberId(), vendor.format());
            ResolveResponse.Span span = coverage.span() == null ? null
                    : new ResolveResponse.Span(coverage.span().effective(), coverage.span().end());
            response = new ResolveResponse(coverage.active() ? Outcome.ACTIVE : Outcome.INACTIVE,
                    idBlock(v, record.storedMemberId(), formatted), vendor.code(), record.company(), record.lineOfBusiness(),
                    v.dateOfService(), v.dateOfServiceDefaulted(),
                    new ResolveResponse.Coverage(coverage.active(), coverage.reason(), span, coverage.lastEndDate(), coverage.nextEffectiveDate()),
                    null, null, correlationId, mmiResult.requestId());
            if (record.unreadableSpans() > 0) {
                log.warn("marker=UNREADABLE_SPANS_ON_SELECTED mmiRequestId={} count={} outcome={}", mmiResult.requestId(),
                        record.unreadableSpans(), response.outcome());
            }
        }
        logOutcome(response, v, members.size(), start);
        return response;
    }

    private static ResolveResponse.MemberId idBlock(RequestValidator.Validated v, String stored, FormattedMemberId formatted) {
        return new ResolveResponse.MemberId(v.memberId().received(), v.memberId().searched(), stored,
                formatted == null ? null : formatted.value(),
                formatted == null || formatted.parts() == null ? null
                        : new ResolveResponse.Parts(formatted.parts().memberId(), formatted.parts().suffix()));
    }

    private boolean hasErrorMessage(List<MmiMessage> messages) {
        return messages.stream().anyMatch(this::isError);
    }

    private boolean isError(MmiMessage m) {
        return m.messageType() != null && mmiProperties.errorMessageTypes().stream().anyMatch(t -> t.equalsIgnoreCase(m.messageType().strip()));
    }

    private static void logOutcome(ResolveResponse r, RequestValidator.Validated v, int records, long start) {
        log.info("resolve outcome={} reason={} vendor={} shape={} memberId={} stored={} company={} lob={} dos={} dosDefaulted={} records={} mmiRequestId={} ms={}",
                r.outcome(), r.coverage() == null ? "-" : r.coverage().reason(), r.vendor(), v.memberId().shape(),
                Masking.memberId(v.memberId().searched()), Masking.memberId(r.memberId().stored()), r.company(), r.lineOfBusiness(),
                r.dateOfService(), r.dateOfServiceDefaulted(), records, r.mmiRequestId(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
