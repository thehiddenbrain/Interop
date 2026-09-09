package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads the configured template once at startup and refuses to start if a field the filler needs is missing. */
@Configuration
public class TemplateSource {

    private static final Logger log = LoggerFactory.getLogger(TemplateSource.class);

    @Bean
    public Cms1500FormFiller cms1500FormFiller(Cms1500Properties properties, ResourceLoader resourceLoader) {
        byte[] template = load(properties.template(), resourceLoader);
        verify(template, properties.template());
        Cms1500Properties.Form form = properties.form();
        return new Cms1500FormFiller(template,
                new FormText(form.uppercase(), form.stripDiagnosisPeriods()), form.continuationMarker());
    }

    static byte[] load(String location, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location.contains(":") ? location : "file:" + location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.TEMPLATE_ERROR, null,
                    "cannot read CMS-1500 template " + location + ": " + e.getMessage(), e);
        }
    }

    static void verify(byte[] template, String location) {
        try (PDDocument doc = Loader.loadPDF(template)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                throw new ClaimException(ErrorCode.TEMPLATE_ERROR, null,
                        "CMS-1500 template " + location + " has no AcroForm fields");
            }
            List<String> missing = new ArrayList<>();
            for (String name : Cms1500Fields.allWritableFields()) {
                if (form.getField(name) == null) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                throw new ClaimException(ErrorCode.TEMPLATE_ERROR, null,
                        "CMS-1500 template " + location + " lacks " + missing.size() + " field(s): " + missing);
            }
            log.info("CMS-1500 template {} verified: {} pages, {} fields", location, doc.getNumberOfPages(),
                    form.getFields().size());
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.TEMPLATE_ERROR, null,
                    "CMS-1500 template " + location + " is not a readable PDF: " + e.getMessage(), e);
        }
    }
}
