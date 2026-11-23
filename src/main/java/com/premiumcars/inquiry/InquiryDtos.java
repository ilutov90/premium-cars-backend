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

    /**
     * @param orderId          the order made from this inquiry ("Свързахме се" makes a potential one), if any
     * @param orderStatus      that order's status (a {@code com.premiumcars.order.OrderStatus} name)
     * @param otherInquiryIds  earlier or later inquiries from the same phone (a known customer), newest first
     * @param otherOrderIds    orders of a customer with the same phone, other than {@code orderId}, newest first
     */
    public record AdminInquiry(Long id, Long carId, String carTitle, String carSlug, String name, String phone,
                               String email, String message, Inquiry.Status status, Instant createdAt,
                               HeardFrom heardFrom, Attribution attribution, Long orderId, String orderStatus,
                               List<Long> otherInquiryIds, List<Long> otherOrderIds) {
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
     * How far one channel's inquiries got: {@code orders} = an order was made from them (potential or more),
     * {@code agreed} = a contract (signed, paid, shipped, arrived or delivered), {@code lost} = the order was
     * cancelled.
     */
    public record Funnel(String key, long inquiries, long orders, long agreed, long delivered, long lost) {
    }

    /**
     * The weekly report of the admin panel: inquiries from {@code from} to {@code to} (both inclusive, Sofia time).
     *
     * @param byHeardFrom   per answer to "Откъде научихте за нас?"
     * @param byUtmSource   per utm_source of the link the customer came with (facebook, viber, mobile.bg ...)
     * @param byUtmCampaign per utm_campaign
     * @param byCar         per car (null car = "find me a car"), most first
     * @param byWeek        per week (Monday), oldest first
     * @param funnelBySource   per utm_source: how many became orders, contracts, deliveries
     * @param funnelByCampaign per utm_campaign, the same
     * @param funnelByHeardFrom per answer to "Откъде научихте за нас?", the same
     * @param lostReasons   why the period's inquiries did not buy (a {@code LostReason} name; null = not given)
     */
    public record InquiryReport(LocalDate from, LocalDate to, long total, List<Count> byStatus,
                                List<Count> byHeardFrom, List<Count> byUtmSource, List<Count> byUtmCampaign,
                                List<CarCount> byCar, List<WeekCount> byWeek, List<Funnel> funnelBySource,
                                List<Funnel> funnelByCampaign, List<Funnel> funnelByHeardFrom,
                                List<Count> lostReasons) {
    }
}
