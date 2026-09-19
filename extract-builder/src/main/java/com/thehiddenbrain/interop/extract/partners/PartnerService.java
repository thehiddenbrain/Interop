package com.thehiddenbrain.interop.extract.partners;

import com.thehiddenbrain.interop.extract.audit.AuditService;
import com.thehiddenbrain.interop.extract.config.ApiErrors;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.store.StateStores;
import com.thehiddenbrain.interop.extract.users.Actor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

@Service
public class PartnerService {

    private final StateStores stores;
    private final AppProperties props;
    private final AuditService audit;

    public PartnerService(StateStores stores, AppProperties props, AuditService audit) {
        this.stores = stores;
        this.props = props;
        this.audit = audit;
    }

    public List<Partner> all() {
        List<Partner> all = new ArrayList<>(stores.partners().all());
        all.sort(Comparator.comparing(p -> p.name == null ? p.code : p.name));
        return all;
    }

    public Partner get(String code) {
        return stores.partners().get(code, "Partner");
    }

    public Optional<Partner> find(String code) {
        return stores.partners().find(code);
    }

    public Partner save(Partner p, Actor actor) {
        actor.require("change MFT partners", "ADMIN");
        if (p.code == null || p.code.isBlank()) throw new ApiErrors.BadRequest("A partner needs a vendor code");
        p.code = p.code.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "");
        if (p.test.folder == null || p.test.folder.isBlank()) p.test.folder = p.code + "/test";
        if (p.prod.folder == null || p.prod.folder.isBlank()) p.prod.folder = p.code + "/prod";
        if (p.apiEnabled && (p.apiKey == null || p.apiKey.isBlank())) p.apiKey = newApiKey();
        stores.partners().save(p);
        audit.record(actor, "PARTNER_SAVED", "partner", p.code, null, p.code, false, Map.of("name", p.name == null ? "" : p.name));
        return p;
    }

    public String rotateApiKey(String code, Actor actor) {
        actor.require("rotate API keys", "ADMIN");
        Partner p = get(code);
        p.apiKey = newApiKey();
        p.apiEnabled = true;
        stores.partners().save(p);
        audit.record(actor, "API_KEY_ROTATED", "partner", code, null, code, false, Map.of());
        return p.apiKey;
    }

    public static String newApiKey() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return "veb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The outbound folder Axway polls for a partner route: data/mft/{folder}/out. */
    public Path outFolder(Partner p, String route) {
        Partner.Route r = "TEST".equalsIgnoreCase(route) ? p.test : p.prod;
        String folder = r.folder == null || r.folder.isBlank() ? p.code + "/" + route.toLowerCase() : r.folder;
        return props.mftDir().resolve(folder).resolve("out");
    }

    public Path sentFolder(Partner p, String route) {
        return outFolder(p, route).resolveSibling("sent");
    }

    /** Dry-run check used by the wizard: create the folder and write and delete a probe file. */
    public Map<String, Object> testRoute(String code, String route) {
        Partner p = get(code);
        Path out = outFolder(p, route);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("folder", out.toString());
        result.put("mode", ("TEST".equalsIgnoreCase(route) ? p.test : p.prod).mode);
        try {
            Files.createDirectories(out);
            Path probe = out.resolve(".probe-" + System.nanoTime());
            Files.writeString(probe, "probe");
            Files.delete(probe);
            result.put("ok", true);
            result.put("message", "Folder is writable. Axway will pick up files placed here.");
        } catch (IOException e) {
            result.put("ok", false);
            result.put("message", "Cannot write to " + out + ": " + e.getMessage());
        }
        return result;
    }

    public boolean contractActive(Partner p, LocalDate on) {
        if (p.contractEnd != null && !p.contractEnd.isBlank() && LocalDate.parse(p.contractEnd).isBefore(on)) return false;
        return !"DISABLED".equals(p.status);
    }

    public long daysToContractEnd(Partner p, LocalDate on) {
        if (p.contractEnd == null || p.contractEnd.isBlank()) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(on, LocalDate.parse(p.contractEnd));
    }
}
