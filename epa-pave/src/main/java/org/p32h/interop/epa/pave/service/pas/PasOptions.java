package org.p32h.interop.epa.pave.service.pas;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * The code lists of the Service card, the attachment kinds and limits, and the version of the guide
 * Submit follows, as {@code GET /api/pas/options} serves them.
 *
 * <p>They live on the server so the page draws no list of its own: the codes the page offers are
 * the codes the request builder and the validation know, from one place. Each list is the short set
 * a tester of prior authorization reaches for, not the whole X12 code list. A code outside it can
 * still be typed where the card takes text (place of service, service type), because the guide
 * binds those elements to code lists far longer than a select can hold.
 *
 * <p>The same on mock and on live: these are code lists, not samples, and nothing here is filled
 * in for the tester.
 *
 * @param version            the guide Submit follows for the active environment, as the screen names it
 * @param certificationTypes X12 1322 certification type codes
 * @param requestTypes       X12 1525 request category codes
 * @param serviceTypes       X12 1365 service type codes
 * @param levelsOfService    X12 1338 level of service codes
 * @param placesOfService    CMS place of service codes
 * @param units              X12 673 unit codes for a quantity
 * @param attachments        the kinds of file Submit sends and how large they may be
 */
@JsonPropertyOrder({"version", "certificationTypes", "requestTypes", "serviceTypes", "levelsOfService", "placesOfService", "units",
        "attachments"})
public record PasOptions(String version, List<Option> certificationTypes, List<Option> requestTypes, List<Option> serviceTypes,
                         List<Option> levelsOfService, List<Option> placesOfService, List<Option> units, Attachments attachments) {

    /** At most 5 MB a file. A larger document is rare in a prior authorization and slows every screen that holds the run. */
    public static final long PER_FILE_BYTES = 5L * 1024 * 1024;

    /** At most 10 MB for all the files of one request. */
    public static final long TOTAL_BYTES = 10L * 1024 * 1024;

    /** One code and the words the screen shows for it. */
    @JsonPropertyOrder({"code", "label"})
    public record Option(String code, String label) {
    }

    /** One kind of file, by the extension of its name. */
    @JsonPropertyOrder({"extension", "contentType", "label"})
    public record Kind(String extension, String contentType, String label) {
    }

    /**
     * @param kinds        the files Submit sends, one entry per extension, so the page can map a
     *                     file name to its media type without a table of its own
     * @param perFileBytes the most one file may hold
     * @param totalBytes   the most all the files of one request may hold together
     */
    @JsonPropertyOrder({"kinds", "perFileBytes", "totalBytes"})
    public record Attachments(List<Kind> kinds, long perFileBytes, long totalBytes) {
    }

    /** The lists under the default guide, 2.0.1; the lists are the same under either guide. */
    public static final PasOptions CURRENT = new PasOptions(PasGuide.V201.fullName(),
            List.of(new Option("I", "Initial"), new Option("S", "Revised"), new Option("4", "Extension"), new Option("R", "Renewal"),
                    new Option("3", "Cancel"), new Option("1", "Appeal, immediate"), new Option("2", "Appeal, standard")),
            List.of(new Option("HS", "Health services review"), new Option("AR", "Admission review"),
                    new Option("SC", "Specialty care review"), new Option("IN", "Initial medical services reservation")),
            List.of(new Option("1", "Medical care"), new Option("2", "Surgical"), new Option("3", "Consultation"),
                    new Option("42", "Home health care")),
            List.of(new Option("E", "Elective"), new Option("U", "Urgent"), new Option("03", "Emergency")),
            List.of(new Option("11", "Office"), new Option("12", "Home"), new Option("19", "Off campus outpatient hospital"),
                    new Option("21", "Inpatient hospital"), new Option("22", "On campus outpatient hospital"),
                    new Option("23", "Emergency room, hospital"), new Option("24", "Ambulatory surgical center"),
                    new Option("81", "Independent laboratory")),
            List.of(new Option("FL", "Units"), new Option("DY", "Days"), new Option("VS", "Visits"), new Option("HS", "Hours"),
                    new Option("MN", "Months")),
            new Attachments(List.of(
                    new Kind("pdf", "application/pdf", "PDF"),
                    new Kind("png", "image/png", "PNG image"),
                    new Kind("jpg", "image/jpeg", "JPEG image"),
                    new Kind("jpeg", "image/jpeg", "JPEG image"),
                    new Kind("tif", "image/tiff", "TIFF image"),
                    new Kind("tiff", "image/tiff", "TIFF image"),
                    new Kind("gif", "image/gif", "GIF image"),
                    new Kind("txt", "text/plain", "Text"),
                    new Kind("doc", "application/msword", "Word document"),
                    new Kind("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document")),
                    PER_FILE_BYTES, TOTAL_BYTES));

    public PasOptions {
        certificationTypes = List.copyOf(certificationTypes);
        requestTypes = List.copyOf(requestTypes);
        serviceTypes = List.copyOf(serviceTypes);
        levelsOfService = List.copyOf(levelsOfService);
        placesOfService = List.copyOf(placesOfService);
        units = List.copyOf(units);
    }

    /** These lists named for the guide in force, such as "Da Vinci PAS 2.2.1" for an environment that chose it. */
    public static PasOptions forGuide(PasGuide guide) {
        PasGuide named = guide == null ? PasGuide.V201 : guide;
        return new PasOptions(named.fullName(), CURRENT.certificationTypes(), CURRENT.requestTypes(), CURRENT.serviceTypes(),
                CURRENT.levelsOfService(), CURRENT.placesOfService(), CURRENT.units(), CURRENT.attachments());
    }

    /** True when Submit sends a file of this media type. Compared without case, as media types are. */
    public static boolean sendsContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String wanted = contentType.strip();
        return CURRENT.attachments().kinds().stream().anyMatch(kind -> kind.contentType().equalsIgnoreCase(wanted));
    }

    /** The words for a code of one of these lists, or null when the list does not hold it. */
    public static String label(List<Option> list, String code) {
        if (code == null) {
            return null;
        }
        String wanted = code.strip();
        return list.stream().filter(option -> option.code().equals(wanted)).map(Option::label).findFirst().orElse(null);
    }
}
