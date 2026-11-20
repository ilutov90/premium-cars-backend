package com.premiumcars.order;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for the {@code order_document} table (the rows; the files are kept by {@link OrderDocumentStorage}). */
@Repository
public class OrderDocumentRepository {

    private static final String SELECT = """
            SELECT id, order_id, kind, title, file_name, content_type, size_bytes, storage_key, uploaded_at,
                   uploaded_by
            FROM order_document
            """;

    private static final RowMapper<OrderDocument> MAPPER = (rs, n) -> new OrderDocument(
            rs.getLong("id"),
            rs.getLong("order_id"),
            DocumentKind.valueOf(rs.getString("kind")),
            rs.getString("title"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getObject("size_bytes", Long.class),
            rs.getString("storage_key"),
            rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
            rs.getString("uploaded_by"));

    private final JdbcClient jdbc;

    public OrderDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long orderId, DocumentKind kind, String title, String fileName, String contentType,
                       Long sizeBytes, String storageKey, String uploadedBy) {
        Map<String, Object> p = new HashMap<>();
        p.put("orderId", orderId);
        p.put("kind", kind.name());
        p.put("title", title);
        p.put("fileName", fileName);
        p.put("contentType", contentType);
        p.put("sizeBytes", sizeBytes);
        p.put("storageKey", storageKey);
        p.put("uploadedBy", uploadedBy);
        return jdbc.sql("""
                        INSERT INTO order_document (order_id, kind, title, file_name, content_type, size_bytes,
                                                    storage_key, uploaded_by)
                        VALUES (:orderId, :kind, :title, :fileName, :contentType, :sizeBytes, :storageKey,
                                :uploadedBy)
                        RETURNING id""")
                .params(p)
                .query(Long.class)
                .single();
    }

    /** The order's documents, in the order they were added. */
    public List<OrderDocument> findByOrder(long orderId) {
        return jdbc.sql(SELECT + "WHERE order_id = :orderId ORDER BY id")
                .param("orderId", orderId)
                .query(MAPPER)
                .list();
    }

    public Optional<OrderDocument> find(long orderId, long documentId) {
        return jdbc.sql(SELECT + "WHERE id = :id AND order_id = :orderId")
                .param("id", documentId)
                .param("orderId", orderId)
                .query(MAPPER)
                .optional();
    }

    /** @return false when the order has no such document */
    public boolean update(long orderId, long documentId, DocumentKind kind, String title) {
        return jdbc.sql("UPDATE order_document SET kind = :kind, title = :title "
                        + "WHERE id = :id AND order_id = :orderId")
                .param("kind", kind.name())
                .param("title", title)
                .param("id", documentId)
                .param("orderId", orderId)
                .update() == 1;
    }

    /** @return false when the order has no such document */
    public boolean delete(long orderId, long documentId) {
        return jdbc.sql("DELETE FROM order_document WHERE id = :id AND order_id = :orderId")
                .param("id", documentId)
                .param("orderId", orderId)
                .update() == 1;
    }
}
