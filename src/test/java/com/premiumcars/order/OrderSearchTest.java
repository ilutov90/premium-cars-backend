package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.premiumcars.order.OrderDtos.OrderRequest;

class OrderSearchTest {

    @Test
    void testWhere_NoStatusNoText_Empty() {
        var w = OrderRepository.where(null, "  ");
        assertThat(w.sql()).isEmpty();
        assertThat(w.params()).isEmpty();
    }

    @Test
    void testWhere_StatusAndName_FixedFragmentsWithEscapedText() {
        var w = OrderRepository.where(OrderStatus.PAID, " Ivan_50% ");
        assertThat(w.sql()).startsWith("WHERE o.status = :status AND (o.customer_name ILIKE :q")
                .doesNotContain("Ivan").doesNotContain(":carNumber").doesNotContain(":phoneDigits");
        assertThat(w.params()).containsEntry("status", "PAID").containsEntry("q", "%Ivan\\_50\\%%");
    }

    @Test
    void testWhere_CarNumber_MatchesCarIdAndPhoneDigits() {
        var w = OrderRepository.where(null, "№ 29538");
        assertThat(w.sql()).contains("o.car_id = :carNumber").doesNotContain(":phoneDigits");
        assertThat(w.params()).containsEntry("carNumber", 29538L);

        assertThat(OrderRepository.where(null, "Обява № 29538").params()).containsEntry("carNumber", 29538L);

        var phone = OrderRepository.where(null, "+359 888 12");
        assertThat(phone.sql()).contains(":phoneDigits");
        assertThat(phone.params()).containsEntry("phoneDigits", "%35988812%");
    }

    @Test
    void testWhere_SiteLink_MatchesCarSlug() {
        var w = OrderRepository.where(null, "https://premiumcarsimport.bg/cars/kia-k5-2022-123?utm_source=fb");
        assertThat(w.sql()).contains("o.car_slug = :slug");
        assertThat(w.params()).containsEntry("slug", "kia-k5-2022-123");
    }

    @Test
    void testFields_BlankValuesAndNoStatus_NullsAndContractSigned() {
        var f = OrderService.fields(new OrderRequest(1L, null, " knadg81 ", new BigDecimal("100"), null, null,
                "  ", null, " note "));
        assertThat(f.status()).isEqualTo(OrderStatus.CONTRACT_SIGNED);
        assertThat(f.vin()).isEqualTo("KNADG81");
        assertThat(f.containerNumber()).isNull();
        assertThat(f.notes()).isEqualTo("note");
    }

    @Test
    void testNormalized_CustomerWithBlanks_TrimmedAndNull() {
        var c = new Customer(" Ivan ", " ", null, "", " 8001010000 ", null, " 123 ").normalized();
        assertThat(c).isEqualTo(new Customer("Ivan", null, null, null, "8001010000", null, "123"));
    }
}
