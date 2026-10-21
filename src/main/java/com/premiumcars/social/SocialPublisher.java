package com.premiumcars.social;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * Publishes a car to the Facebook page / Instagram account / Viber channel: the admin's text (generated in the admin panel in the
 * posting script's format, editable) plus the car's photos in their order. Not transactional: it makes slow network
 * calls and only records the result at the end.
 */
@Service
public class SocialPublisher {

    private static final Logger log = LoggerFactory.getLogger(SocialPublisher.class);

    /** Instagram's caption limit. */
    static final int INSTAGRAM_CAPTION_MAX = 2200;

    /** Viber's limit for a text message. */
    static final int VIBER_TEXT_MAX = 7000;

    /** Viber shows photos up to 1 MB on every platform; bigger ones are scaled down. */
    static final int VIBER_PHOTO_MAX_BYTES = 1_000_000;

    /** Most photos the admin may pick for one post (Instagram carousels take at most 10). */
    public static final int MAX_SELECTED_PHOTOS = 10;


    public record Status(boolean facebook, boolean instagram, boolean viber) {
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
    private final ViberChannelClient viber;

    public SocialPublisher(AppProperties props, CarRepository cars, CarImageRepository images, ImageStorage storage,
                           MetaGraphClient graph, ImgbbUploader imgbb, SocialPostRepository posts,
                           ViberChannelClient viber) {
        this.cfg = props.social();
        this.cars = cars;
        this.images = images;
        this.storage = storage;
        this.graph = graph;
        this.imgbb = imgbb;
        this.posts = posts;
        this.viber = viber;
    }

    public Status status() {
        return new Status(cfg.facebookConfigured(), cfg.instagramConfigured(), cfg.viberConfigured());
    }

    /**
     * Registers the site's Viber callback ({@code siteUrl} + {@link ViberWebhookController#PATH}). Viber checks the
     * URL right away, so the site must be reachable from the internet over HTTPS (a tunnel works for testing).
     *
     * @return the registered callback URL
     */
    public String connectViber(String siteUrl) {
        if (!cfg.viberConfigured()) {
            throw new BadRequestException("Viber is not configured: set VIBER_CHANNEL_TOKEN and IMGBB_API_KEY");
        }
        URI uri;
        try {
            uri = URI.create(siteUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Not a valid address: " + siteUrl);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new BadRequestException("Viber needs a public https:// address of the site");
        }
        String callback = "https://" + uri.getRawAuthority() + ViberWebhookController.PATH;
        viber.setWebhook(callback);
        log.info("Viber channel connected, callback {}", callback);
        return callback;
    }

    public List<SocialPost> posts(long carId) {
        requireCar(carId);
        return posts.findByCarId(carId);
    }

    /**
     * The checks {@link #publish} makes before any network call: the car, the network's configuration, the text
     * length and the chosen photos. Used when a post is scheduled, so mistakes show up right away.
     */
    public void validate(long carId, SocialNetwork network, String text, List<Long> imageIds) {
        requireCar(carId);
        requireConfigured(network);
        requireTextFits(network, text);
        if (imageIds != null) {
            if (imageIds.isEmpty() || imageIds.size() > MAX_SELECTED_PHOTOS) {
                throw new BadRequestException("Pick 1 to " + MAX_SELECTED_PHOTOS + " photos");
            }
            var ids = images.findByCarId(carId).stream().map(CarImage::id).toList();
            for (Long id : imageIds) {
                if (!ids.contains(id)) {
                    throw new BadRequestException("Photo " + id + " is not a photo of car " + carId);
                }
            }
        } else if (images.findByCarId(carId).isEmpty()) {
            throw new BadRequestException("The car has no photos to publish");
        }
    }

    private void requireConfigured(SocialNetwork network) {
        switch (network) {
            case FACEBOOK -> {
                if (!cfg.facebookConfigured()) {
                    throw new BadRequestException("Facebook is not configured: set FB_PAGE_ID and FB_ACCESS_TOKEN");
                }
            }
            case INSTAGRAM -> {
                if (!cfg.instagramConfigured()) {
                    throw new BadRequestException(
                            "Instagram is not configured: set IG_USER_ID, FB_ACCESS_TOKEN and IMGBB_API_KEY");
                }
            }
            case VIBER -> {
                if (!cfg.viberConfigured()) {
                    throw new BadRequestException("Viber is not configured: set VIBER_CHANNEL_TOKEN and IMGBB_API_KEY");
                }
            }
        }
    }

    private static void requireTextFits(SocialNetwork network, String text) {
        int max = switch (network) {
            case INSTAGRAM -> INSTAGRAM_CAPTION_MAX;
            case VIBER -> VIBER_TEXT_MAX;
            case FACEBOOK -> Integer.MAX_VALUE;
        };
        if (text == null || text.isBlank()) {
            throw new BadRequestException("The post text is empty");
        }
        if (text.length() > max) {
            String name = network == SocialNetwork.INSTAGRAM ? "Instagram captions" : "Viber messages";
            throw new BadRequestException(name + " are limited to " + max + " characters (this one has "
                    + text.length() + ")");
        }
    }

    public SocialPost publish(long carId, SocialNetwork network, String text) {
        return publish(carId, network, text, null);
    }

    /**
     * @param imageIds the car's photos to post, in this order (at most {@link #MAX_SELECTED_PHOTOS}); null = the
     *                 first ones of the gallery ({@code max-photos}, for Viber {@code viber-max-photos})
     */
    public SocialPost publish(long carId, SocialNetwork network, String text, List<Long> imageIds) {
        requireCar(carId);
        List<Photo> photos;
        if (imageIds == null) {
            photos = photos(carId, network == SocialNetwork.VIBER ? cfg.viberMaxPhotos() : cfg.maxPhotos());
        } else {
            photos = selectedPhotos(carId, imageIds);
        }
        if (photos.isEmpty()) {
            throw new BadRequestException("The car has no photos to publish");
        }
        return switch (network) {
            case FACEBOOK -> publishToFacebook(carId, text, photos);
            case INSTAGRAM -> publishToInstagram(carId, text, photos);
            case VIBER -> publishToViber(carId, text, photos);
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

    /** Viber channels have no albums: each photo is a message, then the text as the last (newest) message. */
    private SocialPost publishToViber(long carId, String text, List<Photo> photos) {
        if (!cfg.viberConfigured()) {
            throw new BadRequestException("Viber is not configured: set VIBER_CHANNEL_TOKEN and IMGBB_API_KEY");
        }
        if (text.length() > VIBER_TEXT_MAX) {
            throw new BadRequestException("Viber messages are limited to " + VIBER_TEXT_MAX
                    + " characters (this one has " + text.length() + ")");
        }
        String from = viber.superadminId();
        List<String> urls = new ArrayList<>();
        for (Photo p : photos) {
            byte[] jpeg = toJpeg(p);
            if (jpeg != null) {
                urls.add(imgbb.upload(fitWithin(jpeg, VIBER_PHOTO_MAX_BYTES)));
            }
        }
        if (urls.isEmpty()) {
            throw new BadRequestException("None of the photos could be converted to JPEG for Viber");
        }
        for (String url : urls) {
            viber.postPicture(from, url);
        }
        String messageToken = viber.postText(from, text);
        log.info("Published car {} to Viber ({} photos)", carId, urls.size());
        return posts.insert(carId, SocialNetwork.VIBER, messageToken, null, urls.size());
    }

    /** The chosen photos in the chosen order; each must belong to the car. */
    private List<Photo> selectedPhotos(long carId, List<Long> imageIds) {
        if (imageIds.size() > MAX_SELECTED_PHOTOS) {
            throw new BadRequestException("At most " + MAX_SELECTED_PHOTOS + " photos per post");
        }
        Map<Long, CarImage> byId = new HashMap<>();
        for (CarImage image : images.findByCarId(carId)) {
            byId.put(image.id(), image);
        }
        List<Photo> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(imageIds)) {
            CarImage image = byId.get(id);
            if (image == null) {
                throw new BadRequestException("Photo " + id + " is not a photo of car " + carId);
            }
            out.add(read(image));
        }
        return out;
    }

    private Photo read(CarImage image) {
        String path = image.storagePath();
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return new Photo(storage.read(path), ext);
    }

    private List<Photo> photos(long carId, int limit) {
        List<Photo> out = new ArrayList<>();
        for (CarImage image : images.findByCarId(carId)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(read(image));
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

    /** Scales a JPEG down (and re-encodes it) until it is at most {@code maxBytes}; small ones are kept as they are. */
    static byte[] fitWithin(byte[] jpeg, int maxBytes) {
        if (jpeg.length <= maxBytes) {
            return jpeg;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image == null) {
                return jpeg;
            }
            byte[] out = jpeg;
            for (int width = Math.min(image.getWidth(), 1600); width >= 400 && out.length > maxBytes;
                 width = width * 3 / 4) {
                int height = Math.max(1, image.getHeight() * width / image.getWidth());
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, width, height, java.awt.Color.WHITE, null);
                g.dispose();
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(scaled, "jpg", bytes);
                out = bytes.toByteArray();
            }
            return out;
        } catch (IOException e) {
            return jpeg;
        }
    }

    private void requireCar(long carId) {
        if (cars.findById(carId).isEmpty()) {
            throw new NotFoundException("Car not found: " + carId);
        }
    }
}
