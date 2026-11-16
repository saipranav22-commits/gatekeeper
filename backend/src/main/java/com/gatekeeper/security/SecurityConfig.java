package com.gatekeeper.security;

import com.gatekeeper.security.filter.GatekeeperAccessDeniedHandler;
import com.gatekeeper.security.filter.GatekeeperAuthenticationEntryPoint;
import com.gatekeeper.security.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Gatekeeper Spring Security Configuration.
 *
 * <p>Architectural Security Decisions:
 * <ul>
 *   <li><b>Stateless Session Management:</b> The API is strictly stateless; no HTTP sessions
 *       or session cookies are created or stored ({@link SessionCreationPolicy#STATELESS}).</li>
 *   <li><b>CSRF Protection Disabled:</b> Cross-Site Request Forgery protection is disabled because
 *       Gatekeeper operates as a stateless REST API using HTTP {@code Authorization: Bearer <token>}
 *       headers. Browsers do not automatically attach bearer tokens to cross-origin requests,
 *       making standard CSRF exploits inapplicable.</li>
 *   <li><b>Fail-Secure Default:</b> Only {@code /api/v1/health} and {@code /api/v1/auth/login}
 *       are public. All other endpoints require explicit authentication.</li>
 *   <li><b>Separation of Admin and Consumer Auth:</b> Administrator JWT authentication operates
 *       independently from upcoming consumer API key rate-limiting proxies.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final GatekeeperAuthenticationEntryPoint authenticationEntryPoint;
    private final GatekeeperAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            GatekeeperAuthenticationEntryPoint authenticationEntryPoint,
            GatekeeperAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
