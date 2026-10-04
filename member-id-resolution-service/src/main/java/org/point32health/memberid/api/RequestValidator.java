package org.point32health.memberid.api;

import org.point32health.memberid.config.MemberIdProperties;
import org.point32health.memberid.domain.InvalidRequestException;
import org.point32health.memberid.domain.MemberIdParser;
import org.point32health.memberid.domain.ParsedMemberId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates the whole request in one pass and reports every problem together. No MMI call for an invalid request. */
@Component
public class RequestValidator {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern VENDOR = Pattern.compile("^[A-Za-z0-9 _.()-]{1,40}$");

    public record Validated(ParsedMemberId memberId, LocalDate dateOfService, boolean dateOfServiceDefaulted,
            LocalDate dateOfBirth, String vendor) {
    }

    private final MemberIdParser parser;
    private final MemberIdProperties properties;
    private final Clock clock;

    public RequestValidator(MemberIdParser parser, MemberIdProperties properties, Clock clock) {
        this.parser = parser;
        this.properties = properties;
        this.clock = clock;
    }

    public Validated validate(ResolveRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        ParsedMemberId parsed = null;
        try {
            parsed = parser.parse(request.memberId());
        } catch (InvalidRequestException e) {
            details.addAll(e.details());
        }

        LocalDate dos = null;
        boolean defaulted = false;
        if (request.dateOfService() == null || request.dateOfService().isBlank()) {
            dos = today;
            defaulted = true;
        } else {
            dos = parseDate(request.dateOfService());
            if (dos == null) {
                details.add(new ErrorDetail("dateOfService", "DATE_OF_SERVICE_INVALID", "expected a real date in yyyy-MM-dd"));
            } else {
                LocalDate min = today.minusYears(properties.dateOfService().maxPastYears());
                LocalDate max = today.plusDays(properties.dateOfService().maxFutureDays());
                if (dos.isBefore(min) || dos.isAfter(max)) {
                    details.add(new ErrorDetail("dateOfService", "DATE_OF_SERVICE_OUT_OF_RANGE",
                            "must be between " + min + " and " + max));
                }
            }
        }

        LocalDate dob = null;
        String rawDob = request.patient() == null ? null : request.patient().dateOfBirth();
        if (rawDob != null && !rawDob.isBlank()) {
            dob = parseDate(rawDob);
            if (dob == null) {
                details.add(new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_INVALID", "expected a real date in yyyy-MM-dd"));
            } else if (dob.isAfter(today) || dob.isBefore(today.minusYears(properties.dateOfBirthMaxAgeYears()))) {
                details.add(new ErrorDetail("patient.dateOfBirth", "DATE_OF_BIRTH_OUT_OF_RANGE",
                        "must not be in the future or more than " + properties.dateOfBirthMaxAgeYears() + " years ago"));
                dob = null;
            }
        }

        String vendor = request.vendor() == null ? "" : request.vendor().strip();
        if (vendor.isEmpty()) {
            details.add(new ErrorDetail("vendor", "VENDOR_MISSING", "vendor is required"));
        } else if (!VENDOR.matcher(vendor).matches()) {
            details.add(new ErrorDetail("vendor", "VENDOR_INVALID", "vendor must be 1-40 letters, digits, spaces or _ . ( ) -"));
        }

        if (!details.isEmpty()) {
            throw new InvalidRequestException(details);
        }
        return new Validated(parsed, dos, defaulted, dob, vendor);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.strip(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
