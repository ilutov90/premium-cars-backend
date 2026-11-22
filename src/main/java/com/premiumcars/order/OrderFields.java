package com.premiumcars.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The order's own editable values (besides the car and the customer); all optional but the status.
 *
 * @param nextContactAt when to contact the customer next (the follow-up list, the morning e-mail)
 * @param lostReason    why the customer did not buy (a cancelled order)
 */
public record OrderFields(String vin, BigDecimal agreedPriceEur, BigDecimal depositEur, OrderStatus status,
                          String containerNumber, LocalDate contractDate, String notes, Instant nextContactAt,
                          LostReason lostReason) {

    OrderFields withStatus(OrderStatus newStatus) {
        return new OrderFields(vin, agreedPriceEur, depositEur, newStatus, containerNumber, contractDate, notes,
                nextContactAt, lostReason);
    }
}
