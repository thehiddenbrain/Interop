package org.p32h.interop.api;

import org.p32h.interop.config.MemberIdProperties;
import org.p32h.interop.domain.InvalidRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates the whole request in one pass and reports every problem together. No MMI call for an invalid request.
 *
 * <p>The caller must identify itself and the call: {@code clientId}, {@code clientType} ({@code EXT} or {@code INT}) and
 * {@code requestId} are required. A usable {@code requestId} is kept for the log and echoed even when another field
 * fails.
 *
 * <p>The member id itself is only checked for presence. Whatever the EMR typed (9, 10, 11 or 40 characters, hyphens,
 * spaces, letters) is sent to MMI exactly as received, with only surrounding whitespace removed; MMI decides whether it
 * knows the id. This service never judges the shape of a member id.
 *
 * <p>The date of service is the date the answer is about. A date that was sent is always the
 * date evaluated, never replaced by today: a value that is not a real {@code yyyy-MM-dd} date, or is older than the
 * coverage history MMI keeps, is a 400. Only a missing or blank date defaults to today. There is no
 * upper limit: a future date is judged against the coverage on record, which is the only way a 2027 date can be told
 * apart from a 2026 one. A prior authorization may cover a period: {@code dateOfServiceEnd}, optional,
 * makes the request about every day from {@code dateOfService} to it; it must be a real date, not before the start,
 * and never comes alone. The same holds for a sent date of birth: unusable means 400, never silently dropped.
 *
 * <p>Only what the operation needs is looked at: the member id, the dates and the date of birth. Anything else Onyx
 * sends, a vendor included, is ignored by the request type.
 */
@Component
public class RequestValidator {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9._-]{1,50}$");
    private static final Set<String> CLIENT_TYPES = Set.of("EXT", "INT");

    /**
     * @param clientId         the calling system
     * @param clientType       EXT or INT
     * @param requestId        the caller's id for this call, echoed in the response
     * @param memberId         the id as Onyx sent it, surrounding whitespace removed: what is sent to MMI and echoed back
     * @param dateOfService    the first (or only) date of service
     * @param dateOfServiceEnd the last date of service when a period was sent, else null
     */
    public record Validated(String clientId, String clientType, String requestId, String memberId, LocalDate dateOfService,
            LocalDate dateOfServiceEnd, boolean dateOfServiceDefaulted, LocalDate dateOfBirth) {

        /** The last date the answer is about: the end of the period, or the single date itself. */
        public LocalDate lastDateOfService() {
            return dateOfServiceEnd == null ? dateOfService : dateOfServiceEnd;
        }

        /** True when the request asked about more than one day. */
        public boolean isPeriod() {
            return dateOfServiceEnd != null && dateOfServiceEnd.isAfter(dateOfService);
        }
    }

    private final MemberIdProperties properties;
    private final Clock clock;

    public RequestValidator(MemberIdProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Caller identified, member id present, dates usable; every problem is reported together. */
    public Validated validate(MemberResolutionRequest request) {
        String rawDob = request.dateOfBirth();
        String rawDateOfService = request.dateOfService();
        String rawDateOfServiceEnd = request.dateOfServiceEnd();
        List<ErrorDetail> details = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        String memberId = request.memberId() == null ? "" : request.memberId().strip();
        if (memberId.isEmpty()) {
            details.add(new ErrorDetail("memberId", "MEMBER_ID_MISSING", "memberId is required"));
        }

        LocalDate dos;
        boolean defaulted = false;
        if (rawDateOfService == null || rawDateOfService.isBlank()) {
            dos = today;
            defaulted = true;
        } else {
            dos = parseDate(rawDateOfService);
            if (dos == null) {
                details.add(new ErrorDetail("dateOfService", "DATE_OF_SERVICE_INVALID", "expected a real date in yyyy-MM-dd"));
            } else {
                LocalDate min = today.minusYears(properties.dateOfService().maxPastYears());
                if (dos.isBefore(min)) {
                    details.add(new ErrorDetail("dateOfService", "DATE_OF_SERVICE_OUT_OF_RANGE",
                            "must not be before " + min + " (older than the coverage history on record)"));
                }
            }
        }

        LocalDate dosEnd = null;
        if (rawDateOfServiceEnd != null && !rawDateOfServiceEnd.isBlank()) {
            dosEnd = parseDate(rawDateOfServiceEnd);
            if (dosEnd == null) {
                details.add(new ErrorDetail("dateOfServiceEnd", "DATE_OF_SERVICE_END_INVALID", "expected a real date in yyyy-MM-dd"));
            } else if (defaulted) {
                details.add(new ErrorDetail("dateOfServiceEnd", "DATE_OF_SERVICE_END_WITHOUT_START",
                        "dateOfServiceEnd needs dateOfService, the first date of the period"));
            } else if (dos != null && dosEnd.isBefore(dos)) {
                details.add(new ErrorDetail("dateOfServiceEnd", "DATE_OF_SERVICE_END_BEFORE_START",
                        "dateOfServiceEnd must not be before dateOfService"));
            }
        }

        LocalDate dob = null;
        if (rawDob != null && !rawDob.isBlank()) {
            dob = parseDate(rawDob);
            if (dob == null) {
                details.add(new ErrorDetail("dateOfBirth", "DATE_OF_BIRTH_INVALID", "expected a real date in yyyy-MM-dd"));
            } else if (dob.isAfter(today) || dob.isBefore(today.minusYears(properties.dateOfBirthMaxAgeYears()))) {
                details.add(new ErrorDetail("dateOfBirth", "DATE_OF_BIRTH_OUT_OF_RANGE",
                        "must not be in the future or more than " + properties.dateOfBirthMaxAgeYears() + " years ago"));
            }
        }

        String clientId = strip(request.clientId());
        if (clientId.isEmpty()) {
            details.add(new ErrorDetail("clientId", "CLIENT_ID_MISSING", "clientId is required"));
        } else if (!CLIENT_ID.matcher(clientId).matches()) {
            details.add(new ErrorDetail("clientId", "CLIENT_ID_INVALID", "letters, digits, '.', '_' or '-', at most 50 characters"));
        }

        String clientType = strip(request.clientType());
        if (clientType.isEmpty()) {
            details.add(new ErrorDetail("clientType", "CLIENT_TYPE_MISSING", "clientType is required: EXT or INT"));
        } else if (!CLIENT_TYPES.contains(clientType)) {
            details.add(new ErrorDetail("clientType", "CLIENT_TYPE_INVALID", "must be EXT (external caller) or INT (internal caller)"));
        }

        String requestId = strip(request.requestId());
        if (requestId.isEmpty()) {
            details.add(new ErrorDetail("requestId", "REQUEST_ID_MISSING", "requestId is required: the caller's id for this call"));
        } else if (!CorrelationFilter.VALID.matcher(requestId).matches()) {
            details.add(new ErrorDetail("requestId", "REQUEST_ID_INVALID", "letters, digits, '.', '_', ':' or '-', at most 64 characters"));
        } else {
            CorrelationFilter.rememberRequestId(requestId);
        }

        if (!details.isEmpty()) {
            throw new InvalidRequestException(details);
        }
        return new Validated(clientId, clientType, requestId, memberId, dos, dosEnd, defaulted, dob);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.strip(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
