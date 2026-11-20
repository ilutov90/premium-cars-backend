package com.premiumcars.order;

import java.time.Instant;

/**
 * One row of the {@code customer_order} table.
 *
 * @param carId the catalog car; null once that car was deleted (the snapshot stays)
 * @param car   the car as it was when the order was made or got this car
 */
public record CustomerOrder(
        long id,
        Long carId,
        CarSnapshot car,
        Customer customer,
        OrderFields fields,
        Instant createdAt,
        Instant updatedAt,
        String createdBy
) {
}
