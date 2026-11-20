package com.premiumcars.order;

import jakarta.validation.constraints.Size;

/**
 * The buyer; every field is optional.
 *
 * @param idNumber    personal number (ЕГН / ЛНЧ)
 * @param companyEik  the company's ЕИК, when a company buys
 */
public record Customer(
        @Size(max = 200) String name,
        @Size(max = 50) String phone,
        @Size(max = 200) String email,
        @Size(max = 500) String address,
        @Size(max = 20) String idNumber,
        @Size(max = 200) String companyName,
        @Size(max = 20) String companyEik
) {

    public static final Customer EMPTY = new Customer(null, null, null, null, null, null, null);

    /** Trimmed, blank as null. */
    Customer normalized() {
        return new Customer(OrderText.clean(name), OrderText.clean(phone), OrderText.clean(email),
                OrderText.clean(address), OrderText.clean(idNumber), OrderText.clean(companyName),
                OrderText.clean(companyEik));
    }
}
