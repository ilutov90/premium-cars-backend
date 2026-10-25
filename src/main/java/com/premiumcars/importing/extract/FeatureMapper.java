package com.premiumcars.importing.extract;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.Drivetrain;

/**
 * Maps equipment found on the source sites to {@link CarFeature}s (the mobile.bg extras).
 * <p>
 * Both sites are first turned into "phrases": lines {@code "item"} or {@code "item: detail"}, one per piece of
 * equipment the car has, e.g. {@code "Front Seat Features: Heating"} (autohome) or {@code "Heating Seat"}
 * (autowini). Group names are left out on purpose: autohome's "Sunroof &amp; Glass" or "Air Conditioning &amp;
 * Refrigerator" would otherwise match every item of the group. guazi gives both kinds: its "Features" names and
 * its "Specifications" rows ({@code "Rear Curtain Airbags"}, {@code "External Audio Interface: Type-C"}). A feature is set when any of its patterns is found in a phrase. Only extras that a source can show
 * have rules; the rest (leasing, barter, taxi ...) are left to the admins.
 */
public final class FeatureMapper {

    private static final Map<CarFeature, Pattern> RULES = new LinkedHashMap<>();

    static {
        // Безопасност
        rule(CarFeature.GPS_TRACKING, "find my car|vehicle location|stolen vehicle tracking");
        rule(CarFeature.ADAPTIVE_HEADLIGHTS, "adaptive (front )?(lighting|headlight)|\\bafs\\b|cornering (light|lamp)"
                + "|steering assist(ed)? light");
        rule(CarFeature.ABS, "\\babs\\b|anti-lock");
        rule(CarFeature.AIRBAGS_FRONT, "driver/passenger airbags|(driver|passenger|front)( seat)? airbag"
                + "|airbag ?\\((driver|passenger|front)");
        rule(CarFeature.AIRBAGS_SIDE, "side airbags: front|curtain airbags: front|side airbag|curtain airbag"
                + "|airbag ?\\((side|curtain)");
        rule(CarFeature.AIRBAGS_REAR, "(side|curtain) airbags: rear|(?<!/)rear( seat)?( side| curtain)? airbag"
                + "|airbag ?\\(rear");
        rule(CarFeature.EBD, "brake force distribution|\\bebd\\b");
        rule(CarFeature.ESP, "electronic stability|\\besc\\b|\\besp\\b|\\bvdc\\b|\\bdsc\\b");
        rule(CarFeature.TIRE_PRESSURE_MONITORING, "tire pressure|tyre pressure|\\btpms\\b");
        rule(CarFeature.PARKING_SENSORS, "parking sensors?|park(ing)? assist sensor|parking distance"
                + "|(front|rear|parking) radar");
        rule(CarFeature.ISOFIX, "isofix");
        rule(CarFeature.TRACTION_CONTROL, "traction control|\\btcs\\b|\\basr\\b");
        rule(CarFeature.DISTANCE_CONTROL, "adaptive cruise|smart cruise|distance control");
        rule(CarFeature.HILL_DESCENT_CONTROL, "hill descent");

        // Комфорт
        rule(CarFeature.CAMERA, "driving assistance camera: (360|panoramic|rear|reversing)|rear ?view camera"
                + "|rear camera|reversing camera|backup camera|around ?view|360.degree|360 camera|panoramic camera");
        rule(CarFeature.CARPLAY_ANDROID_AUTO, "carplay|android auto");
        rule(CarFeature.START_STOP, "start/stop|start-stop|stop ?& ?go|\\bisg\\b");
        rule(CarFeature.BLUETOOTH, "bluetooth|hands-?free");
        rule(CarFeature.DVD_TV, "\\bdvd\\b|\\btv\\b|rear (seat )?(entertainment|lcd|screen)");
        rule(CarFeature.HEAD_UP_DISPLAY, "head[- ]?up|\\bhud\\b");
        rule(CarFeature.MANUAL_SHIFT_MODE, "paddle shift|manual shift mode|steptronic|tiptronic");
        rule(CarFeature.USB_AUX, "\\busb\\b|type-c|\\baux\\b");
        rule(CarFeature.POWER_TAILGATE, "power (tailgate|trunk|liftgate)|electric (tailgate|trunk)|smart trunk"
                + "|auto(matic)? trunk");
        rule(CarFeature.AIR_SUSPENSION, "air suspension");
        rule(CarFeature.KEYLESS_START, "keyless start|push.?(button )?start|button start|smart key");
        rule(CarFeature.DIFFERENTIAL_LOCK, "differential lock|locking differential|limited.slip");
        rule(CarFeature.TRIP_COMPUTER, "trip computer|on-?board computer");
        rule(CarFeature.LOW_RANGE, "low.range|low gear ratio");
        rule(CarFeature.VENTILATED_SEATS, "seat features: ventilation|ventilated seat|cooling seat|cooled seat"
                + "|seat ventilation");
        rule(CarFeature.LIGHT_SENSOR, "automatic headlights|auto(matic)? light|light sensor|auto headlight");
        rule(CarFeature.POWER_MIRRORS, "mirror features: power|power (folding )?(side )?mirror"
                + "|electric (folding )?(side )?mirror|electric (rearview|exterior) mirror");
        rule(CarFeature.POWER_WINDOWS, "power windows?|anti-?pinch window");
        rule(CarFeature.POWER_SEATS, "power-adjustable (driver|passenger|driver/passenger) seats|power seat"
                + "|electric seat|seat electric adjust");
        rule(CarFeature.ELECTRIC_POWER_STEERING, "electric power steering|\\bmdps\\b|\\beps\\b");
        rule(CarFeature.CLIMATE_CONTROL, "automatic air conditioning|auto(matic)? air ?con|full auto air"
                + "|climate control|dual-zone");
        rule(CarFeature.AIR_CONDITIONING, "manual air conditioning|air ?conditioner|\\ba/c\\b"
                + "|(rear|independent) air conditioning");
        rule(CarFeature.MULTIFUNCTION_STEERING_WHEEL, "multi-?function steering|steering (wheel )?remote");
        rule(CarFeature.NAVIGATION, "navigation system|^navigation$|^navigation \\(|gps navigation");
        rule(CarFeature.HEATED_STEERING_WHEEL, "heated steering|steering wheel heat");
        rule(CarFeature.HEATED_WINDSHIELD, "heated (front )?windshield|windshield heat|front windshield.*heat");
        rule(CarFeature.HEATED_SEATS, "seat features: heating|heat(ed|ing) seat|seat heat");
        rule(CarFeature.STEERING_WHEEL_ADJUSTMENT, "steering wheel adjustment|tilt steering|telescopic steering");
        rule(CarFeature.RAIN_SENSOR, "rain.sens");
        rule(CarFeature.POWER_STEERING, "(hydraulic|electro-hydraulic) power steering|^power steering$");
        rule(CarFeature.HEADLIGHT_WASHERS, "headlight (washer|cleaning)|headlamp (washer|cleaning)");
        rule(CarFeature.CRUISE_CONTROL, "cruise");
        rule(CarFeature.HEAT_PUMP, "heat pump");
        rule(CarFeature.COOLED_GLOVEBOX, "refrigerator|cool(ed|ing) glove");

        // Екстериор
        rule(CarFeature.LED_HEADLIGHTS, "beam light source: (led|matrix)|led head|full led");
        rule(CarFeature.XENON_HEADLIGHTS, "xenon|\\bhid\\b");
        rule(CarFeature.HALOGEN_HEADLIGHTS, "halogen");
        rule(CarFeature.ALLOY_WHEELS, "rim material: alumin|alloy (wheel|rim)|alumin(i)?um (wheel|rim)");
        rule(CarFeature.PANORAMIC_ROOF, "panoram(a|ic) ?(sun)?roof|sunroof type: split");
        rule(CarFeature.SUNROOF, "sunroof");
        rule(CarFeature.ROOF_RAILS, "roof (rack|rail)");
        rule(CarFeature.SPOILERS, "spoiler");
        rule(CarFeature.TOW_BAR, "tow ?(hitch|bar)|trailer hitch");

        // Защита
        rule(CarFeature.ALARM, "alarm");
        rule(CarFeature.CENTRAL_LOCKING, "central (door )?lock|(remote|power) (door )?lock|keyless entry");

        // Интериор
        rule(CarFeature.LEATHER_INTERIOR, "seat material: [^>]*leather|leather seat");
        rule(CarFeature.VELOUR_INTERIOR, "seat material: [^>]*(suede|alcantara|velour)|(suede|alcantara) seat");
    }

    private FeatureMapper() {
    }

    private static void rule(CarFeature feature, String regex) {
        RULES.put(feature, Pattern.compile(regex));
    }

    /** Features named by the phrases (any case). */
    public static Set<CarFeature> fromPhrases(Collection<String> phrases) {
        EnumSet<CarFeature> out = EnumSet.noneOf(CarFeature.class);
        for (String raw : phrases) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String phrase = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            RULES.forEach((feature, pattern) -> {
                if (pattern.matcher(phrase).find()) {
                    out.add(feature);
                }
            });
        }
        // a panoramic roof is not also a "шибедах"; automatic climate control is not also a plain "климатик"
        if (out.contains(CarFeature.PANORAMIC_ROOF)) {
            out.remove(CarFeature.SUNROOF);
        }
        if (out.contains(CarFeature.CLIMATE_CONTROL)) {
            out.remove(CarFeature.AIR_CONDITIONING);
        }
        if (out.contains(CarFeature.ELECTRIC_POWER_STEERING)) {
            out.remove(CarFeature.POWER_STEERING);
        }
        return out;
    }

    /**
     * Features that follow from fields we already have: all-wheel drive (4x4), 7 or more seats, the number of
     * doors and a right-hand steering wheel.
     *
     * @param rightHandDrive true/false when the source says, null when unknown
     */
    public static Set<CarFeature> fromSpecs(Drivetrain drivetrain, Integer seats, Integer doors,
                                            Boolean rightHandDrive) {
        EnumSet<CarFeature> out = EnumSet.noneOf(CarFeature.class);
        if (drivetrain == Drivetrain.AWD) {
            out.add(CarFeature.FOUR_BY_FOUR);
        }
        if (seats != null && seats >= 7) {
            out.add(CarFeature.SEVEN_SEATS);
        }
        if (doors != null) {
            out.add(doors <= 3 ? CarFeature.DOORS_2_3 : CarFeature.DOORS_4_5);
        }
        if (Boolean.TRUE.equals(rightHandDrive)) {
            out.add(CarFeature.RIGHT_HAND_DRIVE);
        }
        return out;
    }

    /** "Right", "RHD", "右舵" -> true; "Left", "LHD" -> false; anything else -> null. */
    public static Boolean rightHandDrive(String steering) {
        if (steering == null || steering.isBlank()) {
            return null;
        }
        String s = steering.toLowerCase(Locale.ROOT);
        if (s.contains("rhd") || s.contains("right") || s.contains("右")) {
            return true;
        }
        if (s.contains("lhd") || s.contains("left") || s.contains("左")) {
            return false;
        }
        return null;
    }

    /** "China VI", "国VI b", "Euro 6d", "EURO V" -> 6 / 5; null when no class 1-6 is found. */
    public static Integer euroStandard(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toUpperCase(Locale.ROOT);
        var arabic = Pattern.compile("(?:EURO|CHINA|国|NATIONAL)\\s*([1-6])(?![0-9])").matcher(t);
        if (arabic.find()) {
            return Integer.valueOf(arabic.group(1));
        }
        var roman = Pattern.compile("(?:EURO|CHINA|国|NATIONAL)\\s*(VI|V|IV|III|II|I)(?![A-Z])").matcher(t);
        if (roman.find()) {
            return switch (roman.group(1)) {
                case "I" -> 1;
                case "II" -> 2;
                case "III" -> 3;
                case "IV" -> 4;
                case "V" -> 5;
                default -> 6;
            };
        }
        return null;
    }
}
