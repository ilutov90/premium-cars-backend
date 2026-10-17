package com.premiumcars.inquiry;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InquiryDtos {

    private InquiryDtos() {
    }

    /**
     * @param website honeypot field: hidden in the form, real people leave it empty, bots fill it in
     */
    public record InquiryRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 40) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 4000) String message,
            Long carId,
            String website
    ) {
    }

    public record InquiryCreated(Long id) {
    }

    public record AdminInquiry(Long id, Long carId, String carTitle, String carSlug, String name, String phone,
                               String email, String message, Inquiry.Status status, Instant createdAt) {
    }

    public record InquiryStatusRequest(@NotNull Inquiry.Status status) {
    }
}
