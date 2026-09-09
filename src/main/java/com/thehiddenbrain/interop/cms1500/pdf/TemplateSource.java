package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the form filler built from the current template and form options. A template is read
 * and verified (every field the filler writes must exist) before it replaces the previous one,
 * so a bad template path never takes the service down.
 */
@Component
public class TemplateSource {

    private static final Logger log = LoggerFactory.getLogger(TemplateSource.class);

    private final ResourceLoader resourceLoader;
    private final AtomicReference<Cms1500FormFiller> filler = new AtomicReference<>();
    private String loadedTemplate;
    private byte[] loadedBytes;
    private Cms1500Properties.Form loadedForm;

    public TemplateSource(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** The filler for the current template and form options. */
    public Cms1500FormFiller current() {
        Cms1500FormFiller current = filler.get();
        if (current == null) {
            throw new IllegalStateException("CMS-1500 template has not been loaded");
        }
        return current;
    }

    public String loadedTemplate() {
        return loadedTemplate;
    }

    /** Loads and verifies the template if it (or the form options) changed; otherwise a no-op. */
    public synchronized void load(String template, Cms1500Properties.Form form) {
        boolean sameTemplate = template.equals(loadedTemplate) && loadedBytes != null;
        if (sameTemplate && form.equals(loadedForm)) {
            return;
        }
        byte[] bytes = sameTemplate ? loadedBytes : load(template, resourceLoader);
        if (!sameTemplate) {
            verify(bytes, template);
        }
        filler.set(new Cms1500FormFiller(bytes, new FormText(form.uppercase(), form.stripDiagnosisPeriods()),
                form.continuationMarker()));
        loadedTemplate = template;
        loadedBytes = bytes;
        loadedForm = form;
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
