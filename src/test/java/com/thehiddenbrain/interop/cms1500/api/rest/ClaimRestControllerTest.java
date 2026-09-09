package com.thehiddenbrain.interop.cms1500.api.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.support.ClaimFixtures;
import com.thehiddenbrain.interop.cms1500.support.TempDirs;
import com.thehiddenbrain.interop.cms1500.support.TestFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ClaimRestControllerTest {

    private static final TempDirs DIRS = TempDirs.create("cms1500-rest");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        DIRS.register(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void attachments() throws Exception {
        TestFiles.pdf(DIRS.attachments.resolve("CLM-R1_1.pdf"), 2, "EOB");
        TestFiles.image(DIRS.attachments.resolve("CLM-R1_2.jpg"), "jpg", 640, 480);
        TestFiles.text(DIRS.attachments.resolve("CLM-R2_1.docx"), "word");
        TestFiles.pdf(DIRS.attachments.resolve("CLM-2026-000123_1.pdf"), 1, "Referral");
    }

    @Test
    void generatesBundleAndReportsWhereItIs() throws Exception {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-R1");
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(claim)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.claimNumber").value("CLM-R1"))
                .andExpect(jsonPath("$.fileName").value("CLM-R1.pdf"))
                .andExpect(jsonPath("$.bundlePath").value(DIRS.output.resolve("CLM-R1.pdf").toString()))
                .andExpect(jsonPath("$.formPages").value(1))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.attachmentCount").value(2))
                .andExpect(jsonPath("$.attachments", hasSize(2)))
                .andExpect(jsonPath("$.attachments[0].fileName").value("CLM-R1_1.pdf"))
                .andExpect(jsonPath("$.attachments[0].type").value("PDF"))
                .andExpect(jsonPath("$.attachments[1].type").value("IMAGE"))
                .andExpect(jsonPath("$.replacedExisting").value(false))
                .andExpect(jsonPath("$.generatedAt").exists());
        assertThat(DIRS.output.resolve("CLM-R1.pdf")).exists();
    }

    @Test
    void downloadsTheGeneratedBundle() throws Exception {
        Cms1500Claim claim = ClaimFixtures.minimalClaim("CLM-DL");
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(claim))).andExpect(status().isOk());
        Path bundle = DIRS.output.resolve("CLM-DL.pdf");

        MvcResult result = mvc.perform(get("/api/v1/claims/CLM-DL/bundle"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("CLM-DL.pdf")))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(Files.readAllBytes(bundle));
    }

    @Test
    void unknownBundleIs404() throws Exception {
        mvc.perform(get("/api/v1/claims/NOPE-1/bundle"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"))
                .andExpect(jsonPath("$.claimNumber").value("NOPE-1"));
    }

    @Test
    void validationErrorsAre400WithEveryDetail() throws Exception {
        Cms1500Claim claim = ClaimFixtures.fullClaim("CLM-BAD");
        claim.getBillingProvider().setNpi("123");
        claim.getPatient().setSex(null);
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(claim)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.claimNumber").value("CLM-BAD"))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath("$.details[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("billingProvider.npi", "patient.sex")));
        assertThat(DIRS.output.resolve("CLM-BAD.pdf")).doesNotExist();
    }

    @Test
    void malformedJsonIs400WithThePathOfTheProblem() throws Exception {
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claimNumber\":\"X\",\"patient\":{\"foo\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("patient.foo"))
                .andExpect(jsonPath("$.details[0].message").value(containsString("unknown property 'foo'")));

        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claimNumber\":\"X\",\"insuranceType\":\"PPO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("insuranceType"))
                .andExpect(jsonPath("$.details[0].message").value(containsString("PPO")));

        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claimNumber\":\"X\",\"patient\":{\"dateOfBirth\":\"1980-13-40\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("patient.dateOfBirth"));

        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedAttachmentIs422() throws Exception {
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ClaimFixtures.fullClaim("CLM-R2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_ATTACHMENT"))
                .andExpect(jsonPath("$.message").value(containsString("CLM-R2_1.docx")));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void sampleRequestFromTheRepositoryIsAccepted() throws Exception {
        String sample = Files.readString(Path.of("samples/claim-full.json"));
        mvc.perform(post("/api/v1/claims/cms1500").contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimNumber").value("CLM-2026-000123"))
                .andExpect(jsonPath("$.attachmentCount").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }
}
