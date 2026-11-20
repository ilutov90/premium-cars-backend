package com.premiumcars.order;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.order.OrderDocumentStorage.StoredFile;
import com.premiumcars.order.OrderDtos.DocumentUpdate;
import com.premiumcars.order.OrderDtos.DocumentView;

/**
 * The files of an order: PDF, DOC, DOCX, JPEG, PNG, WEBP (recognised by their bytes), or several photos of a paper
 * document (JPEG, PNG) combined into one PDF page by page ({@link ScanPdf}). Not transactional: the file is stored
 * first (a network call), then the row is inserted; a failed insert removes the file again.
 */
@Service
public class OrderDocumentService {

    private static final Logger log = LoggerFactory.getLogger(OrderDocumentService.class);
    public static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    public static final int MAX_IMAGES = 40;
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final Set<String> JPEG_NAMES = Set.of("jpg", "jpeg");

    /** One uploaded file as the client named it. */
    public record Upload(String fileName, byte[] bytes) {
    }

    /** A stored file ready to be sent back. */
    public record FileContent(String fileName, String contentType, byte[] bytes) {
    }

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderDocumentStorage storage;

    public OrderDocumentService(OrderRepository orders, OrderDocumentRepository documents,
                                OrderDocumentStorage storage) {
        this.orders = orders;
        this.documents = documents;
        this.storage = storage;
    }

    public DocumentView upload(long orderId, DocumentKind kind, String title, List<Upload> uploads, String admin) {
        if (kind == null) {
            throw new BadRequestException("Choose the kind of the document");
        }
        orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        if (uploads == null || uploads.isEmpty()) {
            throw new BadRequestException("Choose a file");
        }
        if (uploads.size() > MAX_IMAGES) {
            throw new BadRequestException("At most " + MAX_IMAGES + " images in one document");
        }
        for (Upload u : uploads) {
            if (u.bytes().length == 0) {
                throw new BadRequestException("File " + name(u) + " is empty");
            }
            if (u.bytes().length > MAX_FILE_BYTES) {
                throw new BadRequestException("File " + name(u) + " is larger than 25 MB");
            }
        }
        Prepared p = prepare(kind, uploads);
        String key = storage.put(orderId, p.bytes(), p.type().contentType());
        try {
            long id = documents.insert(orderId, kind, clean(title), p.fileName(), p.type().contentType(),
                    (long) p.bytes().length, key, admin);
            return DocumentView.of(documents.find(orderId, id).orElseThrow());
        } catch (RuntimeException e) {
            removeQuietly(key);
            throw e;
        }
    }

    public FileContent file(long orderId, long documentId) {
        OrderDocument d = find(orderId, documentId);
        if (d.storageKey() == null) {
            throw new NotFoundException("Document " + documentId + " has no file");
        }
        try {
            StoredFile f = storage.get(d.storageKey());
            return new FileContent(d.fileName(), d.contentType() != null ? d.contentType() : f.contentType(),
                    f.bytes());
        } catch (UncheckedIOException e) {
            log.warn("File of document {} of order {} is not readable: {}", documentId, orderId, e.toString());
            throw new NotFoundException("The file of document " + documentId + " is not available");
        }
    }

    public DocumentView update(long orderId, long documentId, DocumentUpdate request) {
        find(orderId, documentId);
        documents.update(orderId, documentId, request.kind(), clean(request.title()));
        return DocumentView.of(find(orderId, documentId));
    }

    public void delete(long orderId, long documentId) {
        OrderDocument d = find(orderId, documentId);
        documents.delete(orderId, documentId);
        if (d.storageKey() != null) {
            removeQuietly(d.storageKey());
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Prepared(byte[] bytes, DocumentFileType type, String fileName) {
    }

    private Prepared prepare(DocumentKind kind, List<Upload> uploads) {
        List<DocumentFileType> types = uploads.stream().map(u -> DocumentFileType.detect(u.bytes(), u.fileName())
                .orElseThrow(() -> new BadRequestException("File " + name(u)
                        + " is not supported: PDF, DOC, DOCX, JPEG, PNG or WEBP only"))).toList();
        String scanName = kind.name().toLowerCase(Locale.ROOT) + "-" + LocalDate.now(SOFIA) + ".pdf";
        if (uploads.size() == 1) {
            Upload u = uploads.getFirst();
            DocumentFileType t = types.getFirst();
            boolean photo = t == DocumentFileType.JPEG || t == DocumentFileType.PNG;
            if (photo && kind == DocumentKind.CONTRACT) {
                String own = fileName(u.fileName(), DocumentFileType.PDF);
                return new Prepared(ScanPdf.combine(List.of(u.bytes())), DocumentFileType.PDF,
                        own == null ? scanName : own);
            }
            String own = fileName(u.fileName(), t);
            return new Prepared(u.bytes(), t, own == null ? scanName.replace(".pdf", "." + t.extension()) : own);
        }
        for (int i = 0; i < uploads.size(); i++) {
            DocumentFileType t = types.get(i);
            if (t != DocumentFileType.JPEG && t != DocumentFileType.PNG) {
                throw new BadRequestException("Several files are combined into one PDF only when they are JPEG or "
                        + "PNG images; " + name(uploads.get(i)) + " is " + t);
            }
        }
        return new Prepared(ScanPdf.combine(uploads.stream().map(Upload::bytes).toList()), DocumentFileType.PDF,
                scanName);
    }

    /** The uploaded name without any path, control characters or a wrong extension; null when none is left. */
    static String fileName(String uploaded, DocumentFileType type) {
        if (uploaded == null) {
            return null;
        }
        String name = uploaded.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && name.length() - dot <= 6 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : null;
        boolean fits = ext != null && (ext.equals(type.extension())
                || type == DocumentFileType.JPEG && JPEG_NAMES.contains(ext));
        if (!fits) {
            name = (ext == null ? name : name.substring(0, dot)) + "." + type.extension();
        }
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private OrderDocument find(long orderId, long documentId) {
        return documents.find(orderId, documentId)
                .orElseThrow(() -> new NotFoundException("Document not found: " + documentId));
    }

    private void removeQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the stored file {}: {}", key, e.toString());
        }
    }

    private static String clean(String title) {
        return OrderText.clean(title);
    }

    private static String name(Upload u) {
        return u.fileName() == null || u.fileName().isBlank() ? "(unnamed)" : u.fileName();
    }
}
