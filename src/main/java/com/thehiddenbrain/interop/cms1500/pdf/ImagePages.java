package com.thehiddenbrain.interop.cms1500.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Renders image attachments as letter-size pages, one page per image frame (multi-page TIFF supported). */
public final class ImagePages {

    private static final float MARGIN = 36f; // half an inch

    private ImagePages() {
    }

    /** Appends the image(s) in {@code file} to {@code doc} and returns the number of pages added. */
    public static int append(PDDocument doc, Path file, String extension) throws IOException {
        return switch (extension) {
            case "jpg", "jpeg" -> {
                try (InputStream in = Files.newInputStream(file)) {
                    addPage(doc, JPEGFactory.createFromStream(doc, in));
                }
                yield 1;
            }
            case "tif", "tiff" -> appendFrames(doc, file, "tiff");
            case "png", "bmp", "gif" -> {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) {
                    throw new IOException("not a readable " + extension + " image");
                }
                addPage(doc, LosslessFactory.createFromImage(doc, image));
                yield 1;
            }
            default -> throw new IOException("unsupported image type ." + extension);
        };
    }

    private static int appendFrames(PDDocument doc, Path file, String format) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new IOException("no " + format + " image reader available");
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("cannot open " + file.getFileName());
            }
            reader.setInput(in, false, true);
            int frames = reader.getNumImages(true);
            if (frames <= 0) {
                throw new IOException("no image frames in " + file.getFileName());
            }
            for (int i = 0; i < frames; i++) {
                addPage(doc, LosslessFactory.createFromImage(doc, reader.read(i)));
            }
            return frames;
        } finally {
            reader.dispose();
        }
    }

    /** Fits the image inside a letter page (landscape when the image is wider than tall), centered. */
    private static void addPage(PDDocument doc, PDImageXObject image) throws IOException {
        boolean landscape = image.getWidth() > image.getHeight();
        PDRectangle size = landscape
                ? new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth())
                : PDRectangle.LETTER;
        PDPage page = new PDPage(size);
        doc.addPage(page);
        float availableWidth = size.getWidth() - 2 * MARGIN;
        float availableHeight = size.getHeight() - 2 * MARGIN;
        float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;
        float x = (size.getWidth() - width) / 2;
        float y = (size.getHeight() - height) / 2;
        try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
            content.drawImage(image, x, y, width, height);
        }
    }
}
