package com.premiumcars.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.premiumcars.config.AppProperties;

@Component
public class LocalImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalImageStorage.class);

    private final Path root;
    private final String baseUrl;

    public LocalImageStorage(AppProperties props) {
        this.root = Path.of(props.media().dir()).toAbsolutePath().normalize();
        String url = props.media().baseUrl();
        this.baseUrl = url.endsWith("/") ? url : url + "/";
    }

    @Override
    public String store(String folder, byte[] bytes, String extension) {
        String relative = folder + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return relative;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store image " + relative, e);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(resolve(storagePath));
        } catch (IOException e) {
            log.warn("Could not delete image {}", storagePath, e);
        }
    }

    @Override
    public byte[] read(String storagePath) {
        try {
            return Files.readAllBytes(resolve(storagePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image " + storagePath, e);
        }
    }

    @Override
    public String publicUrl(String storagePath) {
        return baseUrl + storagePath;
    }

    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage path: " + relative);
        }
        return path;
    }
}
