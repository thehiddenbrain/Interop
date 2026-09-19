package com.thehiddenbrain.interop.extract.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/** Plan holidays per calendar, so "every business day" means what the vendor expects. */
@Service
public class BusinessCalendar {

    public record Calendar(String id, String name, List<String> holidays, Map<String, String> names) {}

    private final Map<String, Calendar> calendars = new LinkedHashMap<>();

    public BusinessCalendar(AppProperties props, ObjectMapper mapper) {
        Path p = props.getDataDir().resolve("calendars.json");
        if (Files.exists(p)) {
            try {
                for (Calendar c : mapper.readValue(p.toFile(), new TypeReference<List<Calendar>>() {})) calendars.put(c.id(), c);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + p, e);
            }
        }
    }

    public Collection<Calendar> all() { return calendars.values(); }

    public boolean isHoliday(String calendarId, LocalDate d) {
        Calendar c = calendarId == null ? null : calendars.get(calendarId);
        return c != null && c.holidays().contains(d.toString());
    }

    public String holidayName(String calendarId, LocalDate d) {
        Calendar c = calendarId == null ? null : calendars.get(calendarId);
        return c == null || c.names() == null ? null : c.names().get(d.toString());
    }

    public boolean isBusinessDay(String calendarId, LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !isHoliday(calendarId, d);
    }

    public LocalDate nextBusinessDay(String calendarId, LocalDate d) {
        LocalDate x = d.plusDays(1);
        while (!isBusinessDay(calendarId, x)) x = x.plusDays(1);
        return x;
    }
}
