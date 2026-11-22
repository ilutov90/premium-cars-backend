package com.premiumcars.order;

/** How the business talked with the customer (an {@code order_contact} entry); stored as its {@code name()}. */
public enum ContactKind {
    CALL,
    VIBER,
    EMAIL,
    MEETING,
    NOTE,
    /** the customer's own inquiry from the site, copied when the order was made from it */
    INQUIRY
}
