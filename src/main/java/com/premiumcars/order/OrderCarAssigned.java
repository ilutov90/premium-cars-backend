package com.premiumcars.order;

/** An order was made for this car, or got it: published inside the order's transaction. */
public record OrderCarAssigned(long orderId, long carId) {
}
