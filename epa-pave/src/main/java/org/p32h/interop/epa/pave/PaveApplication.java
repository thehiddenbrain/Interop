package org.p32h.interop.epa.pave;

import org.p32h.interop.epa.pave.service.error.ClassesOnDisk;
import org.p32h.interop.epa.pave.service.error.UncaughtFailures;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaveApplication {

    public static void main(String[] args) {
        // Before Spring starts anything: the jar and a start from the IDE both enter here, and a
        // thread started on the way up must already find the net under it.
        UncaughtFailures.install();
        // Every class of the tool is loaded before the IDE has a chance to rebuild any of them, so a
        // running copy keeps one consistent set, and a rebuild under it can be told (R-24).
        ClassesOnDisk.atStart();
        SpringApplication.run(PaveApplication.class, args);
    }
}
