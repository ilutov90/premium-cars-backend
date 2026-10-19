package com.premiumcars.inquiry;

/** A customer inquiry was saved; published inside the transaction, handled after the commit. */
public record InquiryCreatedEvent(long id, String name, String phone, String email, String message,
                                  String carTitle, String carSlug) {
}
