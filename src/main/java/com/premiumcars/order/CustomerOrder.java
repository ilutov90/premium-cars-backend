package com.premiumcars.order;

import java.time.Instant;
import java.util.List;

/**
 * One row of the {@code customer_order} table.
 *
 * @param carId the catalog car; null once that car was deleted (the snapshot stays), and for a potential order
 * @param car   the car as it was when the order was made or got this car
 * @param carPhotos storage paths of the car's stored photos, gallery order (kept when the car is deleted)
 * @param inquiryId the site inquiry the order was made from, if any
 */
public record CustomerOrder(
        long id,
        Long carId,
        CarSnapshot car,
        Customer customer,
        OrderFields fields,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        List<String> carPhotos,
        Long inquiryId
) {
}
