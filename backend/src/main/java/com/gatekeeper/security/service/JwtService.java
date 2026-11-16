package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.security.config.JwtProperties;
import com.gatekeeper.security.dto.AdminJwtClaims;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties jwtProperties;
    private final byte[] secretKeyBytes;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKeyBytes = jwtProperties.getSecretKey().trim().getBytes(StandardCharsets.UTF_8);
        if (this.secretKeyBytes.length < 32) {
            throw new IllegalStateException("JWT secret key must be at least 256 bits (32 bytes)");
        }
    }

    public String generateToken(UUID adminId, String username, String role) {
        Objects.requireNonNull(adminId, "adminId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(role, "role must not be null");

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.getAccessTokenExpirationSeconds());

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(adminId.toString())
            .claim(CLAIM_USERNAME, username)
            .claim(CLAIM_ROLE, role)
            .issuer(jwtProperties.getIssuer())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);

        try {
            JWSSigner signer = new MACSigner(secretKeyBytes);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            log.error("Failed to sign JWT access token", e);
            throw new IllegalStateException("Failed to generate security token", e);
        }
    }

    public AdminJwtClaims parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new AdminAuthenticationException("JWT token is missing or empty");
        }

        SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(token);
        } catch (ParseException e) {
            log.warn("Malformed JWT token rejected: {}", e.getMessage());
            throw new AdminAuthenticationException("Malformed or unparseable JWT token");
        }

        // Strict algorithm verification: must be HS256
        if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
            log.warn("Rejected JWT with unexpected algorithm: {}", signedJWT.getHeader().getAlgorithm());
            throw new AdminAuthenticationException("Invalid token signature algorithm");
        }

        // Cryptographic signature verification
        try {
            JWSVerifier verifier = new MACVerifier(secretKeyBytes);
            if (!signedJWT.verify(verifier)) {
                log.warn("JWT signature verification failed");
                throw new AdminAuthenticationException("Invalid token signature");
            }
        } catch (JOSEException e) {
            log.warn("JWT verification error: {}", e.getMessage());
            throw new AdminAuthenticationException("Invalid token signature");
        }

        JWTClaimsSet claimsSet;
        try {
            claimsSet = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new AdminAuthenticationException("Unable to read JWT claims");
        }

        // Issuer verification
        if (!jwtProperties.getIssuer().equals(claimsSet.getIssuer())) {
            log.warn("JWT issuer mismatch: expected '{}', found '{}'", jwtProperties.getIssuer(), claimsSet.getIssuer());
            throw new AdminAuthenticationException("Invalid token issuer");
        }

        // Expiration check
        Date expirationTime = claimsSet.getExpirationTime();
        if (expirationTime == null || expirationTime.before(new Date())) {
            log.warn("JWT has expired (expired at {})", expirationTime);
            throw new AdminAuthenticationException("Authentication token has expired");
        }

        // Subject validation
        String subject = claimsSet.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AdminAuthenticationException("Missing token subject");
        }

        UUID adminId;
        try {
            adminId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new AdminAuthenticationException("Invalid subject identifier in token");
        }

        String username = (String) claimsSet.getClaim(CLAIM_USERNAME);
        String role = (String) claimsSet.getClaim(CLAIM_ROLE);

        if (username == null || username.isBlank() || role == null || role.isBlank()) {
            throw new AdminAuthenticationException("Token missing required identity claims");
        }

        Instant issuedAt = claimsSet.getIssueTime() != null ? claimsSet.getIssueTime().toInstant() : Instant.now();
        Instant expiresAt = expirationTime.toInstant();

        return new AdminJwtClaims(adminId, username, role, issuedAt, expiresAt);
    }
}
