package com.premiumcars.importing.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.Drivetrain;

class FeatureMapperTest {

    @Test
    void mapsAutowiniOptions() {
        assertThat(FeatureMapper.fromPhrases(List.of("Park Assist Sensor", "Full Auto Air Conditioner",
                "Heating Seat", "Power Seat", "Cooling Seat", "Cruise Control")))
                .containsExactlyInAnyOrder(CarFeature.PARKING_SENSORS, CarFeature.CLIMATE_CONTROL,
                        CarFeature.HEATED_SEATS, CarFeature.POWER_SEATS, CarFeature.VENTILATED_SEATS,
                        CarFeature.CRUISE_CONTROL);
        assertThat(FeatureMapper.fromPhrases(List.of("Smart Key", "Panorama Sunroof", "HID Headlamp",
                "Airbag(Side)", "Smart Cruise Control", "Electric Folding Side Mirror", "Navigation")))
                .containsExactlyInAnyOrder(CarFeature.KEYLESS_START, CarFeature.PANORAMIC_ROOF,
                        CarFeature.XENON_HEADLIGHTS, CarFeature.AIRBAGS_SIDE, CarFeature.CRUISE_CONTROL,
                        CarFeature.DISTANCE_CONTROL, CarFeature.POWER_MIRRORS, CarFeature.NAVIGATION);
    }

    @Test
    void mapsAutohomeEquipmentLines() {
        assertThat(FeatureMapper.fromPhrases(List.of(
                "Front/Rear Side Airbags: Front",
                "Front/Rear Curtain Airbags: Rear",
                "Front Seat Features: Heating",
                "Sunroof Type: Split Power Sunroof",
                "AC Temperature Control Method: Automatic Air Conditioning",
                "Power Steering Type: Electric power steering",
                "Low Beam Light Source: LED")))
                .containsExactlyInAnyOrder(CarFeature.AIRBAGS_SIDE, CarFeature.AIRBAGS_REAR,
                        CarFeature.HEATED_SEATS, CarFeature.PANORAMIC_ROOF, CarFeature.CLIMATE_CONTROL,
                        CarFeature.ELECTRIC_POWER_STEERING, CarFeature.LED_HEADLIGHTS);
    }

    @Test
    void equipmentWithoutAMobileBgCheckboxIsIgnored() {
        assertThat(FeatureMapper.fromPhrases(List.of("Memory Seat", "Electric Parking Brake",
                "Lane Departure Warning", "Voice Recognition Control System: Navigation",
                "Navigation Traffic Display", "Front Sensing Camera: Monocular x2"))).isEmpty();
    }

    @Test
    void featuresFromSpecs() {
        assertThat(FeatureMapper.fromSpecs(Drivetrain.AWD, 7, 5, true))
                .containsExactlyInAnyOrder(CarFeature.FOUR_BY_FOUR, CarFeature.SEVEN_SEATS, CarFeature.DOORS_4_5,
                        CarFeature.RIGHT_HAND_DRIVE);
        assertThat(FeatureMapper.fromSpecs(Drivetrain.FWD, 5, 3, false)).containsExactly(CarFeature.DOORS_2_3);
        assertThat(FeatureMapper.fromSpecs(null, null, null, null)).isEmpty();
    }

    @Test
    void steeringSide() {
        assertThat(FeatureMapper.rightHandDrive("RHD")).isTrue();
        assertThat(FeatureMapper.rightHandDrive("Right")).isTrue();
        assertThat(FeatureMapper.rightHandDrive("LHD")).isFalse();
        assertThat(FeatureMapper.rightHandDrive("Left")).isFalse();
        assertThat(FeatureMapper.rightHandDrive("-")).isNull();
    }

    @Test
    void euroStandard() {
        assertThat(FeatureMapper.euroStandard("China VI")).isEqualTo(6);
        assertThat(FeatureMapper.euroStandard("China VI b")).isEqualTo(6);
        assertThat(FeatureMapper.euroStandard("China V")).isEqualTo(5);
        assertThat(FeatureMapper.euroStandard("Euro 6d")).isEqualTo(6);
        assertThat(FeatureMapper.euroStandard("EURO IV")).isEqualTo(4);
        assertThat(FeatureMapper.euroStandard("国VI")).isEqualTo(6);
        assertThat(FeatureMapper.euroStandard("-")).isNull();
        assertThat(FeatureMapper.euroStandard(null)).isNull();
    }
}
