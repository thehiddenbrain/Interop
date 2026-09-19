package com.thehiddenbrain.interop.extract.audit;

import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Append-only record of who did what. Every PHI touch (preview, sample, download, API read) carries the phi flag. */
@Service
public class AuditService {

    private final StateStores stores;

    public AuditService(StateStores stores) {
        this.stores = stores;
    }

    public AuditEvent record(Actor actor, String action, String targetType, String targetId, String definitionId, String vendorCode, boolean phi, Map<String, Object> details) {
        AuditEvent e = new AuditEvent();
        e.id = Ids.shortId("a");
        e.at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        e.actor = actor.id();
        e.actorName = actor.name();
        e.role = actor.role();
        e.action = action;
        e.targetType = targetType;
        e.targetId = targetId;
        e.definitionId = definitionId;
        e.vendorCode = vendorCode;
        e.phi = phi;
        e.details = details == null ? Map.of() : details;
        stores.audit().save(e);
        return e;
    }

    public AuditEvent record(Actor actor, String action, String definitionId, String vendorCode, Map<String, Object> details) {
        return record(actor, action, "definition", definitionId, definitionId, vendorCode, false, details);
    }

    public List<AuditEvent> recent(int limit, String definitionId, String vendorCode, String actor, Boolean phi) {
        List<AuditEvent> all = new ArrayList<>(stores.audit().all());
        all.sort(Comparator.comparing((AuditEvent a) -> a.at).reversed());
        return all.stream()
                .filter(a -> definitionId == null || definitionId.equals(a.definitionId))
                .filter(a -> vendorCode == null || vendorCode.equals(a.vendorCode))
                .filter(a -> actor == null || actor.equals(a.actor))
                .filter(a -> phi == null || phi == a.phi)
                .limit(limit)
                .toList();
    }

    public List<AuditEvent> all() {
        return stores.audit().all();
    }
}
