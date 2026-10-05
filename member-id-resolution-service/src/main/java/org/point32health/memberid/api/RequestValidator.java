package org.point32health.memberid.api;

import org.point32health.memberid.config.MemberIdProperties;
import org.point32health.memberid.domain.InvalidRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates the whole request in one pass and reports every problem together. No MMI call for an invalid request.
 *
 * <p>The member id itself is only checked for presence. Whatever the EMR typed (9, 10, 11 or 40 characters, hyphens,
 * spaces, letters) is sent to MMI exactly as received, with only surrounding whitespace removed; MMI decides whether it
 * knows the id. This service never judges the shape of a member id.
 *
 * <p>The date of service is the date the answer is about (owner feedback 20). A date that was sent is always the
 * date evaluated, never replaced by today: a value that is not a real {@code yyyy-MM-dd} date, or is older than the
 * coverage history MMI keeps, is a 400 on both operations. Only a missing or blank date defaults to today. There is no
 * upper limit: a future date is judged against the coverage on record, which is the only way a 2027 date can be told
 * apart from a 2026 one. The same holds for a sent date of birth: unusable means 400, never silently dropped.
 *
 * <p>Two modes. {@code /resolve} is strict and needs a vendor. {@code /vendor-map} is lenient about what it does not
 * need: the vendor and unknown properties are accepted and ignored, and only the member id is required.
 */
@Component
public class RequestValidator {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern VENDOR = Pattern.compile("^[A-Za-z0-9 _.()-]{1,40}$");

    /**
     * @param memberId the id as Onyx sent it, surrounding whitespace removed: what is sent to MMI and echoed back
     * @param vendor   the vendor code or alias as sent; null for the vendor-map operation
     */
    public record Validated(String memberId, LocalDate dateOfService, boolean dateOfServiceDefaulted,
            LocalDate dateOfBirth, String vendor) {
    }

    private enum Mode { STRICT, LENIENT }

    private final MemberIdProperties properties;
    private final Clock clock;

    public RequestValidator(MemberIdProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Operation 1 ({@code /resolve}), strict: member id, dates and a vendor; every problem is reported. */
    public Validated validate(ResolveRequest request) {
        String dob = request.patient() == null ? null : request.patient().dateOfBirth();
        return validate(request.memberId(), request.dateOfService(), dob, request.vendor(), Mode.STRICT);
    }

    /** Operation 2 ({@code /vendor-map}), lenient: only the member id is required; a vendor is ignored. */
    public Validated validate(VendorMapRequest request) {
        String dob = request.patient() == null ? null : request.patient().dateOfBirth();
        return validate(request.memberId(), request.dateOfService(), dob, null, Mode.LENIENT);
    }

    private Validated validate(String rawMemberId, String rawDateOfService, String rawDob, String rawVendor, Mode mode) {
        List<ErrorDetail> details = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        String memberId = rawMemberId == null ? "" : rawMemberId.strip();
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

        LocalDate dob = null;
        if (rawDob != null && !rawDob.isBlank()) {
            dob = parseDate(rawDob);
            if (dob == null) {
                details.add(new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_INVALID", "expected a real date in yyyy-MM-dd"));
            } else if (dob.isAfter(today) || dob.isBefore(today.minusYears(properties.dateOfBirthMaxAgeYears()))) {
                details.add(new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_OUT_OF_RANGE",
                        "must not be in the future or more than " + properties.dateOfBirthMaxAgeYears() + " years ago"));
            }
        }

        String vendor = null;
        if (mode == Mode.STRICT) {
            vendor = rawVendor == null ? "" : rawVendor.strip();
            if (vendor.isEmpty()) {
                details.add(new ErrorDetail("vendor", "VENDOR_MISSING", "vendor is required"));
            } else if (!VENDOR.matcher(vendor).matches()) {
                details.add(new ErrorDetail("vendor", "VENDOR_INVALID", "vendor must be 1-40 letters, digits, spaces or _ . ( ) -"));
            }
        }

        if (!details.isEmpty()) {
            throw new InvalidRequestException(details);
        }
        return new Validated(memberId, dos, defaulted, dob, vendor);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.strip(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
