package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.order.OrderRepository.OrderRow;

class OrderLeadTest {

    @Test
    void testPhoneKey_SamePhoneWrittenDifferently_SameKey() {
        assertThat(OrderRepository.phoneKey("+359 888 123 456")).isEqualTo("888123456");
        assertThat(OrderRepository.phoneKey("0888123456")).isEqualTo("888123456");
        assertThat(OrderRepository.phoneKey("00359 (888) 12-34-56")).isEqualTo("888123456");
    }

    @Test
    void testPhoneKey_TooShortOrMissing_Null() {
        assertThat(OrderRepository.phoneKey(null)).isNull();
        assertThat(OrderRepository.phoneKey("12-34")).isNull();
    }

    @Test
    void testHoldsCar_LeadAndCancelled_DoNotHoldTheCar() {
        assertThat(OrderStatus.LEAD.holdsCar()).isFalse();
        assertThat(OrderStatus.CANCELLED.holdsCar()).isFalse();
        assertThat(OrderStatus.CONTRACT_SIGNED.holdsCar()).isTrue();
        assertThat(OrderStatus.DELIVERED.holdsCar()).isTrue();
        assertThat(OrderStatus.LEAD.closesInquiry()).isFalse();
        assertThat(OrderStatus.CANCELLED.closesInquiry()).isTrue();
    }

    @Test
    void testFollowUpText_LeadAndAgreedOrder_OneLineEachWithLink() {
        Instant at = Instant.parse("2026-11-23T07:00:00Z");
        OrderRow lead = row(5, "Мария", "0888123456", null, at, 2);
        OrderRow agreed = row(6, null, null, "Kia K5", at, 0);

        String text = FollowUpMailer.text(List.of(lead, agreed), "https://site.bg");

        assertThat(text).contains("• 23.11 09:00 – Мария, 0888123456 – предложени коли: 2")
                .contains("https://site.bg/admin/orders/5")
                .contains("• 23.11 09:00 – (без име) – Kia K5")
                .contains("https://site.bg/admin/orders?followUp=1");
    }

    @Test
    void testChangeNote_OldCarGoneOrBack_NoteNamesBoth() {
        assertThat(OrderService.changeNote("Kia Stinger", 125890L, "Kia K5", 130001, true))
                .isEqualTo("Колата е сменена: Kia Stinger (Обява № 125890) → Kia K5 (Обява № 130001)."
                        + " Предишната е върната в сайта.");
        assertThat(OrderService.changeNote(null, null, "Kia K5", 130001, false))
                .isEqualTo("Колата е сменена: кола → Kia K5 (Обява № 130001).");
    }

    private static OrderRow row(long id, String name, String phone, String carTitle, Instant next, int candidates) {
        CarSnapshot car = new CarSnapshot(carTitle, null, null, null, null, null, null, null, null, null);
        Customer customer = new Customer(name, phone, null, null, null, null, null);
        OrderFields fields = new OrderFields(null, null, null, OrderStatus.LEAD, null, null, null, next, null);
        CustomerOrder order = new CustomerOrder(id, null, car, customer, fields, next, next, "admin", List.of(), null);
        return new OrderRow(order, null, null, null, 0, candidates);
    }
}
