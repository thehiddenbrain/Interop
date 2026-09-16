package com.thehiddenbrain.interop.patientaccess.conformance;

import tools.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FullValidatorTest {

    @TempDir
    Path dir;

    private FullValidator validator() {
        WorkbenchProperties p = new WorkbenchProperties(dir.toString(), "", "", "vendors.yaml", new WorkbenchProperties.Ui(true), new WorkbenchProperties.Demo(false),
                new WorkbenchProperties.Security(new WorkbenchProperties.Security.Basic(false, "w", "")),
                new WorkbenchProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(5), "t", 0), new WorkbenchProperties.History(10, 100, false),
                new WorkbenchProperties.Search(10, 2), new WorkbenchProperties.Conformance(2, 1, 100, 200),
                new WorkbenchProperties.Validation(dir.resolve("packages").toString()));
        return new FullValidator(p);
    }

    @Test
    void baseValidationWorksWithoutPackages() throws Exception {
        FullValidator v = validator();
        assertThat(v.status().ready()).isFalse();
        var ok = v.validate(new ObjectMapper().readTree("{\"resourceType\":\"Patient\",\"id\":\"p1\",\"gender\":\"female\",\"birthDate\":\"1970-01-01\"}"), null);
        assertThat(ok.errors()).isZero();
        var bad = v.validate(new ObjectMapper().readTree("{\"resourceType\":\"Patient\",\"id\":\"p2\",\"gender\":\"not-a-gender\"}"), null);
        assertThat(bad.errors()).isGreaterThan(0);
        assertThat(bad.issues()).anyMatch(i -> i.message().toLowerCase().contains("gender") || i.location().contains("gender"));
        assertThat(v.status().ready()).isTrue();
    }

    @Test
    void unknownProfileIsReportedNotThrown() throws Exception {
        var r = validator().validate(new ObjectMapper().readTree("{\"resourceType\":\"Patient\",\"id\":\"p1\"}"),
                "http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient");
        assertThat(r.errors()).isGreaterThan(0);
        assertThat(r.issues()).anyMatch(i -> i.message().contains("C4BB-Patient"));
    }
}
