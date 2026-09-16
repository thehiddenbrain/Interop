package com.thehiddenbrain.interop.patientaccess.history;

import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies {@code paw.history.retention-days} at startup and once a day. */
@Component
public class HistoryRetention {

    private final RequestLog history;
    private final WorkbenchProperties properties;

    public HistoryRetention(RequestLog history, WorkbenchProperties properties) {
        this.history = history;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT24H")
    public void purge() {
        history.purgeOlderThan(properties.history().retentionDays());
    }
}
