package com.gatekeeper.apikey.service;

import com.gatekeeper.common.exception.InvalidApiKeyException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Cryptographically secure generator and parser for Gatekeeper API keys.
 * <p>
 * Key Format: {@code gk_live_<publicId>_<secret>}
 * <ul>
 *   <li>Environment Tag: {@code gk_live_} (8 characters)</li>
 *   <li>Public Identifier: 16 lowercase hex characters (64 bits of entropy)</li>
 *   <li>Public Lookup Prefix: {@code gk_live_<publicId>} (exactly 24 characters)</li>
 *   <li>Secret Material: 32 random bytes (256 bits of entropy) encoded as URL-safe Base64 without padding (43 characters)</li>
 *   <li>Total Key Length: exactly 68 characters</li>
 * </ul>
 */
@Component
public class ApiKeyGenerator {

    public static final String PREFIX_TAG = "gk_live_";
    public static final int PUBLIC_ID_BYTES = 8;
    public static final int PUBLIC_ID_HEX_LENGTH = 16;
    public static final int PREFIX_LENGTH = 24; // 8 (gk_live_) + 16 (hex)
    public static final int SECRET_BYTES = 32;   // 256 bits of entropy
    public static final int SECRET_BASE64_LENGTH = 43; // 32 bytes in URL-safe base64 unpadded
    public static final int TOTAL_KEY_LENGTH = 68; // 24 (prefix) + 1 (_) + 43 (secret)

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^gk_live_[0-9a-f]{16}$");
    private static final Pattern SECRET_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final SecureRandom secureRandom;
    private final Base64.Encoder base64Encoder;
    private final HexFormat hexFormat;

    public ApiKeyGenerator() {
        this.secureRandom = new SecureRandom();
        this.base64Encoder = Base64.getUrlEncoder().withoutPadding();
        this.hexFormat = HexFormat.of();
    }

    public record GeneratedApiKey(String fullKey, String keyPrefix, String secret) {
        public GeneratedApiKey {
            Objects.requireNonNull(fullKey, "fullKey must not be null");
            Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
        }
    }

    public record ParsedApiKey(String keyPrefix, String secret) {
        public ParsedApiKey {
            Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
        }
    }

    /**
     * Generates a new API key pair with independent public identifier and secret material.
     */
    public GeneratedApiKey generateKey() {
        byte[] publicIdBytes = new byte[PUBLIC_ID_BYTES];
        this.secureRandom.nextBytes(publicIdBytes);
        String publicId = this.hexFormat.formatHex(publicIdBytes);
        String keyPrefix = PREFIX_TAG + publicId;

        byte[] secretBytes = new byte[SECRET_BYTES];
        this.secureRandom.nextBytes(secretBytes);
        String secret = this.base64Encoder.encodeToString(secretBytes);

        String fullKey = keyPrefix + "_" + secret;
        return new GeneratedApiKey(fullKey, keyPrefix, secret);
    }

    /**
     * Generates an API key with bounded retries against a collision detection predicate.
     *
     * @param prefixExistsChecker predicate returning true if the prefix already exists in database
     * @param maxRetries          maximum collision resolution attempts (minimum 1)
     * @return unique GeneratedApiKey
     * @throws IllegalStateException if collision persists after maxRetries
     */
    public GeneratedApiKey generateUniqueKey(Predicate<String> prefixExistsChecker, int maxRetries) {
        int attempts = Math.max(1, maxRetries);
        for (int i = 0; i < attempts; i++) {
            GeneratedApiKey candidate = generateKey();
            if (!prefixExistsChecker.test(candidate.keyPrefix())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique API key prefix after " + attempts + " attempts");
    }

    /**
     * Strictly parses and validates a raw API key string without exposing secrets.
     *
     * @param rawKey raw input key
     * @return ParsedApiKey containing prefix and secret
     * @throws InvalidApiKeyException if key is null, wrong length, or fails format checks
     */
    public ParsedApiKey parseKey(String rawKey) {
        if (rawKey == null || rawKey.length() != TOTAL_KEY_LENGTH) {
            throw new InvalidApiKeyException("Invalid API key format or length");
        }

        if (!rawKey.startsWith(PREFIX_TAG)) {
            throw new InvalidApiKeyException("API key must start with expected environment prefix");
        }

        if (rawKey.charAt(PREFIX_LENGTH) != '_') {
            throw new InvalidApiKeyException("API key missing delimiter between prefix and secret");
        }

        String keyPrefix = rawKey.substring(0, PREFIX_LENGTH);
        String secret = rawKey.substring(PREFIX_LENGTH + 1);

        if (!PREFIX_PATTERN.matcher(keyPrefix).matches()) {
            throw new InvalidApiKeyException("API key public prefix contains invalid characters");
        }

        if (!SECRET_PATTERN.matcher(secret).matches()) {
            throw new InvalidApiKeyException("API key secret contains invalid characters");
        }

        return new ParsedApiKey(keyPrefix, secret);
    }
}
