package com.premiumcars.carsbg;

import java.math.BigDecimal;
import java.util.List;

/**
 * cars.bg's "Публикувай обява" form filled in from a car, in cars.bg's own ids (the form's field names in the
 * comments), for the admin panel to show and edit before the extension types it into cars.bg. Null = not known,
 * the admin chooses. The description and the photos come from the admin panel.
 *
 * @param engine        "Двигател/модификация" ({@code engine}, at most 15 characters): our model's name when
 *                      cars.bg's differs ("Yuan UP" posted as "Atto 2"), else the trim
 * @param fullName      make, model and trim as we name them, for the description's first line
 * @param categoryId    {@code categoryId}: 1 sedan, 2 hatchback, 4 wagon, 5 coupe, 6 cabrio, 7 SUV, 8 pickup, 9 van
 * @param fuelId        {@code fuelId}: 1 petrol, 2 diesel, 3 LPG, 4 CNG, 6 hybrid (also plug-in), 7 electric
 * @param gearId        {@code gearId}: 1 manual, 2 automatic
 * @param doorId        {@code doorId}: 1 = 2/3 doors, 2 = 4/5 doors
 * @param currencyId    {@code currencyId}: 3 = EUR
 * @param stateId       {@code stateId}: 1 used
 * @param conditionId   {@code conditionId}: 1 good, 2 damaged, 3 for parts
 * @param inBulgaria    {@code is_inbg}: false = abroad (the car is bought on order)
 * @param usageId       {@code usageId}: 1 = a new import
 * @param extras        the ids of the {@code e[]} checkboxes
 */
public record CarsBgDraft(
        String brandId,
        String brandName,
        String modelId,
        String modelName,
        String engine,
        String fullName,
        Integer categoryId,
        Integer fuelId,
        Integer gearId,
        Integer power,
        Integer cubature,
        Integer productionYear,
        Integer monthId,
        Integer run,
        Integer doorId,
        Integer colorId,
        boolean metallic,
        Integer euroId,
        BigDecimal price,
        int currencyId,
        int stateId,
        int conditionId,
        boolean inBulgaria,
        int usageId,
        boolean leasing,
        boolean barter,
        List<Integer> extras
) {
}
