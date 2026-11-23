package com.premiumcars.order;

/** Where a customer order stands; stored as its {@code name()}. */
public enum OrderStatus {
    /**
     * A potential order ("Потенциална"): the customer was contacted, nothing is agreed. Its offered cars
     * ({@code order_candidate_car}) stay on the site and the order holds no car of its own.
     */
    LEAD,
    CONTRACT_SIGNED,
    PAID,
    SHIPPED,
    ARRIVED,
    DELIVERED,
    CANCELLED;

    /** An agreed order: its car is SOLD, kept from the automatic import, and no other agreed order may take it. */
    public boolean holdsCar() {
        return this != LEAD && this != CANCELLED;
    }

    /** Agreed or cancelled: the inquiry it came from is then closed. */
    public boolean closesInquiry() {
        return this != LEAD;
    }
}
