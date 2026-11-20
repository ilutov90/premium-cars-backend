package com.premiumcars.order;

/** Where a customer order stands; stored as its {@code name()}. */
public enum OrderStatus {
    CONTRACT_SIGNED,
    PAID,
    SHIPPED,
    ARRIVED,
    DELIVERED,
    CANCELLED
}
