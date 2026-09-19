package com.thehiddenbrain.interop.extract.runtime;

import com.thehiddenbrain.interop.extract.audit.NotificationService;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.ProductionConfig;
import com.thehiddenbrain.interop.extract.definition.VersionStatus;
import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fires production runs on their cron schedule in the partner's timezone, honouring the business-day calendar,
 * the misfire policy and the contract end date. Also watches the delivery SLA and contract expiry.
 */
@Service
public class Scheduler {

    private final StateStores stores;
    private final RunService runs;
    private final BusinessCalendar calendar;
    private final PartnerService partners;
    private final NotificationService notifications;
    private final DemoSettings settings;

    public Scheduler(StateStores stores, RunService runs, BusinessCalendar calendar, PartnerService partners, NotificationService notifications, DemoSettings settings) {
        this.stores = stores;
        this.runs = runs;
        this.calendar = calendar;
        this.partners = partners;
        this.notifications = notifications;
        this.settings = settings;
    }

    public static String cronFor(ProductionConfig.Schedule s) {
        if ("CUSTOM".equals(s.preset) && s.cron != null && !s.cron.isBlank()) return s.cron;
        String[] hm = (s.time == null || s.time.isBlank() ? "02:00" : s.time).split(":");
        int h = Integer.parseInt(hm[0]);
        int m = hm.length > 1 ? Integer.parseInt(hm[1]) : 0;
        return switch (s.preset == null ? "WEEKDAYS" : s.preset) {
            case "DAILY" -> String.format("0 %d %d * * *", m, h);
            case "WEEKLY" -> String.format("0 %d %d * * %s", m, h, s.dayOfWeek == null ? "MON" : s.dayOfWeek);
            case "MONTHLY" -> String.format("0 %d %d %d * *", m, h, Math.max(1, Math.min(28, s.dayOfMonth)));
            default -> String.format("0 %d %d * * MON-FRI", m, h);
        };
    }

    public static ZoneId zone(ProductionConfig.Schedule s) {
        try {
            return ZoneId.of(s.timezone == null || s.timezone.isBlank() ? "America/Chicago" : s.timezone);
        } catch (RuntimeException e) {
            return ZoneId.of("America/Chicago");
        }
    }

    /** Preview the next fire times, with the holiday policy applied, for the wizard. */
    public List<Map<String, Object>> nextRuns(ProductionConfig.Schedule s, int count) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            CronExpression cron = CronExpression.parse(cronFor(s));
            ZonedDateTime t = ZonedDateTime.now(zone(s));
            int guard = 0;
            while (out.size() < count && guard++ < 400) {
                t = cron.next(t);
                if (t == null) break;
                Map<String, Object> m = new LinkedHashMap<>();
                LocalDate d = t.toLocalDate();
                boolean holiday = calendar.isHoliday(s.calendar, d);
                m.put("scheduled", t.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString());
                if (holiday) {
                    String hn = calendar.holidayName(s.calendar, d);
                    switch (s.holidayPolicy == null ? "NEXT_BUSINESS_DAY" : s.holidayPolicy) {
                        case "SKIP" -> { m.put("effective", null); m.put("note", "skipped, " + hn); }
                        case "RUN_ANYWAY" -> { m.put("effective", m.get("scheduled")); m.put("note", "runs on " + hn); }
                        default -> {
                            LocalDate nb = calendar.nextBusinessDay(s.calendar, d);
                            m.put("effective", nb.atTime(t.toLocalTime()).truncatedTo(ChronoUnit.MINUTES).toString());
                            m.put("note", hn + ", moved to " + nb.getDayOfWeek().toString().charAt(0) + nb.getDayOfWeek().toString().substring(1, 3).toLowerCase() + " " + nb);
                        }
                    }
                } else {
                    m.put("effective", m.get("scheduled"));
                }
                out.add(m);
            }
        } catch (RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "Invalid schedule: " + e.getMessage());
            out.add(m);
        }
        return out;
    }

    public void register(Definition def, Definition.Version v) {
        ProductionConfig.Schedule s = v.productionConfig.schedule;
        ScheduleState st = new ScheduleState();
        st.definitionId = def.id;
        st.versionNo = v.versionNo;
        ZonedDateTime next = CronExpression.parse(cronFor(s)).next(ZonedDateTime.now(zone(s)));
        st.nextFireAt = next == null ? null : next.toLocalDateTime().toString();
        stores.schedules().save(st);
    }

    public void unregister(String definitionId) {
        stores.schedules().delete(definitionId);
    }

    public Optional<ScheduleState> state(String definitionId) {
        return stores.schedules().find(definitionId);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void tick() {
        if (!settings.schedulerEnabled) return;
        LocalDate today = LocalDate.now();
        for (Definition def : stores.definitions().all()) {
            Optional<Definition.Version> prod = def.production();
            if (prod.isEmpty() || prod.get().productionConfig == null) continue;
            Definition.Version v = prod.get();
            if (Boolean.TRUE.equals(v.schedulePaused) || !v.productionConfig.schedule.enabled) continue;
            ProductionConfig.Schedule s = v.productionConfig.schedule;
            ScheduleState st = stores.schedules().find(def.id).orElse(null);
            if (st == null || st.versionNo != v.versionNo) {
                register(def, v);
                continue;
            }
            if (st.nextFireAt == null) continue;
            ZoneId zone = zone(s);
            LocalDateTime nowLocal = LocalDateTime.now(zone);
            LocalDateTime fireAt = LocalDateTime.parse(st.nextFireAt);
            if (nowLocal.isBefore(fireAt)) {
                checkSla(def, v, st, nowLocal);
                continue;
            }
            boolean misfired = ChronoUnit.MINUTES.between(fireAt, nowLocal) > 30;
            LocalDate businessDate = fireAt.toLocalDate();
            boolean run = true;
            if (calendar.isHoliday(s.calendar, businessDate)) {
                switch (s.holidayPolicy == null ? "NEXT_BUSINESS_DAY" : s.holidayPolicy) {
                    case "SKIP" -> run = false;
                    case "NEXT_BUSINESS_DAY" -> {
                        LocalDate nb = calendar.nextBusinessDay(s.calendar, businessDate);
                        if (nowLocal.toLocalDate().isBefore(nb)) {
                            st.nextFireAt = nb.atTime(fireAt.toLocalTime()).toString();
                            st.note = "Moved from " + businessDate + " (" + calendar.holidayName(s.calendar, businessDate) + ")";
                            stores.schedules().save(st);
                            continue;
                        }
                    }
                    default -> { }
                }
            }
            if (misfired && "SKIP".equals(s.misfire)) run = false;
            if (run) {
                RunService.ProductionOptions po = new RunService.ProductionOptions();
                po.trigger = "SCHEDULE";
                po.businessDate = businessDate;
                runs.production(def, v, Actor.SYSTEM, po);
                st.lastFiredAt = nowLocal.truncatedTo(ChronoUnit.SECONDS).toString();
            } else {
                st.note = "Skipped " + businessDate + (misfired ? " (missed while the app was down)" : " (holiday)");
            }
            ZonedDateTime next = CronExpression.parse(cronFor(s)).next(ZonedDateTime.now(zone));
            st.nextFireAt = next == null ? null : next.toLocalDateTime().toString();
            stores.schedules().save(st);
        }
        contractWatch(today);
    }

    private void checkSla(Definition def, Definition.Version v, ScheduleState st, LocalDateTime nowLocal) {
        ProductionConfig.Sla sla = v.productionConfig.sla;
        if (sla == null || sla.deliverBy == null || sla.deliverBy.isBlank()) return;
        String today = nowLocal.toLocalDate().toString();
        if (today.equals(st.lastSlaCheckDate)) return;
        if (st.lastFiredAt == null || !st.lastFiredAt.startsWith(today)) return;
        LocalTime by = LocalTime.parse(sla.deliverBy);
        if (nowLocal.toLocalTime().isBefore(by)) return;
        boolean delivered = runs.forDefinition(def.id).stream().anyMatch(r -> "PRODUCTION".equals(r.mode) && today.equals(r.businessDate) && (r.status.equals("DELIVERED") || r.status.equals("TRANSFERRED")));
        st.lastSlaCheckDate = today;
        stores.schedules().save(st);
        if (!delivered) {
            notifications.notify("SLA_MISSED", "CRITICAL", def.name + " missed its delivery window", "No file was delivered to " + def.vendorCode + " by " + sla.deliverBy + " today.", def.id, null, v.productionConfig.notifications.onSlaMissed.isEmpty() ? v.productionConfig.notifications.onFailure : v.productionConfig.notifications.onSlaMissed);
        }
    }

    private void contractWatch(LocalDate today) {
        for (Partner p : partners.all()) {
            long days = partners.daysToContractEnd(p, today);
            if (days <= 30 && days >= 0 && !notifications.exists("CONTRACT_ENDING", "partner:" + p.code, today.minusDays(7).toString())) {
                notifications.notify("CONTRACT_ENDING", "WARN", p.name + " contract ends in " + days + " days", "Feeds to " + p.code + " stop automatically after " + p.contractEnd + ". Renew the contract or retire the feeds.", "partner:" + p.code, null, List.of());
            }
        }
    }
}
