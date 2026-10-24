package com.premiumcars.mobilebg;

import java.math.BigDecimal;
import java.util.List;

import com.premiumcars.catalog.domain.CarFeature;

/**
 * The "Добави обява" form of mobile.bg filled in from a car, in mobile.bg's own values, for the admin panel to show
 * and edit before the extension types it into mobile.bg. Text values are those of mobile.bg's selects (e.g.
 * engine "Бензинов", gearbox "Автоматична", category "Джип", month "март"); null = not known, the admin chooses.
 * The description and the phone come from the admin panel (the mobile.bg template and the shared phone).
 *
 * @param condition  mobile.bg's code: "0" used (always: imported cars are used)
 * @param vat        mobile.bg's code: "2" = price with VAT (the final price includes it)
 * @param region     the "Намира се в" region as mobile.bg stores it ("София"), city as its option text
 * @param rangeKm    electric range (mobile.bg asks for WLTP) and battery: electric and plug-in cars only
 * @param extras     the car's extras; the admin panel turns them into mobile.bg's checkbox values
 */
public record MobileBgDraft(
        String brand,
        String model,
        String modification,
        String engineType,
        String condition,
        Integer powerHp,
        Integer euroStandard,
        String gearbox,
        String category,
        Integer engineCc,
        BigDecimal price,
        String vat,
        String currency,
        Integer mileageKm,
        String month,
        Integer year,
        String color,
        String region,
        String city,
        Integer rangeKm,
        BigDecimal batteryKwh,
        List<CarFeature> extras
) {
}
