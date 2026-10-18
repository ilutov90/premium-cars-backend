package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Viber signs every callback with the channel token: hex HMAC-SHA256 of the raw body. */
class ViberWebhookControllerTest {

    private static final byte[] BODY = "{\"event\":\"webhook\",\"timestamp\":1457764197627,\"message_token\":241256543215}"
            .getBytes(StandardCharsets.UTF_8);

    // HMAC-SHA256(key = "channel-token", BODY), computed independently with:
    // printf '%s' "$BODY" | openssl dgst -sha256 -hmac channel-token
    private static final String SIGNATURE = "bc7ed909415bb63f2718efa2df31b9db33e1f796447a1660e66cf746e2064065";

    @Test
    void acceptsOnlyTheSignatureMadeWithTheChannelToken() {
        String good = SIGNATURE;

        assertThat(ViberWebhookController.validSignature("channel-token", BODY, good)).isTrue();
        assertThat(ViberWebhookController.validSignature("channel-token", BODY, good.toUpperCase())).isTrue();
        assertThat(ViberWebhookController.validSignature("other-token", BODY, good)).isFalse();
        assertThat(ViberWebhookController.validSignature("channel-token", "{}".getBytes(StandardCharsets.UTF_8), good))
                .isFalse();
        assertThat(ViberWebhookController.validSignature("channel-token", BODY, null)).isFalse();
        assertThat(ViberWebhookController.validSignature("channel-token", BODY, "")).isFalse();
    }

}
