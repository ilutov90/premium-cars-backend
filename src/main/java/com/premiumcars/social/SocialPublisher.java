package com.premiumcars.social;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.config.AppProperties;
import com.premiumcars.media.ImageStorage;

/**
 * Publishes a car to the Facebook page / Instagram account: the admin's text (generated in the admin panel in the
 * posting script's format, editable) plus the car's photos in their order. Not transactional: it makes slow network
 * calls and only records the result at the end.
 */
@Service
public class SocialPublisher {

    private static final Logger log = LoggerFactory.getLogger(SocialPublisher.class);

    /** Instagram's caption limit. */
    static final int INSTAGRAM_CAPTION_MAX = 2200;

    public record Status(boolean facebook, boolean instagram) {
    }

    private record Photo(byte[] bytes, String extension) {
    }

    private final AppProperties.Social cfg;
    private final CarRepository cars;
    private final CarImageRepository images;
    private final ImageStorage storage;
    private final MetaGraphClient graph;
    private final ImgbbUploader imgbb;
    private final SocialPostRepository posts;

    public SocialPublisher(AppProperties props, CarRepository cars, CarImageRepository images, ImageStorage storage,
                           MetaGraphClient graph, ImgbbUploader imgbb, SocialPostRepository posts) {
        this.cfg = props.social();
        this.cars = cars;
        this.images = images;
        this.storage = storage;
        this.graph = graph;
        this.imgbb = imgbb;
        this.posts = posts;
    }

    public Status status() {
        return new Status(cfg.facebookConfigured(), cfg.instagramConfigured());
    }

    public List<SocialPost> posts(long carId) {
        requireCar(carId);
        return posts.findByCarId(carId);
    }

    public SocialPost publish(long carId, SocialNetwork network, String text) {
        requireCar(carId);
        List<Photo> photos = photos(carId);
        if (photos.isEmpty()) {
            throw new BadRequestException("The car has no photos to publish");
        }
        return switch (network) {
            case FACEBOOK -> publishToFacebook(carId, text, photos);
            case INSTAGRAM -> publishToInstagram(carId, text, photos);
        };
    }

    private SocialPost publishToFacebook(long carId, String text, List<Photo> photos) {
        if (!cfg.facebookConfigured()) {
            throw new BadRequestException("Facebook is not configured: set FB_PAGE_ID and FB_ACCESS_TOKEN");
        }
        List<String> photoIds = new ArrayList<>();
        for (Photo p : photos) {
            photoIds.add(graph.uploadUnpublishedPhoto(p.bytes(), p.extension()));
        }
        String postId = graph.createPagePost(text, photoIds);
        String link = graph.facebookPermalink(postId);
        log.info("Published car {} to Facebook: {} ({} photos)", carId, link, photoIds.size());
        return posts.insert(carId, SocialNetwork.FACEBOOK, postId, link, photoIds.size());
    }

    private SocialPost publishToInstagram(long carId, String text, List<Photo> photos) {
        if (!cfg.instagramConfigured()) {
            throw new BadRequestException("Instagram is not configured: set IG_USER_ID, FB_ACCESS_TOKEN and IMGBB_API_KEY");
        }
        if (text.length() > INSTAGRAM_CAPTION_MAX) {
            throw new BadRequestException("Instagram captions are limited to " + INSTAGRAM_CAPTION_MAX
                    + " characters (this one has " + text.length() + ")");
        }
        // Instagram accepts JPEG only.
        List<String> urls = new ArrayList<>();
        for (Photo p : photos) {
            byte[] jpeg = toJpeg(p);
            if (jpeg != null) {
                urls.add(imgbb.upload(jpeg));
            }
        }
        if (urls.isEmpty()) {
            throw new BadRequestException("None of the photos could be converted to JPEG for Instagram");
        }
        String container;
        if (urls.size() == 1) {
            container = graph.instagramPhotoContainer(urls.getFirst(), text);
        } else {
            List<String> children = new ArrayList<>();
            for (String url : urls) {
                children.add(graph.instagramPhotoContainer(url, null));
            }
            for (String child : children) {
                graph.waitUntilReady(child);
            }
            container = graph.instagramCarousel(children, text);
        }
        graph.waitUntilReady(container);
        String mediaId = graph.instagramPublish(container);
        String link = graph.instagramPermalink(mediaId);
        log.info("Published car {} to Instagram: {} ({} photos)", carId, link, urls.size());
        return posts.insert(carId, SocialNetwork.INSTAGRAM, mediaId, link, urls.size());
    }

    private List<Photo> photos(long carId) {
        List<Photo> out = new ArrayList<>();
        for (CarImage image : images.findByCarId(carId)) {
            if (out.size() >= cfg.maxPhotos()) {
                break;
            }
            String path = image.storagePath();
            String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            out.add(new Photo(storage.read(path), ext));
        }
        return out;
    }

    /** JPEG bytes as they are; PNG/GIF re-encoded; formats Java cannot read (webp, avif) skipped. */
    static byte[] toJpeg(Photo p) {
        if (p.extension().equals("jpg") || p.extension().equals("jpeg")) {
            return p.bytes();
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(p.bytes()));
            if (source == null) {
                return null;
            }
            BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
            g.dispose();
            var out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void requireCar(long carId) {
        if (cars.findById(carId).isEmpty()) {
            throw new NotFoundException("Car not found: " + carId);
        }
    }
}
