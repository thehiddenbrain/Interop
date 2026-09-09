package com.thehiddenbrain.interop.cms1500.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.LocalDate;

/** Maps xs:date to java.time.LocalDate (ISO-8601, e.g. 2026-09-09). */
public class LocalDateXmlAdapter extends XmlAdapter<String, LocalDate> {

    @Override
    public LocalDate unmarshal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // xs:date may carry a timezone suffix (2026-09-09Z / 2026-09-09-05:00); ignore it.
        String trimmed = value.trim();
        if (trimmed.length() > 10) {
            trimmed = trimmed.substring(0, 10);
        }
        return LocalDate.parse(trimmed);
    }

    @Override
    public String marshal(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
