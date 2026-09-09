package com.thehiddenbrain.interop.cms1500.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Maps xs:dateTime to java.time.OffsetDateTime (ISO-8601 with offset). */
public class OffsetDateTimeXmlAdapter extends XmlAdapter<String, OffsetDateTime> {

    @Override
    public OffsetDateTime unmarshal(String value) {
        return (value == null || value.isBlank()) ? null : OffsetDateTime.parse(value.trim());
    }

    @Override
    public String marshal(OffsetDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
