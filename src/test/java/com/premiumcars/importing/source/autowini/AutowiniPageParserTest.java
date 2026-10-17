package com.premiumcars.importing.source.autowini;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.source.autowini.AutowiniClient.Page;
import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;

class AutowiniPageParserTest {

    private static String page(String name) throws IOException {
        try (InputStream in = AutowiniPageParserTest.class.getResourceAsStream("/pages/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsListing() throws IOException {
        ParsedPage page = AutowiniPageParser.parse(page("autowini-IC4960158.html"), "IC4960158");
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        assertThat(page.itemId()).isEqualTo("IC4960158");
        assertThat(page.fields()).containsEntry("location", "S.Korea");
        assertThat(page.price()).isEqualByComparingTo("9250");
        assertThat(page.currency()).isEqualTo("USD");

        assertThat(specs.title()).isEqualTo("Audi Q7 (4M) 45 TDI Quattro Sport");
        assertThat(specs.make()).isEqualTo("Audi");
        assertThat(specs.model()).isEqualTo("Q7");
        assertThat(specs.trim()).isEqualTo("(4M) 45 TDI Quattro Sport");
        assertThat(specs.year()).isEqualTo(2016);
        assertThat(specs.mileageKm()).isEqualTo(258_850);
        assertThat(specs.engineCc()).isEqualTo(2967);
        assertThat(specs.fuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(specs.transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(specs.bodyType()).isEqualTo("SUV");
        assertThat(specs.color()).isEqualTo("Charcoal");
    }

    @Test
    void collectsLargePhotosOnce() throws IOException {
        ParsedPage page = AutowiniPageParser.parse(page("autowini-IC4960158.html"), "IC4960158");

        assertThat(page.images()).containsExactly(
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/7050ec7f-97d7-4524-8c47-9366bf201ef5_1024.jpg",
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/5361c6c9-3d70-4fd6-ab28-62a77e0c647f_1024.jpg",
                "https://imagebox.autowini.com/upload/U2021091190104/car/CI202604040004766237/0f3c2a9e-new-style-photo.jpeg");
        assertThat(AutowiniPageParser.imageCandidates(page.images().get(1))).hasSize(3)
                .last().asString().endsWith("5361c6c9-3d70-4fd6-ab28-62a77e0c647f.jpg");
    }

    @Test
    void urls() {
        assertThat(AutowiniPageParser.itemId("https://www.autowini.com/Cars/ic4960158/cars-detail")).isEqualTo("IC4960158");
        assertThat(AutowiniPageParser.mobileUrl("IC4960158")).isEqualTo("https://m.autowini.com/items/IC4960158");
    }

    @Test
    void recognisesKeycloakPages() {
        assertThat(AutowiniClient.looksLikeKeycloak(new Page(200,
                "https://member-auth.tradewini.com/realms/tradewini/protocol/openid-connect/auth?client_id=x",
                "<form id=\"kc-form-login\" action=\"https://member-auth.tradewini.com/login-actions/authenticate\">")))
                .isTrue();
        assertThat(AutowiniClient.looksLikeKeycloak(new Page(200, "https://www.autowini.com/", "<html>home</html>")))
                .isFalse();
    }
}
