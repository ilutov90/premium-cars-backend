package com.premiumcars.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.premiumcars.config.AppProperties;

class RoutingImageStorageTest {

    @TempDir
    Path dir;

    private RoutingImageStorage storage(AppProperties.R2 r2) {
        var props = new AppProperties(null, null, new AppProperties.Media(dir.toString(), "/media/", r2), null, null,
                null);
        return new RoutingImageStorage(new LocalImageStorage(props), props);
    }

    @Test
    void withoutR2PhotosStayLocal() throws Exception {
        RoutingImageStorage storage = storage(null);
        assertThat(storage.r2()).isEmpty();

        String path = storage.store("cars/7", new byte[] {1, 2, 3}, "jpg");
        assertThat(path).startsWith("cars/7/").endsWith(".jpg");
        assertThat(RoutingImageStorage.inR2(path)).isFalse();
        assertThat(storage.publicUrl(path)).isEqualTo("/media/" + path);
        assertThat(storage.read(path)).containsExactly(1, 2, 3);
        storage.delete(path);
        assertThat(Files.exists(dir.resolve(path))).isFalse();

        // a photo already moved to R2 while R2 is off is a configuration error, said plainly
        assertThatThrownBy(() -> storage.read("r2:cars/7/a.jpg")).hasMessageContaining("not configured");
    }

    @Test
    void r2PathsAndUrls() {
        RoutingImageStorage storage = storage(new AppProperties.R2("https://acc.r2.cloudflarestorage.com/",
                "premium-cars-photos", "https://img.premiumcarsimport.bg", "key", "secret"));
        assertThat(storage.r2()).isPresent();
        assertThat(RoutingImageStorage.r2Path("cars/7/a.jpg")).isEqualTo("r2:cars/7/a.jpg");
        assertThat(storage.publicUrl("r2:cars/7/a.jpg")).isEqualTo("https://img.premiumcarsimport.bg/cars/7/a.jpg");
        assertThat(storage.publicUrl("cars/7/b.jpg")).isEqualTo("/media/cars/7/b.jpg"); // not moved yet
        assertThat(new AppProperties.R2(null, "b", "u", "k", "s").configured()).isFalse();
    }

    @Test
    void keysAreEncodedAsS3SignsThem() {
        assertThat(R2ImageStorage.encode("a b*~.jpg")).isEqualTo("a%20b%2A~.jpg");
        assertThat(R2ImageStorage.contentType("WEBP")).isEqualTo("image/webp");
        assertThat(R2ImageStorage.contentType("jpg")).isEqualTo("image/jpeg");
    }
}
