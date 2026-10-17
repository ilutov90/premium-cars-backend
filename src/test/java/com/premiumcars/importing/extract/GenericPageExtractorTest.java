package com.premiumcars.importing.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.importing.ImportedCar;

/**
 * The HTML here is made up to exercise each strategy. When a real source page fails to import, save it
 * (browser: "Save page as… HTML only") under src/test/resources/pages and add a test for it here.
 */
class GenericPageExtractorTest {

    private final GenericPageExtractor extractor = new GenericPageExtractor(new ObjectMapper());
    private final ExtractionHints hints = new ExtractionHints("USD",
            url -> url.contains("autoimg.cn") && !url.contains("logo"), 10);

    @Test
    void readsNextJsPageData() {
        String html = """
                <html><head><title>Audi Audi A3 2021 Sportback - Autohome</title>
                <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"detail":{
                    "carName":"Audi A3 2021 Sportback 35 TFSI",
                    "brandName":"Audi","seriesName":"A3",
                    "firstRegYear":"2021-05","mileage":"3.5","usdPrice":"14,980",
                    "fuelType":"Gasoline","gearbox":"7-speed DCT","color":"Black",
                    "picList":["https:\\/\\/car2.autoimg.cn\\/a\\/1.jpg","//car3.autoimg.cn/a/2.png.webp"]},
                  "logo":"https://x.autoimg.cn/logo.png"}}}
                </script></head><body><h1>Audi A3</h1></body></html>
                """;

        ImportedCar car = extractor.extract(Jsoup.parse(html), hints);

        assertThat(car.specs().title()).isEqualTo("Audi A3 2021 Sportback 35 TFSI");
        assertThat(car.specs().make()).isEqualTo("Audi");
        assertThat(car.specs().model()).isEqualTo("A3");
        assertThat(car.specs().year()).isEqualTo(2021);
        assertThat(car.specs().mileageKm()).isEqualTo(35_000); // "3.5" = 3.5 x 10,000 km on che168
        assertThat(car.price()).isEqualByComparingTo("14980");
        assertThat(car.currency()).isEqualTo("USD");
        assertThat(car.specs().fuelType()).isEqualTo(FuelType.PETROL);
        assertThat(car.specs().transmission()).isEqualTo(Transmission.AUTOMATIC);
        assertThat(car.imageUrls()).containsExactly(
                "https://car2.autoimg.cn/a/1.jpg", "https://car3.autoimg.cn/a/2.png.webp");
    }

    @Test
    void readsJsonLdBeforeOtherSources() {
        String html = """
                <html><head>
                <meta property="og:title" content="Something else">
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"Car","name":"Kia Sorento 2.2 CRDi",
                 "brand":{"@type":"Brand","name":"Kia"},"model":"Sorento","vehicleModelDate":"2020",
                 "mileageFromOdometer":{"value":"76000","unitCode":"KMT"},"fuelType":"Diesel",
                 "offers":{"price":"16200","priceCurrency":"USD"},
                 "image":["https://img.autoimg.cn/s/1.jpg"]}
                </script></head><body></body></html>
                """;

        ImportedCar car = extractor.extract(Jsoup.parse(html), hints);

        assertThat(car.specs().title()).isEqualTo("Kia Sorento 2.2 CRDi");
        assertThat(car.specs().make()).isEqualTo("Kia");
        assertThat(car.specs().year()).isEqualTo(2020);
        assertThat(car.specs().mileageKm()).isEqualTo(76_000);
        assertThat(car.specs().fuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(car.price()).isEqualByComparingTo("16200");
        assertThat(car.imageUrls()).containsExactly("https://img.autoimg.cn/s/1.jpg");
    }

    @Test
    void fallsBackToOpenGraphAndText() {
        String html = """
                <html><head>
                <meta property="og:title" content="BYD Seal 2023 650 Premium | Autohome">
                <meta property="og:image" content="https://car2.autoimg.cn/x/seal.jpg">
                </head><body>
                <p>Price: US$ 19,800</p><p>Mileage 21,000 km</p>
                <dl><dt>Battery:</dt><dd>82.5 kWh</dd></dl>
                </body></html>
                """;

        ImportedCar car = extractor.extract(Jsoup.parse(html), hints);

        assertThat(car.specs().title()).isEqualTo("BYD Seal 2023 650 Premium");
        assertThat(car.specs().make()).isEqualTo("BYD");
        assertThat(car.specs().model()).isEqualTo("Seal");
        assertThat(car.specs().year()).isEqualTo(2023);
        assertThat(car.price()).isEqualByComparingTo("19800");
        assertThat(car.specs().mileageKm()).isEqualTo(21_000);
        assertThat(car.specs().extraSpecs()).containsEntry("Battery", "82.5 kWh");
        assertThat(car.imageUrls()).containsExactly("https://car2.autoimg.cn/x/seal.jpg");
    }

    @Test
    void parsesChineseUnits() {
        assertThat(ValueParsers.parseNumber("12.5万")).isEqualByComparingTo("125000");
        assertThat(ValueParsers.parseMileageKm("3.2万公里")).isEqualTo(32_000);
        assertThat(ValueParsers.parseMileageKm("12,000 miles")).isEqualTo(19_312);
    }
}
