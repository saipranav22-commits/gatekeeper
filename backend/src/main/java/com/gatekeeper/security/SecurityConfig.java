package com.gatekeeper.security;

import com.gatekeeper.gateway.filter.ConsumerApiKeyAuthenticationFilter;
import com.gatekeeper.gateway.filter.ConsumerRateLimitFilter;
import com.gatekeeper.gateway.filter.GatewayUsageTrackingFilter;
import com.gatekeeper.security.filter.GatekeeperAccessDeniedHandler;
import com.gatekeeper.security.filter.GatekeeperAuthenticationEntryPoint;
import com.gatekeeper.security.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Gatekeeper Spring Security Configuration.
 *
 * <p>Architectural Security Decisions:
 * <ul>
 *   <li><b>Stateless Session Management:</b> The API is strictly stateless; no HTTP sessions
 *       or session cookies are created or stored ({@link SessionCreationPolicy#STATELESS}).</li>
 *   <li><b>CSRF Protection Disabled:</b> Cross-Site Request Forgery protection is disabled because
 *       Gatekeeper operates as a stateless REST API using HTTP headers (Authorization: Bearer or X-API-Key).
 *       Browsers do not automatically attach these custom headers to cross-origin requests.</li>
 *   <li><b>Separation of Filter Chains:</b>
 *       <ol>
 *         <li><b>Gateway Security Chain (Order 1):</b> Matches {@code /api/v1/gateway/**}. Requires valid
 *             consumer {@code X-API-Key}, performs scope authorization, and applies atomic Token Bucket
 *             rate limiting. Administrator JWTs cannot access this chain.</li>
 *         <li><b>Management Security Chain (Order 2):</b> Matches all remaining routes. Requires administrator
 *             JWT Bearer tokens. Only {@code /api/v1/health} and {@code /api/v1/auth/login} are public.
 *             Consumer API keys cannot access management endpoints.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ConsumerApiKeyAuthenticationFilter consumerApiKeyAuthFilter;
    private final ConsumerRateLimitFilter consumerRateLimitFilter;
    private final GatewayUsageTrackingFilter gatewayUsageTrackingFilter;
    private final GatekeeperAuthenticationEntryPoint authenticationEntryPoint;
    private final GatekeeperAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsumerApiKeyAuthenticationFilter consumerApiKeyAuthFilter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ConsumerRateLimitFilter consumerRateLimitFilter,
            @org.springframework.beans.factory.annotation.Autowired(required = false) GatewayUsageTrackingFilter gatewayUsageTrackingFilter,
            GatekeeperAuthenticationEntryPoint authenticationEntryPoint,
            GatekeeperAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.consumerApiKeyAuthFilter = consumerApiKeyAuthFilter;
        this.consumerRateLimitFilter = consumerRateLimitFilter;
        this.gatewayUsageTrackingFilter = gatewayUsageTrackingFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * Consumer Gateway Filter Chain: dedicated to external consumer API traffic.
     * Authenticates via X-API-Key and enforces atomic Redis token bucket rate limiting.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/gateway/**", "/api/v1/proxy/**")
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(authorize -> authorize
                .anyRequest().authenticated()
            );

        if (consumerApiKeyAuthFilter != null) {
            http.addFilterBefore(consumerApiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }
        if (consumerRateLimitFilter != null && consumerApiKeyAuthFilter != null) {
            http.addFilterAfter(consumerRateLimitFilter, ConsumerApiKeyAuthenticationFilter.class);
        }
        if (gatewayUsageTrackingFilter != null) {
            if (consumerApiKeyAuthFilter != null) {
                http.addFilterBefore(gatewayUsageTrackingFilter, ConsumerApiKeyAuthenticationFilter.class);
            } else {
                http.addFilterBefore(gatewayUsageTrackingFilter, UsernamePasswordAuthenticationFilter.class);
            }
        }

        return http.build();
    }

    /**
     * Administrator Management Filter Chain: dedicated to administrative REST APIs.
     * Authenticates via Authorization: Bearer <admin-jwt>.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/v1/health", "/actuator/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Value("${gatekeeper.security.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000,http://127.0.0.1:3000}")
    private List<String> allowedOrigins;

    /**
     * CORS configuration explicitly permitting configured frontend development and deployment origins.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin", "X-API-Key"));
        configuration.setExposedHeaders(List.of("Authorization", "Location", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
