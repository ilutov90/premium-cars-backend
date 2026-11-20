package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class DocumentFileTypeTest {

    private static byte[] zipWith(String entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(1);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void testDetect_JpegBytesNamedPdf_Jpeg() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        assertThat(DocumentFileType.detect(jpeg, "contract.pdf")).contains(DocumentFileType.JPEG);
    }

    @Test
    void testDetect_PdfPngWebp_ByMagicBytes() {
        assertThat(DocumentFileType.detect("%PDF-1.7\n".getBytes(), "x.bin")).contains(DocumentFileType.PDF);
        assertThat(DocumentFileType.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, null))
                .contains(DocumentFileType.PNG);
        assertThat(DocumentFileType.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(), "a.webp"))
                .contains(DocumentFileType.WEBP);
    }

    @Test
    void testDetect_ExecutableNamedPdf_Empty() {
        assertThat(DocumentFileType.detect("MZ\u0090\0\u0003\0\0\0".getBytes(), "invoice.pdf")).isEmpty();
        assertThat(DocumentFileType.detect(new byte[0], "a.pdf")).isEmpty();
    }

    @Test
    void testDetect_ZipWithWordDocument_DocxOtherZipRefused() throws Exception {
        assertThat(DocumentFileType.detect(zipWith("word/document.xml"), "a.docx")).contains(DocumentFileType.DOCX);
        assertThat(DocumentFileType.detect(zipWith("evil.exe"), "a.docx")).isEmpty();
    }

    @Test
    void testDetect_OleFile_DocOnlyWithDocName() {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertThat(DocumentFileType.detect(ole, "Contract.DOC")).contains(DocumentFileType.DOC);
        assertThat(DocumentFileType.detect(ole, "sheet.xls")).isEmpty();
    }

    @Test
    void testFileName_WrongExtensionAndPath_FixedAndStripped() {
        assertThat(OrderDocumentService.fileName("C:\\x\\contract.pdf", DocumentFileType.JPEG))
                .isEqualTo("contract.jpg");
        assertThat(OrderDocumentService.fileName("photo.jpeg", DocumentFileType.JPEG)).isEqualTo("photo.jpeg");
        assertThat(OrderDocumentService.fileName("../..", DocumentFileType.PDF)).isNull();
        assertThat(OrderDocumentService.fileName("scan", DocumentFileType.PDF)).isEqualTo("scan.pdf");
    }
}
