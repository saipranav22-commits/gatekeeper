package com.gatekeeper.security.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
@ConfigurationProperties(prefix = "gatekeeper.security.jwt")
public class JwtProperties {

    private String secretKey;
    private String issuer = "gatekeeper-auth";
    private long accessTokenExpirationSeconds = 900; // 15 minutes

    @PostConstruct
    public void validate() {
        if (secretKey == null || secretKey.trim().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "JWT signing key ('gatekeeper.security.jwt.secret-key') must be configured " +
                "and contain at least 256 bits (32 bytes) of cryptographic entropy."
            );
        }
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public void setAccessTokenExpirationSeconds(long accessTokenExpirationSeconds) {
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }
}
