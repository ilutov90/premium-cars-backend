package com.premiumcars.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WltpRangeTest {

    @Test
    void testKm_CltcRange_IsTimesPointEightTwoRoundedToFive() {
        assertThat(WltpRange.km(700, "CLTC")).isEqualTo(575);
        assertThat(WltpRange.km(520, "cltc")).isEqualTo(425);
        assertThat(WltpRange.km(605, " CLTC ")).isEqualTo(495);
    }

    @Test
    void testKm_WltpRange_StaysTheSame() {
        assertThat(WltpRange.km(412, "WLTP")).isEqualTo(412);
    }

    @Test
    void testKm_OtherStandardOrNoRange_IsNull() {
        assertThat(WltpRange.km(605, "NEDC")).isNull();
        assertThat(WltpRange.km(605, null)).isNull();
        assertThat(WltpRange.km(null, "CLTC")).isNull();
    }

    @Test
    void testWltpRangeKm_SpecsAsJson_IsWrittenButNeverRead() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        CarSpecs specs = CarSpecs.builder().title("BYD Seal").electricRangeKm(700).rangeStandard("CLTC").build();

        String written = json.writeValueAsString(specs);
        CarSpecs read = json.readValue(written.replace("\"wltpRangeKm\":575", "\"wltpRangeKm\":1"), CarSpecs.class);

        assertThat(json.readTree(written).path("wltpRangeKm").asInt()).isEqualTo(575);
        assertThat(read.wltpRangeKm()).isEqualTo(575);
    }
}
