package com.premiumcars.delivery;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class DeliveredCarDtos {

    private DeliveredCarDtos() {
    }

    /**
     * Create or change a delivered car.
     *
     * @param carId     the catalog car (optional); with a car, a blank {@code carTitle} takes the car's title
     * @param consent   the customer agreed to be shown on the site
     * @param published show it on the site; only with {@code consent}
     */
    public record DeliveredCarRequest(
            Long carId,
            @Size(max = 200) String carTitle,
            @NotBlank @Size(max = 100) String customerName,
            @Size(max = 100) String city,
            @Size(max = 2000) String quote,
            LocalDate deliveredOn,
            boolean consent,
            boolean published
    ) {
    }

    /** For the public site: no admin details (consent, the catalog id). {@code carSlug} only for a public car. */
    public record PublicDeliveredCar(long id, String carTitle, String customerName, String city, String quote,
                                     LocalDate deliveredOn, String photoUrl, String carSlug) {
    }

    public record AdminDeliveredCar(long id, Long carId, String carSlug, String carTitle, String customerName,
                                    String city, String quote, LocalDate deliveredOn, String photoUrl,
                                    boolean consent, boolean published, Instant createdAt, Instant updatedAt) {
    }
}
