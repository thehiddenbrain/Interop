package com.thehiddenbrain.interop.cms1500.support;

import com.thehiddenbrain.interop.cms1500.attachments.AttachmentLocator;
import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.config.RuntimeSettings;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidator;
import com.thehiddenbrain.interop.cms1500.pdf.PdfBundler;
import com.thehiddenbrain.interop.cms1500.pdf.TemplateSource;
import com.thehiddenbrain.interop.cms1500.service.ClaimBundleService;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;

/** Builds settings and a fully wired service for unit tests, without Spring. */
public final class TestSettings {

    public static final String TEMPLATE = "classpath:forms/cms1500-02-12.pdf";
    public static final List<String> EXTENSIONS = List.of("pdf", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif");

    private TestSettings() {
    }

    public static Cms1500Properties properties(Path attachments, Path output, Path settingsFile,
                                               Cms1500Properties.UnsupportedPolicy unsupported,
                                               Cms1500Properties.WhenNonePolicy whenNone, boolean overwrite) {
        return new Cms1500Properties(TEMPLATE,
                new Cms1500Properties.Attachments(attachments.toString(), EXTENSIONS, unsupported, whenNone),
                new Cms1500Properties.Output(output.toString(), overwrite),
                new Cms1500Properties.Form(true, true, ""),
                settingsFile.toString(),
                new Cms1500Properties.Ui(true));
    }

    public static Cms1500Properties properties(Path attachments, Path output, Path settingsFile) {
        return properties(attachments, output, settingsFile, Cms1500Properties.UnsupportedPolicy.FAIL,
                Cms1500Properties.WhenNonePolicy.WARN, true);
    }

    public static RuntimeSettings runtime(Cms1500Properties defaults) {
        return new RuntimeSettings(defaults, new TemplateSource(new DefaultResourceLoader()));
    }

    public static ClaimBundleService service(Cms1500Properties defaults) {
        TemplateSource templates = new TemplateSource(new DefaultResourceLoader());
        RuntimeSettings settings = new RuntimeSettings(defaults, templates);
        return new ClaimBundleService(settings, new ClaimValidator(), templates, new AttachmentLocator(), new PdfBundler());
    }
}
