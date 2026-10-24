package com.premiumcars.catalog.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Car extras, one per checkbox of the mobile.bg "Добави обява" form (category "Автомобили и Джипове"), in the
 * form's order. {@link #mobileBgLabel()} is the checkbox's exact value there (an integration constant, not UI
 * text: the frontend has its own labels in {@code format.ts}).
 * <p>
 * Stored by {@code name()} in {@code car.features}; never rename a constant without a migration.
 * Importers fill in what the source sites show (see {@code FeatureMapper}); the admins edit the rest.
 */
public enum CarFeature {

    // Безопасност
    GPS_TRACKING(Group.SAFETY, "GPS система за проследяване"),
    ADAPTIVE_HEADLIGHTS(Group.SAFETY, "Адаптивни предни светлини"),
    ABS(Group.SAFETY, "Антиблокираща система"),
    AIRBAGS_REAR(Group.SAFETY, "Въздушни възглавници - Задни"),
    AIRBAGS_FRONT(Group.SAFETY, "Въздушни възглавници - Предни"),
    AIRBAGS_SIDE(Group.SAFETY, "Въздушни възглавници - Странични"),
    EBD(Group.SAFETY, "Ел. разпределяне на спирачното усилие"),
    ESP(Group.SAFETY, "Електронна програма за стабилизиране"),
    TIRE_PRESSURE_MONITORING(Group.SAFETY, "Контрол на налягането на гумите"),
    PARKING_SENSORS(Group.SAFETY, "Парктроник"),
    ISOFIX(Group.SAFETY, "Система ISOFIX"),
    DYNAMIC_STABILITY(Group.SAFETY, "Система за динамична устойчивост"),
    TRACTION_CONTROL(Group.SAFETY, "Система за защита от пробуксуване"),
    DISTANCE_CONTROL(Group.SAFETY, "Система за контрол на дистанцията"),
    HILL_DESCENT_CONTROL(Group.SAFETY, "Система за контрол на спускането"),

    // Комфорт
    CAMERA(Group.COMFORT, "360 camera \\ Задна камера"),
    CARPLAY_ANDROID_AUTO(Group.COMFORT, "Apple CarPlay \\ Android Auto"),
    START_STOP(Group.COMFORT, "Auto Start Stop function"),
    BLUETOOTH(Group.COMFORT, "Bluetooth \\ handsfree система"),
    DVD_TV(Group.COMFORT, "DVD, TV"),
    HEAD_UP_DISPLAY(Group.COMFORT, "Head up display"),
    MANUAL_SHIFT_MODE(Group.COMFORT, "Steptronic, Tiptronic"),
    USB_AUX(Group.COMFORT, "USB, audio\\video, IN\\AUX изводи"),
    POWER_TAILGATE(Group.COMFORT, "Автоматично затваряне на багажника"),
    AIR_SUSPENSION(Group.COMFORT, "Адаптивно въздушно окачване"),
    KEYLESS_START(Group.COMFORT, "Безключово палене"),
    DIFFERENTIAL_LOCK(Group.COMFORT, "Блокаж на диференциала"),
    TRIP_COMPUTER(Group.COMFORT, "Бордкомпютър"),
    LOW_RANGE(Group.COMFORT, "Бързи \\ бавни скорости"),
    VENTILATED_SEATS(Group.COMFORT, "Вентилация на седалките"),
    LIGHT_SENSOR(Group.COMFORT, "Датчик за светлина"),
    POWER_MIRRORS(Group.COMFORT, "Ел. Огледала"),
    POWER_WINDOWS(Group.COMFORT, "Ел. Стъкла"),
    POWER_SEATS(Group.COMFORT, "Ел. регулиране на седалките"),
    ELECTRIC_POWER_STEERING(Group.COMFORT, "Ел. усилвател на волана"),
    AIR_CONDITIONING(Group.COMFORT, "Климатик"),
    CLIMATE_CONTROL(Group.COMFORT, "Климатроник"),
    MULTIFUNCTION_STEERING_WHEEL(Group.COMFORT, "Мултифункционален волан"),
    NAVIGATION(Group.COMFORT, "Навигация"),
    HEATED_STEERING_WHEEL(Group.COMFORT, "Отопление на волана"),
    HEATER(Group.COMFORT, "Печка"),
    HEATED_WINDSHIELD(Group.COMFORT, "Подгряване на предното стъкло"),
    HEATED_SEATS(Group.COMFORT, "Подгряване на седалките"),
    STEERING_WHEEL_ADJUSTMENT(Group.COMFORT, "Регулиране на волана"),
    RAIN_SENSOR(Group.COMFORT, "Сензор за дъжд"),
    POWER_STEERING(Group.COMFORT, "Серво усилвател на волана"),
    HEADLIGHT_WASHERS(Group.COMFORT, "Система за измиване на фаровете"),
    CRUISE_CONTROL(Group.COMFORT, "Система за контрол на скоростта (автопилот)"),
    HEAT_PUMP(Group.COMFORT, "Термопомпа"),
    COOLED_GLOVEBOX(Group.COMFORT, "Хладилна жабка"),

    // Други
    FOUR_BY_FOUR(Group.OTHER, "4x4"),
    SEVEN_SEATS(Group.OTHER, "7 места"),
    BUY_BACK(Group.OTHER, "Buy back"),
    BARTER(Group.OTHER, "Бартер"),
    LPG(Group.OTHER, "Газова уредба"),
    LONG_WHEELBASE(Group.OTHER, "Дълга база"),
    RESERVED_OR_SOLD(Group.OTHER, "Капариран\\Продаден"),
    CRASHED(Group.OTHER, "Катастрофирал"),
    SHORT_WHEELBASE(Group.OTHER, "Къса база"),
    LEASING(Group.OTHER, "Лизинг"),
    CNG(Group.OTHER, "Метанова уредба"),
    FOR_PARTS(Group.OTHER, "На части"),
    FULLY_SERVICED(Group.OTHER, "Напълно обслужен"),
    NEW_IMPORT(Group.OTHER, "Нов внос"),
    REGISTERED(Group.OTHER, "С регистрация"),
    SERVICE_BOOK(Group.OTHER, "Сервизна книжка"),
    TUNING(Group.OTHER, "Тунинг"),

    // Екстериор
    DOORS_2_3(Group.EXTERIOR, "2(3) Врати"),
    DOORS_4_5(Group.EXTERIOR, "4(5) Врати"),
    LED_HEADLIGHTS(Group.EXTERIOR, "LED фарове"),
    XENON_HEADLIGHTS(Group.EXTERIOR, "Ксенонови фарове"),
    ALLOY_WHEELS(Group.EXTERIOR, "Лети джанти"),
    METALLIC_PAINT(Group.EXTERIOR, "Металик"),
    PANORAMIC_ROOF(Group.EXTERIOR, "Панорамен люк"),
    ROOF_RAILS(Group.EXTERIOR, "Рейлинг на покрива"),
    SPOILERS(Group.EXTERIOR, "Спойлери"),
    TOW_BAR(Group.EXTERIOR, "Теглич"),
    HALOGEN_HEADLIGHTS(Group.EXTERIOR, "Халогенни фарове"),
    SUNROOF(Group.EXTERIOR, "Шибедах"),

    // Защита
    OFFROAD_PACKAGE(Group.PROTECTION, "OFFROAD пакет"),
    ALARM(Group.PROTECTION, "Аларма"),
    ARMORED(Group.PROTECTION, "Брониран"),
    CASCO(Group.PROTECTION, "Каско"),
    WINCH(Group.PROTECTION, "Лебедка"),
    CENTRAL_LOCKING(Group.PROTECTION, "Централно заключване"),

    // Интериор
    VELOUR_INTERIOR(Group.INTERIOR, "Велурен салон"),
    RIGHT_HAND_DRIVE(Group.INTERIOR, "Десен волан"),
    LEATHER_INTERIOR(Group.INTERIOR, "Кожен салон"),
    LIGHT_INTERIOR(Group.INTERIOR, "Светъл салон"),

    // Специализирани
    TAXI(Group.SPECIAL, "TAXI"),
    DISABLED_ACCESS(Group.SPECIAL, "За хора с увреждания"),
    HEARSE(Group.SPECIAL, "Катафалка"),
    AMBULANCE(Group.SPECIAL, "Линейка"),
    DRIVING_SCHOOL(Group.SPECIAL, "Учебен"),
    REFRIGERATED(Group.SPECIAL, "Хладилен"),
    N1_HOMOLOGATION(Group.SPECIAL, "Хомологация N1");

    /** The groups of the mobile.bg form, in its order. */
    public enum Group {
        SAFETY, COMFORT, OTHER, EXTERIOR, PROTECTION, INTERIOR, SPECIAL
    }

    private static final Map<String, CarFeature> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final Group group;
    private final String mobileBgLabel;

    CarFeature(Group group, String mobileBgLabel) {
        this.group = group;
        this.mobileBgLabel = mobileBgLabel;
    }

    public Group group() {
        return group;
    }

    public String mobileBgLabel() {
        return mobileBgLabel;
    }

    /** @return null for an unknown name (e.g. a constant removed after it was stored) */
    public static CarFeature byName(String name) {
        return name == null ? null : BY_NAME.get(name);
    }
}
