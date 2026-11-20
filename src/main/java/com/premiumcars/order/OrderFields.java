package com.premiumcars.order;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The order's own editable values (besides the car and the customer); all optional but the status. */
public record OrderFields(String vin, BigDecimal agreedPriceEur, BigDecimal depositEur, OrderStatus status,
                          String containerNumber, LocalDate contractDate, String notes) {
}
