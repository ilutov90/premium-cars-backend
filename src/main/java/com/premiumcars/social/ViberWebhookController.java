package com.premiumcars.social;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.premiumcars.config.AppProperties;

/**
 * Viber's callback URL for the channel. Viber requires one before a channel accepts posts and calls it once when it
 * is registered; the site does not act on channel events. Requests not signed with the channel token
 * (X-Viber-Content-Signature = hex HMAC-SHA256 of the body) are refused.
 */
@RestController
public class ViberWebhookController {

    public static final String PATH = "/api/public/viber/webhook";

    private static final Logger log = LoggerFactory.getLogger(ViberWebhookController.class);

    private final String token;

    public ViberWebhookController(AppProperties props) {
        this.token = props.social().viberChannelToken();
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> callback(@RequestBody(required = false) byte[] body,
                                         @RequestHeader(value = "X-Viber-Content-Signature", required = false)
                                         String signature) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (!validSignature(token, body == null ? new byte[0] : body, signature)) {
            log.warn("Viber callback with a missing or wrong signature refused");
            return ResponseEntity.status(403).build();
        }
        log.debug("Viber callback accepted ({} bytes)", body == null ? 0 : body.length);
        return ResponseEntity.ok().build();
    }

    static boolean validSignature(String token, byte[] body, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
