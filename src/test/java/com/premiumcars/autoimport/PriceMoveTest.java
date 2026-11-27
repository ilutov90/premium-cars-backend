package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.autoimport.AutoImportRunner.PriceMove;
import com.premiumcars.catalog.domain.CarSource;

class PriceMoveTest {

    private static PriceMove move(String id, String before, String now) {
        return PriceMove.of(id, new BigDecimal(before), new BigDecimal(now));
    }

    @Test
    void testMovedPrices_AllMoveWithTheRate_NoneChanged() {
        List<PriceMove> moves = List.of(move("a", "10000", "10120"), move("b", "20000", "20240"),
                move("c", "15000", "15180"));

        assertThat(AutoImportRunner.movedPrices(moves)).isEmpty();
    }

    @Test
    void testMovedPrices_OneDropsBesidesTheRate_OnlyThatOne() {
        List<PriceMove> moves = List.of(move("a", "10000", "10120"), move("b", "20000", "20240"),
                move("c", "15000", "14000"), move("d", "12000", "12144"));

        assertThat(AutoImportRunner.movedPrices(moves)).containsExactly("c");
    }

    @Test
    void testMovedPrices_StablePricesOneRaised_OnlyThatOne() {
        List<PriceMove> moves = List.of(move("a", "10000", "10000"), move("b", "20000", "20200"),
                move("c", "15000", "15000"));

        assertThat(AutoImportRunner.movedPrices(moves)).containsExactly("b");
        assertThat(AutoImportRunner.movedPrices(List.of())).isEmpty();
    }

    @Test
    void testOf_MissingOrZeroPrice_Null() {
        assertThat(PriceMove.of("a", null, BigDecimal.TEN)).isNull();
        assertThat(PriceMove.of("a", BigDecimal.TEN, null)).isNull();
        assertThat(PriceMove.of("a", BigDecimal.ZERO, BigDecimal.TEN)).isNull();
    }

    @Test
    void testCurrency_EachSource_TheSearchCurrency() {
        assertThat(PriceLimit.currency(CarSource.AUTOWINI)).isEqualTo("EUR");
        assertThat(PriceLimit.currency(CarSource.GUAZI)).isEqualTo("USD");
        assertThat(PriceLimit.currency(CarSource.AUTOTRADER_CA)).isEqualTo("CAD");
    }
}
