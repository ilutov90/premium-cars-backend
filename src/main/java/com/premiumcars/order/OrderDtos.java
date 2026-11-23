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
     * @param carId  the catalog car ("Обява №"); required for a new order but a potential one; on a change, another
     *               car takes a new snapshot, null leaves the order without a car (its snapshot stays). A potential
     *               order (LEAD) holds no car: a car sent with it is added to its offered cars instead
     * @param status null = {@link OrderStatus#CONTRACT_SIGNED}
     * @param nextContactAt when to contact the customer next; null = no reminder
     * @param lostReason    why the customer did not buy (for a cancelled order)
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
            @Size(max = 20000) String notes,
            Instant nextContactAt,
            LostReason lostReason
    ) {
    }

    /**
     * A row of the orders' list.
     *
     * @param car       the car as it was when the order got it
     * @param carStatus the car's status now; null when the car was deleted
     * @param coverUrl  the car's cover photo now; null when it has none or was deleted
     * @param candidateCount the cars offered to the customer (a potential order)
     * @param inquiryId the site inquiry the order was made from
     */
    public record OrderSummary(long id, Long carId, CarSnapshot car, CarStatus carStatus, String coverUrl,
                               Customer customer, String vin, BigDecimal agreedPriceEur, BigDecimal depositEur,
                               OrderStatus status, String containerNumber, LocalDate contractDate,
                               int documentCount, Instant createdAt, Instant updatedAt, Instant nextContactAt,
                               LostReason lostReason, int candidateCount, Long inquiryId) {
    }

    /**
     * One order with its documents (their details; the files come from their own endpoints).
     *
     * @param carPhotos displayable URLs of the car's photos in gallery order: the car's photos now, or, once the car
     *                  was deleted, the stored photos the order kept
     * @param inquiry         the site inquiry the order was made from, if any
     * @param candidates      the cars offered to the customer, in the order they were added
     * @param contacts        what was said with the customer, newest first
     * @param otherOrderIds   other orders of a customer with the same phone, newest first
     * @param otherInquiryIds inquiries from the same phone other than {@code inquiry}, newest first
     */
    public record AdminOrder(long id, Long carId, CarSnapshot car, CarStatus carStatus, String coverUrl,
                             Customer customer, String vin, BigDecimal agreedPriceEur, BigDecimal depositEur,
                             OrderStatus status, String containerNumber, LocalDate contractDate, String notes,
                             Instant createdAt, Instant updatedAt, String createdBy,
                             List<DocumentView> documents, List<String> carPhotos, Instant nextContactAt,
                             LostReason lostReason, InquiryInfo inquiry, List<CandidateView> candidates,
                             List<ContactView> contacts, List<Long> otherOrderIds, List<Long> otherInquiryIds) {
    }

    /** The inquiry an order was made from, as the customer sent it. */
    public record InquiryInfo(long id, Instant createdAt, String message, Long carId, String carTitle,
                              String carSlug, String status, com.premiumcars.inquiry.HeardFrom heardFrom,
                              com.premiumcars.inquiry.Attribution attribution) {
    }

    /**
     * A car offered to the customer.
     *
     * @param carId     null once the car was deleted (title, link and price stay as they were)
     * @param carStatus the car's status now (SOLD: sold, maybe to someone else); null when it is gone
     * @param priceEur  the site's final price when it was added
     */
    public record CandidateView(long id, Long carId, String title, String slug, BigDecimal priceEur,
                                String coverUrl, CarStatus carStatus, Instant addedAt, String addedBy) {

        static CandidateView of(OrderCandidateRepository.Candidate c) {
            return new CandidateView(c.id(), c.carId(), c.title(), c.slug(), c.priceEur(), c.coverUrl(),
                    c.carStatus(), c.addedAt(), c.addedBy());
        }
    }

    public record ContactView(long id, ContactKind kind, String body, Instant happenedAt, String createdBy,
                              Instant updatedAt) {

        static ContactView of(OrderContactRepository.Contact c) {
            return new ContactView(c.id(), c.kind(), c.body(), c.happenedAt(), c.createdBy(), c.updatedAt());
        }
    }

    /** Offer a car: its number ("Обява № 29538") or a link to its page on our site. */
    public record CandidateRequest(@jakarta.validation.constraints.NotBlank @Size(max = 2000) String q) {
    }

    /** The customer chose this offered car: it becomes the order's car; status null = CONTRACT_SIGNED. */
    public record ChooseRequest(OrderStatus status) {
    }

    /** @param happenedAt when it happened; null = now */
    public record ContactRequest(@jakarta.validation.constraints.NotNull ContactKind kind,
                                 @jakarta.validation.constraints.NotBlank @Size(max = 10000) String body,
                                 Instant happenedAt) {
    }

    /** The orders to contact today or earlier (not delivered or cancelled). */
    public record FollowUps(long due) {
    }

    /** What the admin may change of a document. */
    public record DocumentUpdate(@jakarta.validation.constraints.NotNull DocumentKind kind,
                                 @Size(max = 200) String title) {
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
