package com.thehiddenbrain.interop.cms1500.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates attachment files of every supported (and some unsupported) kind. */
public final class TestFiles {

    private TestFiles() {
    }

    public static Path pdf(Path file, int pages, String label) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(label + " page " + i);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    public static Path encryptedPdf(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(file.toFile());
        }
        return file;
    }

    public static Path image(Path file, String format, int width, int height) throws IOException {
        if (!ImageIO.write(sample(width, height, format), format, file.toFile())) {
            throw new IOException("no writer for " + format);
        }
        return file;
    }

    public static Path multiPageTiff(Path file, int frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                writer.writeToSequence(new IIOImage(sample(400, 520, "frame " + (i + 1)), null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return file;
    }

    public static Path text(Path file, String content) throws IOException {
        Files.writeString(file, content);
        return file;
    }

    private static BufferedImage sample(int width, int height, String label) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(10, 10, width - 20, height - 20);
        g.drawString(label, 30, 40);
        g.dispose();
        return image;
    }
}
