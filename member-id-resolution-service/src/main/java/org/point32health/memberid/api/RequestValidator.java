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
 * <p>Two modes. {@code /resolve} is strict: a bad date or a missing vendor is a 400 naming every problem. {@code /vendor-map}
 * is lenient: only the member id is required; an unusable date of service or date of birth is ignored (the date of
 * service then defaults to today) and reported back under {@code ignoredFields}, never rejected.
 */
@Component
public class RequestValidator {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern VENDOR = Pattern.compile("^[A-Za-z0-9 _.()-]{1,40}$");

    /**
     * @param memberId      the id as Onyx sent it, surrounding whitespace removed: what is sent to MMI and echoed back
     * @param vendor        the vendor code or alias as sent; null for the vendor-map operation
     * @param ignoredFields request fields that were present but unusable and therefore ignored (lenient mode only)
     */
    public record Validated(String memberId, LocalDate dateOfService, boolean dateOfServiceDefaulted,
            LocalDate dateOfBirth, String vendor, List<String> ignoredFields) {
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

    /** Operation 2 ({@code /vendor-map}), lenient: only the member id is required; unusable dates are ignored. */
    public Validated validate(VendorMapRequest request) {
        String dob = request.patient() == null ? null : request.patient().dateOfBirth();
        return validate(request.memberId(), request.dateOfService(), dob, null, Mode.LENIENT);
    }

    private Validated validate(String rawMemberId, String rawDateOfService, String rawDob, String rawVendor, Mode mode) {
        List<ErrorDetail> details = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        String memberId = rawMemberId == null ? "" : rawMemberId.strip();
        if (memberId.isEmpty()) {
            details.add(new ErrorDetail("memberId", "MEMBER_ID_MISSING", "memberId is required"));
        }

        LocalDate dos = null;
        boolean defaulted = false;
        if (rawDateOfService == null || rawDateOfService.isBlank()) {
            dos = today;
            defaulted = true;
        } else {
            dos = parseDate(rawDateOfService);
            ErrorDetail problem = null;
            if (dos == null) {
                problem = new ErrorDetail("dateOfService", "DATE_OF_SERVICE_INVALID", "expected a real date in yyyy-MM-dd");
            } else {
                LocalDate min = today.minusYears(properties.dateOfService().maxPastYears());
                LocalDate max = today.plusDays(properties.dateOfService().maxFutureDays());
                if (dos.isBefore(min) || dos.isAfter(max)) {
                    problem = new ErrorDetail("dateOfService", "DATE_OF_SERVICE_OUT_OF_RANGE", "must be between " + min + " and " + max);
                }
            }
            if (problem != null) {
                if (mode == Mode.STRICT) {
                    details.add(problem);
                } else {
                    ignored.add("dateOfService");
                    dos = today;
                    defaulted = true;
                }
            }
        }

        LocalDate dob = null;
        if (rawDob != null && !rawDob.isBlank()) {
            dob = parseDate(rawDob);
            ErrorDetail problem = null;
            if (dob == null) {
                problem = new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_INVALID", "expected a real date in yyyy-MM-dd");
            } else if (dob.isAfter(today) || dob.isBefore(today.minusYears(properties.dateOfBirthMaxAgeYears()))) {
                problem = new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_OUT_OF_RANGE",
                        "must not be in the future or more than " + properties.dateOfBirthMaxAgeYears() + " years ago");
                dob = null;
            }
            if (problem != null) {
                if (mode == Mode.STRICT) {
                    details.add(problem);
                } else {
                    ignored.add("patient.dateOfBirth");
                    dob = null;
                }
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
        return new Validated(memberId, dos, defaulted, dob, vendor, List.copyOf(ignored));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.strip(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
