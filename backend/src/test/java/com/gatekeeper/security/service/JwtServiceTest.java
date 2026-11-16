package com.gatekeeper.security.service;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.security.config.JwtProperties;
import com.gatekeeper.security.dto.AdminJwtClaims;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"; // 64 chars = 256 bits
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private JwtProperties jwtProperties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecretKey(TEST_SECRET);
        jwtProperties.setIssuer("gatekeeper-auth");
        jwtProperties.setAccessTokenExpirationSeconds(900);
        jwtService = new JwtService(jwtProperties);
    }

    @Test
    @DisplayName("Reject weak or absent JWT signing keys")
    void testWeakOrAbsentSigningKey() {
        JwtProperties weakProps = new JwtProperties();
        weakProps.setSecretKey("short-key");
        assertThatThrownBy(weakProps::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 256 bits");

        assertThatThrownBy(() -> new JwtService(weakProps))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Generate and parse valid JWT access token")
    void testGenerateAndValidateToken() {
        UUID adminId = UUID.randomUUID();
        String username = "secops";
        String role = "ROLE_SUPER_ADMIN";

        String token = jwtService.generateToken(adminId, username, role);
        assertThat(token).isNotBlank();

        AdminJwtClaims claims = jwtService.parseAndValidateToken(token);
        assertThat(claims.adminId()).isEqualTo(adminId);
        assertThat(claims.username()).isEqualTo(username);
        assertThat(claims.role()).isEqualTo(role);
        assertThat(claims.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Reject expired JWT token")
    void testRejectExpiredToken() throws Exception {
        UUID adminId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(100);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(adminId.toString())
            .claim("username", "admin")
            .claim("role", "ROLE_ADMIN")
            .issuer("gatekeeper-auth")
            .issueTime(Date.from(past.minusSeconds(900)))
            .expirationTime(Date.from(past))
            .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String expiredToken = signedJWT.serialize();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(expiredToken))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Reject forged JWT signed with incorrect secret key")
    void testRejectForgedSignature() throws Exception {
        UUID adminId = UUID.randomUUID();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(adminId.toString())
            .claim("username", "admin")
            .claim("role", "ROLE_ADMIN")
            .issuer("gatekeeper-auth")
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(new MACSigner(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)));
        String forgedToken = signedJWT.serialize();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(forgedToken))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("Invalid token signature");
    }

    @Test
    @DisplayName("Reject JWT with invalid issuer")
    void testRejectInvalidIssuer() throws Exception {
        UUID adminId = UUID.randomUUID();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(adminId.toString())
            .claim("username", "admin")
            .claim("role", "ROLE_ADMIN")
            .issuer("evil-issuer")
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String badIssuerToken = signedJWT.serialize();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badIssuerToken))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("Invalid token issuer");
    }

    @Test
    @DisplayName("Reject JWT with unsupported algorithm")
    void testRejectUnsupportedAlgorithm() throws Exception {
        // Build JWT with unexpected algorithm in header
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS512).build();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .claim("username", "admin")
            .claim("role", "ROLE_ADMIN")
            .issuer("gatekeeper-auth")
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String badAlgoToken = signedJWT.serialize();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badAlgoToken))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("Invalid token signature algorithm");
    }

    @Test
    @DisplayName("Reject missing or malformed JWT token")
    void testRejectMissingOrMalformed() {
        assertThatThrownBy(() -> jwtService.parseAndValidateToken(null))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("missing");

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(""))
            .isInstanceOf(AdminAuthenticationException.class);

        assertThatThrownBy(() -> jwtService.parseAndValidateToken("not.a.valid.jwt"))
            .isInstanceOf(AdminAuthenticationException.class)
            .hasMessageContaining("Malformed");
    }

    @Test
    @DisplayName("Verify JWT contains no password or sensitive information")
    void testNoSensitiveClaimsInJwt() throws Exception {
        UUID adminId = UUID.randomUUID();
        String token = jwtService.generateToken(adminId, "superadmin", "ROLE_SUPER_ADMIN");

        SignedJWT signedJWT = SignedJWT.parse(token);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        assertThat(claims.getClaim("password")).isNull();
        assertThat(claims.getClaim("passwordHash")).isNull();
        assertThat(claims.getClaim("password_hash")).isNull();
        assertThat(claims.getClaim("secret")).isNull();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder(
            "sub", "username", "role", "iss", "iat", "exp"
        );
    }
}
