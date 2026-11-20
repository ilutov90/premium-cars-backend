package com.premiumcars.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.premiumcars.common.BadRequestException;

class UnconfiguredOrderDocumentStorageTest {

    @Test
    void testPut_NoBucketConfigured_RefusesWithAMessage() {
        assertThatThrownBy(() -> new UnconfiguredOrderDocumentStorage().put(1, new byte[] {1}, "application/pdf"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("R2_DOCS_BUCKET");
    }
}
