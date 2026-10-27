package com.premiumcars.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class SigV4Test {

    /** AWS's documented example "GET Object" (Signature Version 4, header-based authentication). */
    @Test
    void awsGetObjectExample() {
        var headers = new TreeMap<String, String>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_PAYLOAD_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String auth = SigV4.authorization("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "us-east-1", "s3", "GET", "/test.txt", "", headers, SigV4.EMPTY_PAYLOAD_SHA256, "20130524T000000Z");

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void sha256OfNothing() {
        assertThat(SigV4.sha256Hex("".getBytes(StandardCharsets.UTF_8))).isEqualTo(SigV4.EMPTY_PAYLOAD_SHA256);
    }
}
