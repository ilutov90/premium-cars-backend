package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.premiumcars.common.GlobalExceptionHandler;
import com.premiumcars.config.AppProperties;
import com.premiumcars.order.OrderRepository.OrderRow;

class AdminOrderDocumentsTest {

    @TempDir
    Path dir;

    private final Map<Long, OrderDocument> rows = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private LocalOrderDocumentStorage storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        storage = new LocalOrderDocumentStorage(dir);
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(5L)).thenReturn(Optional.of(mock(OrderRow.class)));
        OrderDocumentRepository documents = mock(OrderDocumentRepository.class);
        when(documents.insert(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            long id = ids.incrementAndGet();
            rows.put(id, new OrderDocument(id, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), inv.getArgument(6), Instant.now(),
                    inv.getArgument(7)));
            return id;
        });
        when(documents.find(anyLong(), anyLong())).thenAnswer(inv -> {
            OrderDocument d = rows.get(inv.<Long>getArgument(1));
            return d != null && d.orderId() == inv.<Long>getArgument(0) ? Optional.of(d) : Optional.empty();
        });
        when(documents.update(anyLong(), anyLong(), any(), any())).thenAnswer(inv -> {
            OrderDocument d = rows.get(inv.<Long>getArgument(1));
            rows.put(d.id(), new OrderDocument(d.id(), d.orderId(), inv.getArgument(2), inv.getArgument(3),
                    d.fileName(), d.contentType(), d.sizeBytes(), d.storageKey(), d.uploadedAt(), d.uploadedBy()));
            return true;
        });
        when(documents.delete(anyLong(), anyLong())).thenAnswer(inv -> rows.remove(inv.<Long>getArgument(1)) != null);
        var documentService = new OrderDocumentService(orders, documents, storage);
        mvc = MockMvcBuilders.standaloneSetup(new AdminOrderController(mock(OrderService.class), documentService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    void testUpload_PdfThenDownload_RoundTripWithPrivateHeaders() throws Exception {
        byte[] pdf = "%PDF-1.4 contract".getBytes();

        mvc.perform(multipart("/api/admin/orders/5/documents").file(new MockMultipartFile("file", "Договор.pdf",
                        "application/pdf", pdf)).param("kind", "CONTRACT").principal(() -> "admin"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("CONTRACT"))
                .andExpect(jsonPath("$.fileName").value("Договор.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(pdf.length))
                .andExpect(jsonPath("$.uploadedBy").value("admin"));

        long id = rows.keySet().iterator().next();
        mvc.perform(get("/api/admin/orders/5/documents/" + id + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.is("inline; filename=\"_______.pdf\"; filename*=UTF-8''%D0%94%D0%BE%D0%B3%D0%BE%D0%B2%D0%BE%D1%80.pdf")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(pdf));
        mvc.perform(get("/api/admin/orders/5/documents/" + id + "/file").param("download", "1"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"")));
        mvc.perform(get("/api/admin/orders/6/documents/" + id + "/file")).andExpect(status().isNotFound());
    }

    @Test
    void testUpload_SeveralImages_OnePdfPagePerImageInOrder() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", jpeg(300, 200)))
                        .file(new MockMultipartFile("files", "b.jpg", "image/jpeg", jpeg(200, 300)))
                        .file(new MockMultipartFile("files", "c.jpg", "image/jpeg", jpeg(100, 100)))
                        .param("kind", "INVOICE_KOREA").param("title", " Scan "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.title").value("Scan"))
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern(
                        "invoice_korea-\\d{4}-\\d{2}-\\d{2}\\.pdf")));

        OrderDocument d = rows.values().iterator().next();
        try (var doc = Loader.loadPDF(storage.get(d.storageKey()).bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isGreaterThan(doc.getPage(0).getMediaBox().getHeight());
            assertThat(doc.getPage(1).getMediaBox().getHeight()).isGreaterThan(doc.getPage(1).getMediaBox().getWidth());
        }
    }

    @Test
    void testUpload_PdfNamedFileWithJpegBytes_StoredAsJpeg() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", jpeg(50, 50)))
                        .param("kind", "OTHER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.fileName").value("scan.jpg"));
    }

    @Test
    void testUpload_SingleImageAsContract_ConvertedToPdf() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("file", "page.jpg", "image/jpeg", jpeg(80, 80)))
                        .param("kind", "CONTRACT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileName").value("page.pdf"));
    }

    @Test
    void testUpload_ExecutableNamedPdf_BadRequestAndNothingStored() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ\u0090\0".getBytes()))
                        .param("kind", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("file", "setup.exe", "application/x-msdownload", "MZ\u0090\0".getBytes()))
                        .param("kind", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/orders/5/documents").param("kind", "OTHER"))
                .andExpect(status().isBadRequest());
        assertThat(rows).isEmpty();
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void testUpload_PdfMixedWithImages_BadRequest() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                        .file(new MockMultipartFile("files", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                        .file(new MockMultipartFile("files", "b.jpg", "image/jpeg", jpeg(20, 20)))
                        .param("kind", "OTHER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpload_UnknownOrder_NotFound() throws Exception {
        mvc.perform(multipart("/api/admin/orders/99/documents")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                        .param("kind", "OTHER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testUpdateAndDelete_Document_ChangesRowAndRemovesFile() throws Exception {
        mvc.perform(multipart("/api/admin/orders/5/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .param("kind", "OTHER"));
        OrderDocument d = rows.values().iterator().next();

        mvc.perform(put("/api/admin/orders/5/documents/" + d.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CUSTOMS\",\"title\":\"Customs paper\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CUSTOMS"))
                .andExpect(jsonPath("$.title").value("Customs paper"));
        mvc.perform(put("/api/admin/orders/5/documents/" + d.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isBadRequest());

        mvc.perform(delete("/api/admin/orders/5/documents/" + d.id())).andExpect(status().isNoContent());
        assertThat(rows).isEmpty();
        assertThatThrownBy(() -> storage.get(d.storageKey())).isInstanceOf(java.io.UncheckedIOException.class);
        mvc.perform(delete("/api/admin/orders/5/documents/" + d.id())).andExpect(status().isNotFound());
    }

    @Test
    void testConfig_LocalDocsDirInsideMediaDir_Refused() {
        var props = new AppProperties(null, null, new AppProperties.Media(dir.toString(), "/media/", null, null,
                dir.resolve("docs").toString()), null, null, null);
        assertThatThrownBy(() -> new OrderDocumentStorageConfig().orderDocumentStorage(props))
                .isInstanceOf(IllegalStateException.class);
        var ok = new AppProperties(null, null, new AppProperties.Media(dir.resolve("media").toString(), "/media/",
                null, null, dir.resolve("docs").toString()), null, null, null);
        assertThat(new OrderDocumentStorageConfig().orderDocumentStorage(ok)).isInstanceOf(LocalOrderDocumentStorage.class);
        var r2 = new AppProperties(null, null, new AppProperties.Media(dir.toString(), "/media/", null,
                new AppProperties.R2Docs("https://a.r2.cloudflarestorage.com", "b", "k", "s"), null), null, null, null);
        assertThat(new OrderDocumentStorageConfig().orderDocumentStorage(r2)).isInstanceOf(R2OrderDocumentStorage.class);
    }

    @Test
    void testLocalStorage_KeyOutsideRoot_Refused() {
        assertThatThrownBy(() -> storage.get("../secret.txt")).isInstanceOf(IllegalArgumentException.class);
    }
}
