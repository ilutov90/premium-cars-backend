package com.premiumcars.inquiry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InquiryDtos {

    private InquiryDtos() {
    }

    /**
     * @param name            optional; an inquiry without a name is stored with ""
     * @param website         honeypot field: hidden in the form, real people leave it empty, bots fill it in
     * @param privacyAccepted the customer ticked "I have read the privacy policy"; required (must be true)
     * @param heardFrom       "Откъде научихте за нас?"; optional
     * @param attribution     UTM parameters, landing page and referring site of the visit; optional
     */
    public record InquiryRequest(
            @Size(max = 100) String name, // optional since the short form (October 2026); stored as ""
            @Size(max = 40) String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 4000) String message,
            Long carId,
            String website,
            @NotNull @AssertTrue Boolean privacyAccepted,
            HeardFrom heardFrom,
            Attribution attribution
    ) {
    }

    public record InquiryCreated(Long id) {
    }

    public record AdminInquiry(Long id, Long carId, String carTitle, String carSlug, String name, String phone,
                               String email, String message, Inquiry.Status status, Instant createdAt,
                               HeardFrom heardFrom, Attribution attribution) {
    }

    public record InquiryStatusRequest(@NotNull Inquiry.Status status) {
    }

    /** Inquiries per key in a period (e.g. per answer to "Откъде научихте"); key null = not answered / none. */
    public record Count(String key, long count) {
    }

    public record CarCount(Long carId, String carTitle, String carSlug, long count) {
    }

    public record WeekCount(LocalDate weekStart, long count) {
    }

    /**
     * The weekly report of the admin panel: inquiries from {@code from} to {@code to} (both inclusive, Sofia time).
     *
     * @param byHeardFrom   per answer to "Откъде научихте за нас?"
     * @param byUtmSource   per utm_source of the link the customer came with (facebook, viber, mobile.bg ...)
     * @param byUtmCampaign per utm_campaign
     * @param byCar         per car (null car = "find me a car"), most first
     * @param byWeek        per week (Monday), oldest first
     */
    public record InquiryReport(LocalDate from, LocalDate to, long total, List<Count> byStatus,
                                List<Count> byHeardFrom, List<Count> byUtmSource, List<Count> byUtmCampaign,
                                List<CarCount> byCar, List<WeekCount> byWeek) {
    }
}
