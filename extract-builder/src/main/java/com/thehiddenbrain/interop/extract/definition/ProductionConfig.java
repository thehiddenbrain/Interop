package com.thehiddenbrain.interop.extract.definition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Operational settings attached at productionalize time. They live outside the frozen spec so a cron or folder
 * change never forces a re-sample, but every run snapshots the config it used and every edit is audited.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductionConfig {

    public FileNaming fileName = new FileNaming();
    /** NONE or GZIP. */
    public String compression = "NONE";
    /** AXWAY, APP or NONE: who applies PGP. */
    public String pgpBy = "AXWAY";
    public Schedule schedule = new Schedule();
    public Delivery delivery = new Delivery();
    public Quality quality = new Quality();
    public Notifications notifications = new Notifications();
    public Sla sla = new Sla();
    public String initialWatermark;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileNaming {
        /** Tokens: {VENDOR}, {DATE:pattern}, {TIME:pattern}, {SEQ:n}, {VERSION}, {SUBJECT}. */
        public String pattern = "{VENDOR}_{SUBJECT}_{DATE:yyyyMMdd}.txt";
        /** BUSINESS_DATE or RUN_TIME. */
        public String dateSource = "BUSINESS_DATE";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Schedule {
        /** DAILY, WEEKDAYS, WEEKLY, MONTHLY or CUSTOM. */
        public String preset = "WEEKDAYS";
        public String time = "02:00";
        public String timezone = "America/Chicago";
        /** Spring 6-field cron; derived from preset and time unless preset is CUSTOM. */
        public String cron = "0 0 2 * * MON-FRI";
        public String calendar = "plan-2026";
        /** NEXT_BUSINESS_DAY, SKIP or RUN_ANYWAY. */
        public String holidayPolicy = "NEXT_BUSINESS_DAY";
        /** RUN_ONCE or SKIP. */
        public String misfire = "RUN_ONCE";
        public boolean enabled = true;
        public String dayOfWeek = "MON";
        public int dayOfMonth = 1;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delivery {
        /** PROD or TEST route of the partner. */
        public String route = "PROD";
        /** SEND_EMPTY, SKIP or FAIL. */
        public String zeroRowPolicy = "SEND_EMPTY";
        public int retries = 3;
        public int backoffSeconds = 300;
        public boolean controlFile = true;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Quality {
        public boolean enabled = true;
        /** Hold the file if the row count moves more than this percent from the last delivered run. */
        public int rowCountVariancePct = 25;
        /** Hold if any field's null rate exceeds this percent. */
        public int nullRateMaxPct = 40;
        /** Hold if lookup misses exceed this count. */
        public int lookupMissMax = 0;
        public boolean holdOnBreach = true;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Notifications {
        public List<String> onFailure = new ArrayList<>(List.of("mft-ops@plan.example"));
        public List<String> onHold = new ArrayList<>();
        public List<String> onDelivered = new ArrayList<>();
        public List<String> onSlaMissed = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Sla {
        public String deliverBy = "07:00";
        public String note;
    }
}
