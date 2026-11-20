package com.premiumcars.order;

/** What an order's document is; stored as its {@code name()}. {@code OTHER} is named by its title. */
public enum DocumentKind {
    INVOICE_KOREA,
    INVOICE_PORT,
    INVOICE_ROTTERDAM,
    CONTRACT,
    BILL_OF_LADING,
    CUSTOMS,
    OTHER
}
