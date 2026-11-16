package com.gatekeeper.apikey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyHasherTest {

    private ApiKeyHasher hasher;

    @BeforeEach
    void setUp() {
        this.hasher = new ApiKeyHasher();
    }

    @Test
    @DisplayName("Hash output must be exactly 64 lowercase hexadecimal characters")
    void testHashFormat() {
        String secret = "abcdefghijklmnopqrstuvwxyz1234567890-_ABCD";
        String digest = this.hasher.hashSecret(secret);

        assertThat(digest).hasSize(64);
        assertThat(digest).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Hashing is deterministic")
    void testDeterministicHashing() {
        String secret = "sampleSecretMaterialWithHighEntropy12345678";
        String digest1 = this.hasher.hashSecret(secret);
        String digest2 = this.hasher.hashSecret(secret);

        assertThat(digest1).isEqualTo(digest2);
    }

    @Test
    @DisplayName("Verify returns true for matching secret and digest")
    void testVerifyMatching() {
        String secret = "mySecureRandomKeySecretContentForVerification";
        String digest = this.hasher.hashSecret(secret);

        assertThat(this.hasher.verify(secret, digest)).isTrue();
    }

    @Test
    @DisplayName("Verify returns false for mismatched secret, wrong digest, or invalid inputs")
    void testVerifyMismatched() {
        String secret = "correctSecretValue1234567890123456789012345";
        String digest = this.hasher.hashSecret(secret);

        assertThat(this.hasher.verify("wrongSecretValue1234567890123456789012345", digest)).isFalse();
        assertThat(this.hasher.verify(secret, "invalidHexLengthOrCharacters")).isFalse();
        assertThat(this.hasher.verify(null, digest)).isFalse();
        assertThat(this.hasher.verify(secret, null)).isFalse();
    }

    @Test
    @DisplayName("Null input to hashSecret throws NullPointerException")
    void testNullSecretThrows() {
        assertThatThrownBy(() -> this.hasher.hashSecret(null))
            .isInstanceOf(NullPointerException.class);
    }
}
