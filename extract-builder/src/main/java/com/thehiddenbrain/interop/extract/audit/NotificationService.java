package com.thehiddenbrain.interop.extract.audit;

import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.store.StateStores;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** In the demo, notifications land in the bell menu instead of email. The recipients are recorded as they would be sent. */
@Service
public class NotificationService {

    private final StateStores stores;

    public NotificationService(StateStores stores) {
        this.stores = stores;
    }

    public Notification notify(String event, String severity, String title, String message, String definitionId, String runId, List<String> recipients) {
        Notification n = new Notification();
        n.id = Ids.shortId("n");
        n.at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        n.event = event;
        n.severity = severity;
        n.title = title;
        n.message = message;
        n.definitionId = definitionId;
        n.runId = runId;
        n.recipients = recipients == null ? List.of() : recipients;
        stores.notifications().save(n);
        return n;
    }

    public List<Notification> recent(int limit) {
        List<Notification> all = new ArrayList<>(stores.notifications().all());
        all.sort(Comparator.comparing((Notification n) -> n.at).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    public long unread() {
        return stores.notifications().all().stream().filter(n -> !n.read).count();
    }

    public void markAllRead() {
        List<Notification> all = stores.notifications().all();
        for (Notification n : all) n.read = true;
        stores.notifications().replaceAll(all);
    }

    public boolean exists(String event, String definitionId, String sinceIsoDate) {
        return stores.notifications().all().stream().anyMatch(n -> n.event.equals(event) && definitionId.equals(n.definitionId) && n.at.compareTo(sinceIsoDate) >= 0);
    }
}
