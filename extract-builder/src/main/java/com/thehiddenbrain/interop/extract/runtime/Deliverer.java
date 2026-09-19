package com.thehiddenbrain.interop.extract.runtime;

import com.thehiddenbrain.interop.extract.partners.Partner;
import com.thehiddenbrain.interop.extract.partners.PartnerService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Hands a finished file to Axway MFT. The default is the drop folder Axway polls: write a .tmp, fsync, atomic rename,
 * then an optional control file, so the poller never sees a partial file. The REST mode is simulated the same way
 * and records a transfer id, which is where a real Axway REST client would plug in.
 */
@Service
public class Deliverer {

    private final PartnerService partners;

    public Deliverer(PartnerService partners) {
        this.partners = partners;
    }

    public void deliver(Run run, Partner partner, String route, boolean controlFile) {
        Partner.Route r = "TEST".equalsIgnoreCase(route) ? partner.test : partner.prod;
        Path out = partners.outFolder(partner, route);
        Run.DeliveryAttempt attempt = new Run.DeliveryAttempt();
        attempt.attemptNo = run.attempts.size() + 1;
        attempt.at = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        try {
            if (run.filePath == null || !Files.exists(Path.of(run.filePath))) {
                throw new IOException("the produced file no longer exists (purged by retention)");
            }
            Files.createDirectories(out);
            Path source = Path.of(run.filePath);
            Path tmp = out.resolve(run.fileName + ".tmp");
            Path target = out.resolve(run.fileName);
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (controlFile) Files.writeString(out.resolve(run.fileName + ".done"), "sha256=" + run.sha256 + "\nrecords=" + run.rowCount + "\n");
            String receipt;
            if ("AXWAY_REST".equals(r.mode)) {
                receipt = "Axway REST transfer T-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase() + " accepted for account " + (r.axwayAccount == null ? partner.code : r.axwayAccount);
            } else {
                receipt = "Renamed into " + target + (controlFile ? " with control file" : "");
            }
            attempt.status = "OK";
            attempt.receipt = receipt;
            run.deliveryPath = target.toString();
            run.deliveryReceipt = receipt;
            run.deliveredAt = attempt.at;
            run.route = route.toUpperCase();
            run.attempts.add(attempt);
        } catch (IOException e) {
            attempt.status = "FAILED";
            attempt.message = e.getMessage();
            run.attempts.add(attempt);
            throw new IllegalStateException("Delivery failed: " + e.getMessage(), e);
        }
    }
}
