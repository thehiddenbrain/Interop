package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;

/**
 * F5 of the member data audit: the configuration store keeps uploads whole, and the upload says
 * so when a file can hold member data, naming the variable or the request and never a value.
 */
class MemberDataUploadTest {

    @TempDir
    Path directory;

    @Test
    void namesThatReadAsMemberDataAreCaughtAndOrdinaryNamesAreNot() {
        for (String caught : List.of("memberId", "member_id", "MemberID", "patientLastName", "patientBirthDate", "subscriberId", "dob",
                "dateOfBirth", "firstName", "last-name", "PATIENT_IDENTIFIER", "beneficiaryNumber", "birthDate",
                // N9 of the member data check: the names its upload was not warned for.
                "mbrId", "ssn", "MBI", "mrn", "medicaidId", "medicareNumber", "zip", "address", "streetAddress", "phone",
                "memberPhone", "patient_zip_code", "socialSecurityNumber", "medicalRecordNumber", "telephone", "mbrNum")) {
            assertThat(MemberDataNames.readsAsMemberData(caught)).as(caught).isTrue();
        }
        for (String ordinary : List.of("baseUrl", "crdUrl", "crdOrderSignServiceId", "memberIdSystem", "memberIdType", "subscriptionId",
                "callbackUrl", "clientId", "clientSecret", "tenant", "claimId", "npi", "providerNpi", "lineOfBusiness", "apiKey", "patientUrl",
                "serverAddress", "ipAddress", "callbackAddress", "hostAddress", "medicareUrl", "medicaidPlanType", "telemetry", "zipkinUrl")) {
            assertThat(MemberDataNames.readsAsMemberData(ordinary)).as(ordinary).isFalse();
        }
    }

    @Test
    void anEnvironmentWithMemberDataVariablesIsAcceptedWithAWarningThatNamesThemAndNoValue() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        byte[] file = ("{\"name\": \"zz-sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"https://sbx.example.invalid/fhir\"}, "
                + "{\"key\": \"memberId\", \"value\": \"S123456789\"}, {\"key\": \"patientLastName\", \"value\": \"Zzlastname-Placeholder\"}, "
                + "{\"key\": \"patientBirthDate\", \"value\": \"1961-07-23\"}, {\"key\": \"memberIdSystem\", \"value\": \"urn:placeholder\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("zz.json", file, EnvironmentKind.SANDBOX, false);
        String warning = upload.warnings().stream().filter(each -> each.contains("member data")).findFirst().orElseThrow();
        assertThat(warning).startsWith("The variables \"memberId\", \"patientLastName\", \"patientBirthDate\" read as member data: an "
                + "identifier, a name, a birth date, an address or a telephone number. The environment is kept on disk exactly as it was uploaded, values included, in "
                + "the file ").endsWith(". An environment or a collection that holds real member data must not be uploaded: use "
                + "placeholders such as S123456789 in its place.").doesNotContain("Zzlastname").doesNotContain("1961")
                .doesNotContain("memberIdSystem");
    }

    @Test
    void aCollectionWithSavedExamplesSaysTheyCanHoldMemberData() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        String collection = "{\"info\": {\"name\": \"Examples\", \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, "
                + "\"item\": [{\"name\": \"inquiry with example\", \"request\": {\"method\": \"POST\", \"url\": {\"raw\": \"{{baseUrl}}/Claim/$inquire\"}}, "
                + "\"response\": [{\"name\": \"ok\", \"code\": 200, \"body\": \"{}\"}]}, "
                + "{\"name\": \"plain\", \"request\": {\"method\": \"GET\", \"url\": {\"raw\": \"{{baseUrl}}/metadata\"}}}]}";
        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("e.json", collection.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.warnings()).anySatisfy(warning -> assertThat(warning).startsWith("The request \"inquiry with example\" carries a "
                + "saved example response, which can hold the member data of whoever the example was saved for. The collection is kept "
                + "on disk exactly as it was uploaded"));
    }
}
