package com.premiumcars.order;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.premiumcars.common.PageResponse;
import com.premiumcars.order.OrderDtos.AdminOrder;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.order.OrderDocumentService.FileContent;
import com.premiumcars.order.OrderDocumentService.Upload;
import com.premiumcars.order.OrderDtos.CarLookup;
import com.premiumcars.order.OrderDtos.DocumentUpdate;
import com.premiumcars.order.OrderDtos.DocumentView;
import com.premiumcars.order.OrderDtos.OrderRequest;
import com.premiumcars.order.OrderDtos.OrderSummary;

import jakarta.validation.Valid;

/** "Поръчки": the customers' orders of sold cars (admin only). */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService service;
    private final OrderDocumentService documentService;

    public AdminOrderController(OrderService service, OrderDocumentService documentService) {
        this.service = service;
        this.documentService = documentService;
    }

    /** Newest first; {@code q}: customer, company, car title, the car's number or link, VIN, container. */
    @GetMapping
    public PageResponse<OrderSummary> list(@RequestParam(required = false) OrderStatus status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(status, q, page, size);
    }

    /** The car for an order, by its number ("Обява № {id}") or a link to its page on our site; 404 when none. */
    @GetMapping("/car-lookup")
    public CarLookup carLookup(@RequestParam String q) {
        return service.lookupCar(q);
    }

    @GetMapping("/{id}")
    public AdminOrder get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminOrder create(@Valid @RequestBody OrderRequest request, Principal principal) {
        return service.create(request, principal == null ? null : principal.getName());
    }

    @PutMapping("/{id}")
    public AdminOrder update(@PathVariable long id, @Valid @RequestBody OrderRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /**
     * Adds a document: multipart with {@code kind}, an optional {@code title} and one {@code file} (PDF, DOC, DOCX,
     * JPEG, PNG, WEBP) or several images in {@code files} (repeated; JPEG and PNG), combined into one PDF, a page per
     * image in the order sent.
     */
    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView uploadDocument(@PathVariable long id, @RequestParam DocumentKind kind,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(name = "file", required = false) MultipartFile file,
                                       @RequestParam(name = "files", required = false) List<MultipartFile> files,
                                       Principal principal) {
        List<Upload> uploads = new ArrayList<>();
        try {
            if (file != null && !file.isEmpty()) {
                uploads.add(new Upload(file.getOriginalFilename(), file.getBytes()));
            }
            if (files != null) {
                for (MultipartFile f : files) {
                    if (!f.isEmpty()) {
                        uploads.add(new Upload(f.getOriginalFilename(), f.getBytes()));
                    }
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("The upload could not be read");
        }
        return documentService.upload(id, kind, title, uploads, principal == null ? null : principal.getName());
    }

    /** The file itself: inline, or as a download with {@code ?download=1}; never cached. */
    @GetMapping("/{id}/documents/{docId}/file")
    public ResponseEntity<byte[]> documentFile(@PathVariable long id, @PathVariable long docId,
                                               @RequestParam(required = false) String download) {
        FileContent f = documentService.file(id, docId);
        boolean attachment = download != null && !download.isBlank() && !download.equals("0")
                && !download.equalsIgnoreCase("false");
        String encoded = URLEncoder.encode(f.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String fallback = f.fileName().replaceAll("[^\\x20-\\x7E]|[\"\\\\]", "_");
        String disposition = (attachment ? "attachment" : "inline") + "; filename=\"" + fallback
                + "\"; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .contentLength(f.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(f.bytes());
    }

    @PutMapping("/{id}/documents/{docId}")
    public DocumentView updateDocument(@PathVariable long id, @PathVariable long docId,
                                       @Valid @RequestBody DocumentUpdate request) {
        return documentService.update(id, docId, request);
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable long id, @PathVariable long docId) {
        documentService.delete(id, docId);
    }
}
