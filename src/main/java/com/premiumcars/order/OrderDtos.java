package com.premiumcars.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.premiumcars.catalog.domain.CarStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/** The admin panel's "Поръчки" API (admin only: customers' details and the source listing). */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Create an order or replace its values (PUT sends the whole order).
     *
     * @param carId  the catalog car ("Обява №"); required for a new order; on a change, another car takes a new
     *               snapshot, null leaves the order without a car (its snapshot stays)
     * @param status null = {@link OrderStatus#CONTRACT_SIGNED}
     */
    public record OrderRequest(
            Long carId,
            @Valid Customer customer,
            @Size(max = 30) String vin,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal agreedPriceEur,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal depositEur,
            OrderStatus status,
            @Size(max = 30) String containerNumber,
            LocalDate contractDate,
            @Size(max = 20000) String notes
    ) {
    }

    /**
     * A row of the orders' list.
     *
     * @param car       the car as it was when the order got it
     * @param carStatus the car's status now; null when the car was deleted
     * @param coverUrl  the car's cover photo now; null when it has none or was deleted
     */
    public record OrderSummary(long id, Long carId, CarSnapshot car, CarStatus carStatus, String coverUrl,
                               Customer customer, String vin, BigDecimal agreedPriceEur, BigDecimal depositEur,
                               OrderStatus status, String containerNumber, LocalDate contractDate,
                               int documentCount, Instant createdAt, Instant updatedAt) {
    }

    /** One order with its documents (their details; the files come from their own endpoints). */
    public record AdminOrder(long id, Long carId, CarSnapshot car, CarStatus carStatus, String coverUrl,
                             Customer customer, String vin, BigDecimal agreedPriceEur, BigDecimal depositEur,
                             OrderStatus status, String containerNumber, LocalDate contractDate, String notes,
                             Instant createdAt, Instant updatedAt, String createdBy,
                             List<DocumentView> documents) {
    }

    public record DocumentView(long id, DocumentKind kind, String title, String fileName, String contentType,
                               Long sizeBytes, Instant uploadedAt, String uploadedBy) {

        static DocumentView of(OrderDocument d) {
            return new DocumentView(d.id(), d.kind(), d.title(), d.fileName(), d.contentType(), d.sizeBytes(),
                    d.uploadedAt(), d.uploadedBy());
        }
    }

    /**
     * The car an admin chose for an order, by its number or a link to its page.
     *
     * @param priceEur      the site's final price
     * @param activeOrderId the car's order that is not cancelled, if it has one (a car has at most one)
     */
    public record CarLookup(long id, String title, String slug, CarStatus status, String coverUrl,
                            BigDecimal priceEur, Long activeOrderId) {
    }
}
