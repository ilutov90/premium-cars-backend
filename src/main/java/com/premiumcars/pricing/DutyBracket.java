package com.premiumcars.pricing;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * A row of a duty and VAT table (autowini): {@code dutyVat} for a FOB up to {@code fobUpTo} (EUR, inclusive) and
 * above the previous row; {@code fobUpTo} null = every FOB above the other rows.
 */
public record DutyBracket(@DecimalMin("0") BigDecimal fobUpTo, @NotNull @DecimalMin("0") BigDecimal dutyVat) {
}
