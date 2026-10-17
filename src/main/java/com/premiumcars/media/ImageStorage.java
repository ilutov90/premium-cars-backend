package com.premiumcars.media;

/**
 * Stores car photos. The MVP writes to a local folder; a later S3/Cloudflare R2 implementation
 * only needs to implement this interface.
 */
public interface ImageStorage {

    /**
     * @param folder    logical folder, e.g. "cars/15"
     * @param bytes     image content
     * @param extension file extension without dot, e.g. "jpg"
     * @return storage path to keep in the database
     */
    String store(String folder, byte[] bytes, String extension);

    void delete(String storagePath);

    /** The stored bytes (e.g. to upload a photo to Facebook). */
    byte[] read(String storagePath);

    /** Public URL the browser loads the image from. */
    String publicUrl(String storagePath);
}
