package com.premiumcars.delivery;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code delivered_car}: a car handed over to a customer, for the site's "Доставени коли".
 *
 * @param carId        the catalog car, if it is still there; null = not linked
 * @param carPublic    the linked car is shown on the public site (published or reserved), so it can be linked to
 * @param carSlug      the linked car's slug (null without a linked car)
 * @param customerName how the customer is named on the site, e.g. "Георги"
 * @param quote        the customer's words; null = none
 * @param photoPath    storage path of the photo (see {@code ImageStorage}); null = no photo
 * @param consent      the customer agreed to be shown on the site (required before publishing)
 * @param published    shown on the public site
 */
public record DeliveredCar(
        long id,
        Long carId,
        boolean carPublic,
        String carSlug,
        String carTitle,
        String customerName,
        String city,
        String quote,
        LocalDate deliveredOn,
        String photoPath,
        boolean consent,
        boolean published,
        Instant createdAt,
        Instant updatedAt
) {
}
