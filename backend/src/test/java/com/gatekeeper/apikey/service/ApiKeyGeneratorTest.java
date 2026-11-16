package com.gatekeeper.apikey.service;

import com.gatekeeper.common.exception.InvalidApiKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyGeneratorTest {

    private ApiKeyGenerator generator;

    @BeforeEach
    void setUp() {
        this.generator = new ApiKeyGenerator();
    }

    @Test
    @DisplayName("Generated key must adhere to exact format and length specifications")
    void testKeyFormatAndLength() {
        ApiKeyGenerator.GeneratedApiKey key = this.generator.generateKey();

        assertThat(key.fullKey()).isNotNull();
        assertThat(key.fullKey()).hasSize(ApiKeyGenerator.TOTAL_KEY_LENGTH); // 68 chars
        assertThat(key.fullKey()).startsWith("gk_live_");
        assertThat(key.fullKey().charAt(24)).isEqualTo('_');

        assertThat(key.keyPrefix()).hasSize(ApiKeyGenerator.PREFIX_LENGTH); // 24 chars
        assertThat(key.keyPrefix()).startsWith("gk_live_");
        assertThat(key.keyPrefix().substring(8)).matches("^[0-9a-f]{16}$");

        assertThat(key.secret()).hasSize(ApiKeyGenerator.SECRET_BASE64_LENGTH); // 43 chars
        assertThat(key.secret()).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    @DisplayName("Generated keys must be unique across multiple invocations")
    void testKeyUniqueness() {
        int count = 1000;
        Set<String> fullKeys = new HashSet<>(count);
        Set<String> prefixes = new HashSet<>(count);

        for (int i = 0; i < count; i++) {
            ApiKeyGenerator.GeneratedApiKey key = this.generator.generateKey();
            fullKeys.add(key.fullKey());
            prefixes.add(key.keyPrefix());
        }

        assertThat(fullKeys).hasSize(count);
        assertThat(prefixes).hasSize(count);
    }

    @Test
    @DisplayName("Prefix collision retry should retry until a unique prefix is found")
    void testCollisionRetrySuccess() {
        AtomicInteger attempts = new AtomicInteger(0);
        // Simulate 2 collisions before succeeding
        ApiKeyGenerator.GeneratedApiKey key = this.generator.generateUniqueKey(prefix -> attempts.getAndIncrement() < 2, 5);

        assertThat(key).isNotNull();
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Prefix collision retry should throw when maximum retries exhausted")
    void testCollisionRetryExhausted() {
        assertThatThrownBy(() -> this.generator.generateUniqueKey(prefix -> true, 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to generate a unique API key prefix after 3 attempts");
    }

    @Test
    @DisplayName("Valid key parses into exact prefix and secret")
    void testValidKeyParsing() {
        ApiKeyGenerator.GeneratedApiKey key = this.generator.generateKey();
        ApiKeyGenerator.ParsedApiKey parsed = this.generator.parseKey(key.fullKey());

        assertThat(parsed.keyPrefix()).isEqualTo(key.keyPrefix());
        assertThat(parsed.secret()).isEqualTo(key.secret());
    }

    @Test
    @DisplayName("Malformed keys are strictly rejected")
    void testMalformedKeyParsing() {
        // Null key
        assertThatThrownBy(() -> this.generator.parseKey(null))
            .isInstanceOf(InvalidApiKeyException.class);

        // Incorrect length
        assertThatThrownBy(() -> this.generator.parseKey("gk_live_12345"))
            .isInstanceOf(InvalidApiKeyException.class);

        // Missing gk_live_ tag
        String wrongTag = "test_live_0123456789abcdef_1234567890123456789012345678901234567890123";
        assertThatThrownBy(() -> this.generator.parseKey(wrongTag))
            .isInstanceOf(InvalidApiKeyException.class);

        // Missing underscore delimiter at position 24
        String missingDelimiter = "gk_live_0123456789abcdefX1234567890123456789012345678901234567890123";
        assertThatThrownBy(() -> this.generator.parseKey(missingDelimiter))
            .isInstanceOf(InvalidApiKeyException.class);

        // Uppercase or invalid characters in hex prefix
        String invalidHex = "gk_live_0123456789ABCDEF_1234567890123456789012345678901234567890123";
        assertThatThrownBy(() -> this.generator.parseKey(invalidHex))
            .isInstanceOf(InvalidApiKeyException.class);
    }
}
