package com.premiumcars.order;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The kinds of file an order's document may be, recognised by the file's first bytes (what the client calls it does
 * not count): a ".pdf" that is really a JPEG is a JPEG.
 */
public enum DocumentFileType {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    DOC("application/msword", "doc"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

    private final String contentType;
    private final String extension;

    DocumentFileType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** JPEG, PNG and WEBP. */
    public boolean isImage() {
        return this == JPEG || this == PNG || this == WEBP;
    }

    /** The extension for a stored content type; "bin" for an unknown one. */
    public static String extensionOf(String contentType) {
        for (DocumentFileType t : values()) {
            if (t.contentType.equalsIgnoreCase(contentType)) {
                return t.extension;
            }
        }
        return "bin";
    }

    /**
     * @param fileName the uploaded name: only used for the old ".doc" format, whose first bytes every Office 97-2003
     *                 file (xls, ppt) shares
     */
    public static Optional<DocumentFileType> detect(byte[] b, String fileName) {
        if (startsWith(b, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(b, 0, 'R', 'I', 'F', 'F') && startsWith(b, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        if (startsWith(b, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) && fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".doc")) {
            return Optional.of(DOC);
        }
        if (startsWith(b, 0, 'P', 'K', 3, 4) && isWordPackage(b)) {
            return Optional.of(DOCX);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] b, int offset, int... expected) {
        if (b.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((b[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** A zip with a word document inside, not any zip. */
    private static boolean isWordPackage(byte[] b) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b), StandardCharsets.UTF_8)) {
            ZipEntry e;
            int seen = 0;
            while ((e = zip.getNextEntry()) != null && seen++ < 200) {
                if (e.getName().equals("word/document.xml")) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        return false;
    }
}
